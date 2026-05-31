package com.spintracker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_OVERLAY = 1001
        const val REQUEST_SCREENSHOT = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        btnStart.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
                tvStatus.text = "অনুমতি দিন, তারপর আবার চাপুন"
            } else {
                startOverlayService()
                tvStatus.text = "✅ ট্র্যাকার চলছে..."
                btnStart.isEnabled = false
                btnStop.isEnabled = true
            }
        }

        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            tvStatus.text = "⏹ ট্র্যাকার বন্ধ"
            btnStart.isEnabled = true
            btnStop.isEnabled = false
        }

        btnStop.isEnabled = false
    }

    private fun startOverlayService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                Settings.canDrawOverlays(this)) {
                startOverlayService()
                findViewById<TextView>(R.id.tvStatus).text = "✅ ট্র্যাকার চলছে..."
            }
        }
        if (requestCode == REQUEST_SCREENSHOT && resultCode == Activity.RESULT_OK && data != null) {
            OverlayService.mediaProjectionData = data
            OverlayService.mediaProjectionResultCode = resultCode
        }
    }
}
