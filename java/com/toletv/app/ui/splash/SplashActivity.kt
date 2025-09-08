package com.toletv.app.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.toletv.app.R
import com.toletv.app.ui.main.MainActivity

class SplashActivity : AppCompatActivity() {

    private lateinit var splashLogo: ImageView
    private val splashDuration = 2000L // 3 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Hide system UI for fullscreen splash
        hideSystemUI()

        initViews()
        loadSplashAnimation()
        startMainActivityWithDelay()
    }

    private fun initViews() {
        splashLogo = findViewById(R.id.ivSplashLogo)
    }

    private fun loadSplashAnimation() {
        // Load animated vector drawable
        splashLogo.setImageResource(R.drawable.animated_splash_logo)

        // Alternative: If you have a GIF file, uncomment below and comment above
        /*
        Glide.with(this)
            .asGif()
            .load(R.drawable.your_gif_file) // Replace with your actual GIF file
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .into(splashLogo)
        */
    }

    private fun startMainActivityWithDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            startMainActivity()
        }, splashDuration)
    }

    private fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()

        // Add smooth transition
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )
    }

    override fun onBackPressed() {
        // Disable back button during splash
        // Do nothing
    }
}
