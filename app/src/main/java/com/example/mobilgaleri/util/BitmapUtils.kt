// util/BitmapUtils.kt
package com.example.mobilgaleri.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log // Log import'unu ekleyelim
import java.io.InputStream

object BitmapUtils {

    private const val TAG = "BitmapUtils" // Loglama için TAG

    /**
     * Verilen content URI'dan Bitmap'i çözer. API seviyesine göre uyumlu metod kullanır.
     * Hata durumunda null döner.
     * @param contentUri Çözülecek resmin URI'si (String formatında).
     * @param cr ContentResolver nesnesi.
     * @return Başarılı olursa Bitmap, hata olursa null.
     */
    fun decodeBitmapCompat(contentUri: String, cr: ContentResolver): Bitmap? {
        val uri = Uri.parse(contentUri)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+ için ImageDecoder (daha modern ve güvenli)
                val source = ImageDecoder.createSource(cr, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    // Mümkünse donanım yerine yazılım allocator kullanalım (ML modelleri için genellikle daha iyi)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    // isMutableRequired = true // Genellikle gerekmez, sonradan değiştirilecekse kopyası oluşturulabilir.
                }
            } else {
                // API 28 öncesi için BitmapFactory (daha eski yöntem)
                @Suppress("DEPRECATION") // Eski API uyarılarını bastır
                val opts = BitmapFactory.Options().apply {
                    // Mümkün olan en iyi renk formatını tercih et
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    // inMutable = true // Doğrudan mutable yapmak yerine, gerekirse sonradan kopyalamak daha güvenli olabilir.
                }
                cr.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, opts)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding bitmap: $uri", e)
            null // Hata durumunda null dön
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError decoding bitmap: $uri", oom)
            null // Bellek yetmezse null dön
        }
    }

    /**
     * Bir InputStream'den belirtilen dikdörtgen (Rect) bölgesini Bitmap olarak çözer.
     * Özellikle büyük resimlerden sadece bir bölümü yüklemek için verimlidir.
     * Hata durumunda veya geçersiz Rect durumunda null döner.
     * @param inputStream Resim verisinin akışı. Akışın bu fonksiyondan sonra kapatılması gerekebilir.
     * @param rect Çözülecek bölgenin koordinatları.
     * @return Başarılı olursa Bitmap, hata olursa veya bölge geçersizse null.
     */
    fun decodeRegion(inputStream: InputStream, rect: Rect): Bitmap? {
        // Geçersiz (negatif veya sıfır boyutlu) Rect kontrolü
        if (rect.width() <= 0 || rect.height() <= 0) {
            Log.w(TAG, "Attempted to decode region with invalid Rect: $rect")
            return null
        }

        var decoder: BitmapRegionDecoder? = null
        return try {
            decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+
                BitmapRegionDecoder.newInstance(inputStream)
            } else {
                // API 31 öncesi
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(inputStream, false)
            }

            // Çözme seçenekleri (isteğe bağlı, örn. inSampleSize eklenebilir)
            val options = BitmapFactory.Options().apply{
                inPreferredConfig = Bitmap.Config.ARGB_8888 // İyi kalite isteyelim
            }

            // Belirtilen bölgeyi çöz
            decoder?.decodeRegion(rect, options)

        } catch (e: Exception) {
            Log.e(TAG, "Error decoding region: Rect=$rect", e)
            null // Hata durumunda null dön
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError decoding region: Rect=$rect", oom)
            null // Bellek yetmezse null dön
        }
        finally {
            // Decoder'ı her zaman serbest bırakalım (null değilse)
            try {
                decoder?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling BitmapRegionDecoder", e)
            }
        }
    }
}