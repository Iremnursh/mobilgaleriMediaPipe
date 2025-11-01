//com/example/mobilgaleri/util/BitmapX.kt
package com.example.mobilgaleri.util

import android.graphics.Bitmap

/** HARDWARE bitmap’leri ARGB_8888 + mutable SOFTWARE’a çevirir. */
fun Bitmap.toSoftwareArgb8888Mutable(): Bitmap {
    // Zaten uygunsa kopyalama yapma
    if (this.config == Bitmap.Config.ARGB_8888 && this.isMutable) return this
    return this.copy(Bitmap.Config.ARGB_8888, /* mutable = */ true)
}
