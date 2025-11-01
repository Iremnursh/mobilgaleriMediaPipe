//com/example/mobilgaleri/presentation/gallery/GalleryScreen.kt
package com.example.mobilgaleri.presentation.gallery

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.rememberAsyncImagePainter
import com.example.mobilgaleri.domain.model.PersonDetails
import com.example.mobilgaleri.domain.model.Photo
import com.example.mobilgaleri.util.rememberPermissionStateCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(presenter: GalleryContract.Presenter) {
    val context = LocalContext.current
    val storagePermissionState = rememberPermissionStateCompat(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    val contactsPermissionState = rememberPermissionStateCompat(Manifest.permission.READ_CONTACTS)

    var clusters by remember { mutableStateOf<Map<Int, List<Photo>>>(emptyMap()) }
    var personDetails by remember { mutableStateOf<Map<Int, PersonDetails>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedPhotoUri by remember { mutableStateOf<String?>(null) }
    var showMatchPrompt by remember { mutableStateOf(false) }

    val viewImpl = remember {
        object : GalleryContract.View {
            override fun showLoading(isLoading: Boolean) { loading = isLoading }
            override fun showError(message: String) { error = message }
            override fun updateProgress(processed: Int, total: Int) {
                progress = processed to total
            }
            override fun hideProgress() {
                progress = null
            }

            // DEĞİŞİKLİK: Eski 'showOrUpdateCluster' ve 'displayInitialClusters' yerine bu geldi.
            override fun displayClusters(
                newClusters: Map<Int, List<Photo>>,
                newDetails: Map<Int, PersonDetails>
            ) {
                clusters = newClusters
                personDetails = newDetails
            }

            // YENİ: showSummaryToast implementasyonu.
            override fun showSummaryToast(message: String) {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
            override fun showContactsMatchPrompt() { showMatchPrompt = true }

        }
    }

    LaunchedEffect(Unit) { presenter.attach(viewImpl) }
    DisposableEffect(Unit) { onDispose { presenter.detach() } }

    LaunchedEffect(storagePermissionState.hasPermission) {
        if (storagePermissionState.hasPermission) {
            if (!contactsPermissionState.hasPermission) contactsPermissionState.launchPermissionRequest()
        } else {
            storagePermissionState.launchPermissionRequest()
        }
    }
    LaunchedEffect(storagePermissionState.hasPermission, contactsPermissionState.hasPermission) {
        if(storagePermissionState.hasPermission && contactsPermissionState.hasPermission) {
            presenter.loadAndSyncClusters()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("MobilGaleri") }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (progress != null && progress!!.second > 0) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Yeni fotoğraflar taranıyor: ${progress!!.first} / ${progress!!.second}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = progress!!.first.toFloat() / progress!!.second.toFloat(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        error != null -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Hata: $error", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        loading -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        clusters.isNotEmpty() -> {
                            ClusteredList(
                                map = clusters,
                                details = personDetails,
                                onPhotoClick = { photo -> selectedPhotoUri = photo.contentUri }
                            )
                        }
                        else -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("Henüz gruplanmış kişi bulunamadı.")
                            }
                        }
                    }
                }
            }
        }
        if (selectedPhotoUri != null) {
            FullScreenPhotoViewer(
                photoUri = selectedPhotoUri!!,
                onDismiss = { selectedPhotoUri = null }
            )
        }
    }
}

@Composable
private fun ClusteredList(
    map: Map<Int, List<Photo>>,
    details: Map<Int, PersonDetails>,
    onPhotoClick: (Photo) -> Unit
) {
    if (map.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Henüz gruplanmış kişi bulunamadı.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        map.toSortedMap().forEach { (cid, list) ->
            if (cid >= 0) {
                val detail = details[cid]

                item(key = "header_$cid") {
                    Column {
                        Text(
                            text = "${detail?.displayName ?: "Kişi ${cid + 1}"} (${list.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        detail?.matchInfo?.let { info ->
                            Text(
                                text = info,
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                item(key = "row_$cid") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.id }) { photo ->
                            Card(modifier = Modifier.clickable { onPhotoClick(photo) }) {
                                Image(
                                    painter = rememberAsyncImagePainter(photo.contentUri),
                                    contentDescription = null,
                                    modifier = Modifier.size(120.dp),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullScreenPhotoViewer(photoUri: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)).clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = rememberAsyncImagePainter(model = photoUri),
                contentDescription = "Tam Ekran Fotoğraf",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )
        }
    }
}