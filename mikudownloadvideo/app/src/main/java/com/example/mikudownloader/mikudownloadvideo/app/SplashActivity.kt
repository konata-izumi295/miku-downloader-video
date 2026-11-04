
package com.example.mikudownloader.mikudownloadvideo.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity

class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private val openMain = Runnable {
        if (!isFinishing && !isDestroyed) {
            startActivity(
                Intent(this, MainActivity::class.java)
            )
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        window.decorView.setBackgroundColor(Color.BLACK)

        val image = ImageView(this).apply {
            setImageResource(R.drawable.miku_splash)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Miku Downloader"
        }

        setContentView(image)

        handler.postDelayed(openMain, 1400L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openMain)
        super.onDestroy()
    }
}
