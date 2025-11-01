//com/example/mobilgaleri/MobilGaleriApp.kt
package com.example.mobilgaleri

import android.app.Application
import org.opencv.android.OpenCVLoader

class MobilGaleriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // OpenCV kütüphanesini uygulama başlangıcında yükle
        if (!OpenCVLoader.initDebug()) {
            println("OpenCV yüklemesi başarısız oldu!")
        } else {
            println("OpenCV başarıyla yüklendi.")
        }
    }
}