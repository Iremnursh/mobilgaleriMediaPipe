package com.example.mobilgaleri.presentation.onboarding

import android.graphics.Color
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.mobilgaleri.R
import com.example.mobilgaleri.util.Permissions
import com.example.mobilgaleri.util.Prefs

class OnboardingActivity : AppCompatActivity() {

    private lateinit var pager: ViewPager2

    // Foto izin sonucu → 2. sayfaya geç
    private val requestPhotos =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // sonucu burada kontrol etmiyoruz; 2. sayfaya geçiyoruz
            pager.currentItem = 1
        }

    // Rehber izin sonucu → kontrol edip bitir
    private val requestContacts =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            finishIfAllGrantedOrStay()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        pager = findViewById(R.id.pager)
        pager.adapter = OnboardingAdapter(this)

        // Başlangıç sayfası: foto izni yoksa 0, varsa 1
        pager.setCurrentItem(
            if (!Permissions.hasPhotosPermission(this)) 0 else 1,
            false
        )
        pager.isUserInputEnabled = false
        window.statusBarColor = Color.parseColor("#54B1FF")
        window.navigationBarColor = Color.parseColor("#54B1FF")
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
    }

    /** 1. sayfadaki "İleri" → foto iznini iste */
    fun onPhotosIntroNext() {
        requestPhotos.launch(Permissions.photosPermission())
    }

    /** 2. sayfadaki "İleri" → rehber iznini iste */
    fun onContactsIntroNext() {
        requestContacts.launch(android.Manifest.permission.READ_CONTACTS)
    }

    /** İki izin birden varsa onboarding’i kapat ve bir daha gösterme */
    fun finishIfAllGrantedOrStay() {
        val ok = Permissions.hasPhotosPermission(this) &&
                Permissions.hasContactsPermission(this)
        if (ok) {
            Prefs.setOnboardingDone(this, true)
            finish()
        } else {
            Prefs.setOnboardingDone(this, false) // bir sonraki açılışta tekrar
        }
    }
}
