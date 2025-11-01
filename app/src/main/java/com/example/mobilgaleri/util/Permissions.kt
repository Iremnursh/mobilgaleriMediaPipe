// com/example/mobilgaleri/util/Permissions.kt
package com.example.mobilgaleri.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Manifest izinleri ve yardımcı kontroller.
 *
 * Kullanım (View/Activity/VM):
 *  - Permissions.hasPhotosPermission(context)
 *  - Permissions.photosPermission()
 *  - Permissions.hasContactsPermission(context)
 *
 * Kullanım (Compose):
 *  - val p = rememberPermissionStateCompat(Permissions.photosPermission())
 *  - if (!p.hasPermission) p.launchPermissionRequest()
 */
object Permissions {

    /** Android 13+ (API 33) için READ_MEDIA_IMAGES, altı için READ_EXTERNAL_STORAGE kullanır. */
    fun hasPhotosPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** Dinamik isteme sırasında kullanacağın tekil izin adı. */
    fun photosPermission(): String =
        if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_IMAGES
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

    /** Rehber okuma izni. */
    fun hasContactsPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
}

/** Compose tarafında izin durumunu ve launcher'ı birlikte tutan basit model. */
class PermissionState(
    val hasPermission: Boolean,
    val launchPermissionRequest: () -> Unit
)

/**
 * Tek bir runtime izni için (örn. READ_MEDIA_IMAGES ya da READ_CONTACTS)
 * hatırlanabilir izin durumu ve launcher döner.
 *
 * Örnek:
 * val photosPerm = rememberPermissionStateCompat(Permissions.photosPermission())
 * if (!photosPerm.hasPermission) { photosPerm.launchPermissionRequest() }
 */
@Composable
fun rememberPermissionStateCompat(permission: String): PermissionState {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher: ManagedActivityResultLauncher<String, Boolean> =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            granted = isGranted
        }

    return remember(granted) {
        PermissionState(
            hasPermission = granted,
            launchPermissionRequest = { launcher.launch(permission) }
        )
    }
}
