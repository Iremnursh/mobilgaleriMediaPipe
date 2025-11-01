package com.example.mobilgaleri.bench

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

// (MediaPipe ve MLKit dedektörü burada TANIMLI DEĞİL, Backends.kt'de.)

/* ==========================
   Kıyasları PROD ile eşleştirmek + OOM koruması
   ========================== */
object BenchConfig {
    const val USE_DOWNSAMPLE = false
    const val TARGET_SHORT = 1600

    // OOM sınırları (detect() öncesi)
    const val CAP_SHORT  = 1280
    const val CAP_LONG   = 2560
    const val CAP_MAX_MP = 3_000_000

    const val USE_HARDWARE_ALLOC = false
    const val MODE = com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST
    const val MIN_FACE_SIZE = 0.10f
    const val NEG_MAX_SAMPLES = 1000
    const val NEG_WARMUP = 10
    const val RANDOM_SEED = 42L
    const val SAVE_FP_PREVIEWS = false
}

/* ----------------------------- Yardımcı Tipler ----------------------------- */
data class Box(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
    fun area(): Int = (x2 - x1).coerceAtLeast(0) * (y2 - y1).coerceAtLeast(0)
}

fun iou(a: Box, b: Box): Double {
    val ix1 = maxOf(a.x1, b.x1)
    val iy1 = maxOf(a.y1, b.y1)
    val ix2 = minOf(a.x2, b.x2)
    val iy2 = minOf(a.y2, b.y2)
    val inter = (ix2 - ix1).coerceAtLeast(0) * (iy2 - iy1).coerceAtLeast(0)
    val denom = a.area() + b.area() - inter
    return if (denom <= 0) 0.0 else inter.toDouble() / denom.toDouble()
}

/** Greedy tek-eşleştirme */
fun greedyMatch(a: List<Box>, b: List<Box>, thr: Double): Pair<Int, Double> {
    if (a.isEmpty() || b.isEmpty()) return 0 to 0.0
    val pairs = ArrayList<Triple<Int, Int, Double>>(a.size * b.size)
    for (i in a.indices) for (j in b.indices) pairs += Triple(i, j, iou(a[i], b[j]))
    pairs.sortByDescending { it.third }
    val usedA = BooleanArray(a.size)
    val usedB = BooleanArray(b.size)
    var tp = 0
    var iouSum = 0.0
    for ((ia, jb, v) in pairs) {
        if (v >= thr && !usedA[ia] && !usedB[jb]) {
            usedA[ia] = true; usedB[jb] = true
            tp++; iouSum += v
        }
    }
    val avgIou = if (tp > 0) iouSum / tp else 0.0
    return tp to avgIou
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/* ----------------------------- Görsel çizim ----------------------------- */
private fun drawBoxes(src: Bitmap, boxes: List<Box>): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true)
    val c = Canvas(out)
    val p = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = maxOf(3f, src.width * 0.003f)
    }
    for (b in boxes) c.drawRect(b.x1.toFloat(), b.y1.toFloat(), b.x2.toFloat(), b.y2.toFloat(), p)
    return out
}

private fun saveBitmapJpeg(file: File, bmp: Bitmap, quality: Int = 92) {
    file.outputStream().use { os -> bmp.compress(Bitmap.CompressFormat.JPEG, quality, os) }
}

private fun f2(x: Double) = java.lang.String.format(java.util.Locale.US, "%.2f", x)

private fun wilsonCI(pHat: Double, n: Int, z: Double = 1.96): Pair<Double, Double> {
    if (n <= 0) return 0.0 to 0.0
    val z2 = z * z
    val denom = 1 + z2 / n
    val center = (pHat + z2 / (2.0 * n)) / denom
    val margin = (z * kotlin.math.sqrt(pHat * (1 - pHat) / n + z2 / (4.0 * n * n))) / denom
    val lo = (center - margin).coerceIn(0.0, 1.0)
    val hi = (center + margin).coerceIn(0.0, 1.0)
    return lo to hi
}

private fun rotateIfNeeded(bmp: Bitmap, exif: ExifInterface): Bitmap {
    val rot = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90   -> 90
        ExifInterface.ORIENTATION_ROTATE_180  -> 180
        ExifInterface.ORIENTATION_ROTATE_270  -> 270
        else -> 0
    }
    if (rot == 0) return bmp
    val m = Matrix().apply { postRotate(rot.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

private fun percentile(values: List<Double>, q: Double): Double {
    if (values.isEmpty()) return 0.0
    val a = values.sorted()
    val pos = ((a.size - 1) * q).coerceIn(0.0, (a.size - 1).toDouble())
    val lo = floor(pos).toInt()
    val hi = ceil(pos).toInt()
    return if (lo == hi) a[lo] else a[lo] + (a[hi] - a[lo]) * (pos - lo)
}

/* ----------------------------- Görsel Yardımcıları ----------------------------- */
private fun flipHorizontal(bmp: Bitmap): Bitmap = Bitmap.createBitmap(
    bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { preScale(-1f, 1f) }, true
)

private fun transformBoxFlipH(b: Box, w: Int): Box = Box(
    x1 = (w - b.x2).coerceIn(0, w),
    y1 = b.y1,
    x2 = (w - b.x1).coerceIn(0, w),
    y2 = b.y2
)

private fun downscale(bmp: Bitmap, shortSide: Int): Bitmap {
    val s = shortSide.toFloat() / minOf(bmp.width, bmp.height)
    if (s >= 1f) return bmp
    val nw = (bmp.width * s).toInt().coerceAtLeast(1)
    val nh = (bmp.height * s).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bmp, nw, nh, true)
}

private fun scaleBox(b: Box, sx: Float, sy: Float): Box = Box(
    (b.x1 * sx).toInt(), (b.y1 * sy).toInt(), (b.x2 * sx).toInt(), (b.y2 * sy).toInt()
)

/* --------- Detect öncesi zorunlu yeniden boyutlandırma --------- */
private fun resizeForDetect(bmp: Bitmap): Bitmap {
    var w = bmp.width
    var h = bmp.height
    var scale = 1.0
    if (BenchConfig.CAP_SHORT > 0) {
        val s = minOf(w, h).toDouble()
        scale = kotlin.math.min(scale, BenchConfig.CAP_SHORT.toDouble() / s)
    }
    if (BenchConfig.CAP_LONG > 0) {
        val l = maxOf(w, h).toDouble()
        scale = kotlin.math.min(scale, BenchConfig.CAP_LONG.toDouble() / l)
    }
    if (BenchConfig.CAP_MAX_MP > 0) {
        val mp = w.toLong() * h.toLong().toDouble()
        val sclMp = sqrt(BenchConfig.CAP_MAX_MP.toDouble() / mp)
        scale = kotlin.math.min(scale, sclMp)
    }
    if (scale >= 1.0) return bmp
    val nw = kotlin.math.max(1, (w * scale).toInt())
    val nh = kotlin.math.max(1, (h * scale).toInt())
    return Bitmap.createScaledBitmap(bmp, nw, nh, true)
}

/* ----------------------------- Runner ----------------------------- */
class LabelFreeBenchmark(
    private val context: Context,
    private val imagesTree: Uri,
    private val backend: FaceDetectorBackend, // Backends.kt’den
) {
    enum class Mode { LatencyOnly, NegativesFP, Consistency }

    private val cr: ContentResolver = context.contentResolver

    data class Doc(val uri: Uri, val name: String)

    private fun listChildren(tree: Uri): List<Doc> {
        val out = mutableListOf<Doc>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        cr.query(childrenUri, projection, null, null, null)?.use { c ->
            val idxId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val idxName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idxMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getString(idxId)
                val name = c.getString(idxName)
                val mime = c.getString(idxMime)
                if (mime?.startsWith("image/") == true ||
                    name.endsWith(".jpg", true) ||
                    name.endsWith(".jpeg", true) ||
                    name.endsWith(".png", true)
                ) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(imagesTree, id)
                    out += Doc(uri, name)
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** Hatalı/çok büyük görselleri atlayabilen güvenli decoder */
    private fun safeLoadBitmap(uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(cr, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.setOnPartialImageListener { true }
                if (BenchConfig.USE_DOWNSAMPLE) {
                    val short = minOf(info.size.width, info.size.height)
                    val ratio = short.toFloat() / BenchConfig.TARGET_SHORT.toFloat()
                    val sample = max(1, ceil(ratio).toInt())
                    decoder.setTargetSampleSize(sample)
                }
                decoder.allocator =
                    if (BenchConfig.USE_HARDWARE_ALLOC) ImageDecoder.ALLOCATOR_HARDWARE
                    else ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            cr.openInputStream(uri).use { inpBounds ->
                if (inpBounds == null) return null
                val optsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(inpBounds, null, optsBounds)
                val w = optsBounds.outWidth
                val h = optsBounds.outHeight
                var targetShort = minOf(w, h).coerceAtLeast(1)
                if (BenchConfig.CAP_SHORT > 0) targetShort = BenchConfig.CAP_SHORT

                var inSample = 1
                var shortNow = minOf(w, h).coerceAtLeast(1)
                while ((shortNow / (inSample * 2)) >= targetShort) inSample *= 2

                val opts = BitmapFactory.Options().apply {
                    inSampleSize = inSample
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inDither = true
                }
                cr.openInputStream(uri).use { inp ->
                    val raw = BitmapFactory.decodeStream(inp, null, opts) ?: return null
                    cr.openInputStream(uri).use { exifIn ->
                        val exif = if (exifIn != null) ExifInterface(exifIn) else null
                        if (exif != null) rotateIfNeeded(raw, exif) else raw
                    }
                }
            }
        }
    } catch (_: Throwable) { null }

    private suspend fun detectCommon(bmp: Bitmap): List<Box> = backend.detect(bmp)

    data class LatencyRow(
        val name: String,
        val decodeMs: Double,
        val detectMs: Double,
        val totalMs: Double,
        val count: Int
    )

    /** Latency: decode+resize ve detect ayrı ölçülür */
    suspend fun runLatencyOnly(): String = withContext(Dispatchers.IO) {
        val images = listChildren(imagesTree)
        require(images.isNotEmpty()) { "Klasör boş görünüyor." }

        // warm-up
        images.take(BenchConfig.NEG_WARMUP).forEach { img ->
            runCatching {
                val raw = safeLoadBitmap(img.uri) ?: return@runCatching
                val base = resizeForDetect(raw)
                detectCommon(base)
                if (base !== raw) base.recycle()
                raw.recycle()
            }
        }

        val errLog = StringBuilder().apply { appendLine("image,error") }
        var errCount = 0

        val rows = mutableListOf<LatencyRow>()
        for (img in images) {
            var raw: Bitmap? = null
            var base: Bitmap? = null
            try {
                val t0 = System.nanoTime()
                raw = safeLoadBitmap(img.uri)
                if (raw == null) { errCount++; errLog.appendLine("${img.name},decode_null"); continue }
                base = resizeForDetect(raw)
                val t1 = System.nanoTime()
                val preds = detectCommon(base)
                val t2 = System.nanoTime()
                rows += LatencyRow(
                    img.name,
                    decodeMs = (t1 - t0) / 1e6,
                    detectMs = (t2 - t1) / 1e6,
                    totalMs  = (t2 - t0) / 1e6,
                    count    = preds.size
                )
            } catch (t: Throwable) {
                errCount++; errLog.appendLine("${img.name},${t::class.simpleName}:${t.message}")
            } finally {
                if (base !== raw) runCatching { base?.recycle() }
                runCatching { raw?.recycle() }
            }
        }

        val detectList = rows.map { it.detectMs }
        val decodeList = rows.map { it.decodeMs }
        val totalList  = rows.map { it.totalMs }

        val summaryDetect = "detect_p50=${"%.2f".format(percentile(detectList,0.50))}, detect_p90=${"%.2f".format(percentile(detectList,0.90))}, detect_p95=${"%.2f".format(percentile(detectList,0.95))}"
        val summaryDecode = "decode_p50=${"%.2f".format(percentile(decodeList,0.50))}, decode_p90=${"%.2f".format(percentile(decodeList,0.90))}, decode_p95=${"%.2f".format(percentile(decodeList,0.95))}"
        val summaryTotal  = "total_p50=${"%.2f".format(percentile(totalList,0.50))}, total_p90=${"%.2f".format(percentile(totalList,0.90))}, total_p95=${"%.2f".format(percentile(totalList,0.95))}"

        val sb = StringBuilder()
        sb.appendLine("# backend=${backend.name()}")
        sb.appendLine("image,decode_ms,detect_ms,total_ms,face_count")
        rows.forEach { r ->
            sb.appendLine("${r.name},${"%.2f".format(r.decodeMs)},${"%.2f".format(r.detectMs)},${"%.2f".format(r.totalMs)},${r.count}")
        }
        sb.appendLine("SUMMARY,,,$summaryDetect")
        sb.appendLine("SUMMARY,,,$summaryDecode")
        sb.appendLine("SUMMARY,,,$summaryTotal")

        if (errCount > 0) saveText("errors.csv", errLog.toString())
        saveText("latency_only.csv", sb.toString())
    }

    /** Tutarlılık: flipH & downscale 256 kısa kenar */
    suspend fun runConsistency(iouThr: Double = 0.5, shortSide: Int = 256): String = withContext(Dispatchers.IO) {
        val images = listChildren(imagesTree)
        require(images.isNotEmpty()) { "Klasör boş görünüyor." }

        images.take(BenchConfig.NEG_WARMUP).forEach { img ->
            runCatching {
                val raw = safeLoadBitmap(img.uri) ?: return@runCatching
                val base = resizeForDetect(raw)
                detectCommon(base)
                if (base !== raw) base.recycle()
                raw.recycle()
            }
        }

        val sb = StringBuilder()
        sb.appendLine("# backend=${backend.name()}")
        sb.appendLine("image,orig_cnt,flip_cnt,flip_TP,flip_AugRecall,flip_AugPrecision,flip_avgIoU,down_cnt,down_TP,down_AugRecall,down_AugPrecision,down_avgIoU")

        var totOrig = 0; var totFlip = 0; var totDown = 0
        var tpFlip = 0; var tpDown = 0
        var iouFlipSum = 0.0; var iouDownSum = 0.0

        val errLog = StringBuilder().apply { appendLine("image,error") }
        var errCount = 0

        for (img in images) {
            var raw: Bitmap? = null
            var base: Bitmap? = null
            var flipBmp: Bitmap? = null
            var small: Bitmap? = null
            try {
                raw = safeLoadBitmap(img.uri)
                if (raw == null) { errCount++; errLog.appendLine("${img.name},decode_null"); continue }
                base = resizeForDetect(raw)

                val orig = detectCommon(base)
                totOrig += orig.size

                // FlipH
                flipBmp = flipHorizontal(base)
                val flip = detectCommon(flipBmp)
                val flipBack = flip.map { transformBoxFlipH(it, base.width) }
                totFlip += flipBack.size
                val (tpF, avgIouF) = greedyMatch(orig, flipBack, iouThr)
                tpFlip += tpF
                iouFlipSum += avgIouF * tpF
                val arF = if (orig.isNotEmpty()) tpF.toDouble() / orig.size else 0.0
                val apF = if (flipBack.isNotEmpty()) tpF.toDouble() / flipBack.size else 0.0

                // Downscale (→ 256 kısa kenar)
                small = downscale(base, shortSide)
                val sX = base.width.toFloat() / small.width.toFloat()
                val sY = base.height.toFloat() / small.height.toFloat()
                val down = detectCommon(small).map { scaleBox(it, sX, sY) }
                totDown += down.size
                val (tpD, avgIouD) = greedyMatch(orig, down, iouThr)
                tpDown += tpD
                iouDownSum += avgIouD * tpD
                val arD = if (orig.isNotEmpty()) tpD.toDouble() / orig.size else 0.0
                val apD = if (down.isNotEmpty()) tpD.toDouble() / down.size else 0.0

                sb.appendLine(listOf(
                    img.name,
                    orig.size,
                    flip.size,
                    tpF,
                    "%.4f".format(arF),
                    "%.4f".format(apF),
                    "%.4f".format(avgIouF),
                    down.size,
                    tpD,
                    "%.4f".format(arD),
                    "%.4f".format(apD),
                    "%.4f".format(avgIouD)
                ).joinToString(","))
            } catch (t: Throwable) {
                errCount++; errLog.appendLine("${img.name},${t::class.simpleName}:${t.message}")
            } finally {
                runCatching { flipBmp?.recycle() }
                if (small !== base && small !== raw) runCatching { small?.recycle() }
                if (base !== raw) runCatching { base?.recycle() }
                runCatching { raw?.recycle() }
            }
        }

        val rFlip = if (totOrig > 0) tpFlip.toDouble() / totOrig else 0.0
        val pFlip = if (totFlip > 0) tpFlip.toDouble() / totFlip else 0.0
        val avgIoUFlip = if (tpFlip > 0) iouFlipSum / tpFlip else 0.0

        val rDown = if (totOrig > 0) tpDown.toDouble() / totOrig else 0.0
        val pDown = if (totDown > 0) tpDown.toDouble() / totDown else 0.0
        val avgIoUDown = if (tpDown > 0) iouDownSum / tpDown else 0.0

        sb.appendLine(listOf(
            "SUMMARY_FLIP", totOrig, totFlip, tpFlip,
            "%.4f".format(rFlip), "%.4f".format(pFlip), "%.4f".format(avgIoUFlip),
            "", "", "", "", ""
        ).joinToString(","))

        sb.appendLine(listOf(
            "SUMMARY_DOWN", totOrig, "", "", "", "", "",
            totDown, tpDown, "%.4f".format(rDown),
            "%.4f".format(pDown), "%.4f".format(avgIoUDown)
        ).joinToString(","))

        if (errCount > 0) saveText("errors.csv", errLog.toString())
        saveText("consistency.csv", sb.toString())
    }

    private fun saveText(fileName: String, content: String): String {
        val dir = File(context.getExternalFilesDir("bench"), "")
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content)
        return file.absolutePath
    }

    /** NegativesFP: yüz içermeyen klasör; her tespit FP kabul */
    suspend fun runNegativesFP(
        maxSamples: Int = BenchConfig.NEG_MAX_SAMPLES,
        shuffleSeed: Long = BenchConfig.RANDOM_SEED,
        savePreviews: Boolean = BenchConfig.SAVE_FP_PREVIEWS
    ): String = withContext(Dispatchers.IO) {
        val all = listChildren(imagesTree)
        require(all.isNotEmpty()) { "Klasör boş görünüyor." }

        val images = if (maxSamples > 0 && all.size > maxSamples)
            all.shuffled(kotlin.random.Random(shuffleSeed)).take(maxSamples)
        else all

        val n = images.size
        val prevDir = File(context.getExternalFilesDir("bench"), "fp_previews").apply { if (savePreviews) mkdirs() }

        val errLog = StringBuilder().apply { appendLine("image,error") }
        var errCount = 0

        var fpImages = 0
        var fpTotal  = 0
        val sb = StringBuilder()
        sb.appendLine("# backend=${backend.name()}")
        sb.appendLine("image,detect_ms,fp_count")

        // warm-up
        images.take(BenchConfig.NEG_WARMUP).forEach { img ->
            runCatching {
                val raw = safeLoadBitmap(img.uri) ?: return@runCatching
                val base = resizeForDetect(raw)
                detectCommon(base)
                if (base !== raw) base.recycle()
                raw.recycle()
            }
        }

        for ((idx, img) in images.withIndex()) {
            var raw: Bitmap? = null
            var base: Bitmap? = null
            try {
                val t0 = System.nanoTime()
                raw = safeLoadBitmap(img.uri)
                if (raw == null) { errCount++; errLog.appendLine("${img.name},decode_null"); continue }
                base = resizeForDetect(raw)
                val t1 = System.nanoTime()
                val preds = detectCommon(base)
                val t2 = System.nanoTime()
                val detectMs = (t2 - t1) / 1e6
                val fp = preds.size

                if (fp > 0 && savePreviews) {
                    val vis = drawBoxes(base, preds)
                    val safeName = img.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val outFile = File(prevDir, "FP_%04d_%s.jpg".format(idx + 1, safeName))
                    saveBitmapJpeg(outFile, vis)
                    vis.recycle()
                }
                if (fp > 0) fpImages++
                fpTotal += fp
                sb.appendLine("${img.name},${f2(detectMs)},$fp")
            } catch (t: Throwable) {
                errCount++; errLog.appendLine("${img.name},${t::class.simpleName}:${t.message}")
            } finally {
                if (base !== raw) runCatching { base?.recycle() }
                runCatching { raw?.recycle() }
            }
        }

        val rate = if (n > 0) fpImages.toDouble() / n else 0.0
        val (ciL, ciH) = wilsonCI(rate, n)
        val fpPerImage = if (n > 0) fpTotal.toDouble() / n else 0.0

        sb.appendLine("SUMMARY,fp_images=$fpImages/$n (${f2(rate * 100)}%), total_fp=$fpTotal")
        sb.appendLine("SUMMARY2,fp_per_image=${f2(fpPerImage)}")
        sb.appendLine("SUMMARY_CI95,${f2(ciL * 100)}%..${f2(ciH * 100)}%")

        if (errCount > 0) saveText("errors.csv", errLog.toString())
        saveText("negatives_fp_$n.csv", sb.toString())
    }
}

/* ----------------------------- Demo Activity + UI ----------------------------- */
class LabelFreeBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LabelFreeBenchmarkScreen(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelFreeBenchmarkScreen(host: ComponentActivity) {
    val ctx = host
    var imagesTree by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Hazır") }
    val scope = rememberCoroutineScope()

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, flags) }
            imagesTree = uri
        }
    }

    var selectedMode by remember { mutableStateOf(LabelFreeBenchmark.Mode.LatencyOnly) }
    var selectedBackend by remember { mutableStateOf(BackendChoice.MLKit) }

    MaterialTheme {
        Column(Modifier.padding(16.dp)) {
            Text("Label-free Face Detection Benchmark", style = MaterialTheme.typography.titleLarge)

            Row { Button(onClick = { pickDir.launch(null) }) { Text(if (imagesTree == null) "Klasör seç" else "Klasör ✓") } }

            // Backend seçimi
            Row {
                FilterChip(
                    selected = selectedBackend == BackendChoice.MLKit,
                    onClick = { selectedBackend = BackendChoice.MLKit },
                    label = { Text("ML Kit") }
                )
                FilterChip(
                    selected = selectedBackend == BackendChoice.MediaPipe,
                    onClick = { selectedBackend = BackendChoice.MediaPipe },
                    label = { Text("MediaPipe") }
                )
            }

            // Mod seçimi
            Row {
                FilterChip(
                    selected = selectedMode == LabelFreeBenchmark.Mode.LatencyOnly,
                    onClick = { selectedMode = LabelFreeBenchmark.Mode.LatencyOnly },
                    label = { Text("LatencyOnly") }
                )
                FilterChip(
                    selected = selectedMode == LabelFreeBenchmark.Mode.NegativesFP,
                    onClick = { selectedMode = LabelFreeBenchmark.Mode.NegativesFP },
                    label = { Text("NegativesFP") }
                )
                FilterChip(
                    selected = selectedMode == LabelFreeBenchmark.Mode.Consistency,
                    onClick = { selectedMode = LabelFreeBenchmark.Mode.Consistency },
                    label = { Text("Consistency") }
                )
            }

            Button(
                enabled = imagesTree != null,
                onClick = {
                    scope.launch {
                        var backend: FaceDetectorBackend? = null
                        try {
                            status = "Çalışıyor..."
                            backend = when (selectedBackend) {
                                BackendChoice.MLKit     -> MlkitBackend()
                                BackendChoice.MediaPipe -> MediaPipeBackend(ctx)
                            }
                            val bench = LabelFreeBenchmark(ctx, imagesTree!!, backend)
                            val resultPath = when (selectedMode) {
                                LabelFreeBenchmark.Mode.LatencyOnly -> bench.runLatencyOnly()
                                LabelFreeBenchmark.Mode.NegativesFP -> bench.runNegativesFP()
                                LabelFreeBenchmark.Mode.Consistency -> bench.runConsistency()
                            }
                            status = "Bitti ✓  Rapor: $resultPath\n(# backend=${backend.name()}, bench klasöründe CSV’ler)"
                        } catch (t: Throwable) {
                            status = "Hata: ${t.message}"
                        } finally {
                            runCatching { backend?.close() }
                        }
                    }
                }
            ) { Text("Başlat") }

            Text(status)
        }
    }
}
