package com.spintracker

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class OverlayService : Service() {

    companion object {
        var mediaProjectionData: Intent? = null
        var mediaProjectionResultCode: Int = 0
        const val CHANNEL_ID = "SpinTrackerChannel"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // পরিসংখ্যান
    private var totalSpins = 0
    private var currentCount = 0
    private val bigwinIntervals = mutableListOf<Int>()
    private var lastDetectedText = ""
    private val executor = Executors.newSingleThreadExecutor()

    // UI elements
    private lateinit var tvSpinCount: TextView
    private lateinit var tvAvg: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLastEvent: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnReset: Button
    private lateinit var btnMinimize: Button
    private var isMinimized = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (mediaProjectionData != null && mediaProjectionResultCode != 0) {
            startScreenCapture()
        }
        return START_STICKY
    }

    private fun setupOverlay() {
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        tvSpinCount = overlayView.findViewById(R.id.tvSpinCount)
        tvAvg = overlayView.findViewById(R.id.tvAvg)
        tvStatus = overlayView.findViewById(R.id.tvStatus)
        tvLastEvent = overlayView.findViewById(R.id.tvLastEvent)
        btnCapture = overlayView.findViewById(R.id.btnCapture)
        btnReset = overlayView.findViewById(R.id.btnReset)
        btnMinimize = overlayView.findViewById(R.id.btnMinimize)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 10
            y = 100
        }

        // ড্র্যাগ করার সুবিধা
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - touchX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, params); true
                }
                else -> false
            }
        }

        btnCapture.setOnClickListener {
            captureAndAnalyze()
        }

        btnReset.setOnClickListener {
            resetStats()
        }

        btnMinimize.setOnClickListener {
            toggleMinimize()
        }

        // অটো-স্ক্যান প্রতি ৩ সেকেন্ডে
        val handler = Handler(Looper.getMainLooper())
        val autoScan = object : Runnable {
            override fun run() {
                if (!isMinimized) captureAndAnalyze()
                handler.postDelayed(this, 3000)
            }
        }
        handler.postDelayed(autoScan, 3000)

        windowManager.addView(overlayView, params)
        updateUI()
    }

    private fun startScreenCapture() {
        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(
            mediaProjectionResultCode,
            mediaProjectionData!!
        )

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SpinTracker",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        tvStatus.text = "🔴 অটো স্ক্যান চালু"
    }

    private fun captureAndAnalyze() {
        val image = imageReader?.acquireLatestImage() ?: run {
            // MediaProjection নেই — ম্যানুয়াল ডিটেকশন মোড
            tvStatus.text = "📷 স্ক্রিনশট অনুমতি দরকার"
            return
        }

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            analyzeWithMLKit(bitmap)
        } finally {
            image.close()
        }
    }

    private fun analyzeWithMLKit(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.uppercase()
                processDetectedText(text)
            }
            .addOnFailureListener {
                Handler(Looper.getMainLooper()).post {
                    tvStatus.text = "⚠️ OCR ত্রুটি"
                }
            }
    }

    private fun processDetectedText(text: String) {
        if (text == lastDetectedText) return
        lastDetectedText = text

        Handler(Looper.getMainLooper()).post {
            totalSpins++
            currentCount++

            val detected = when {
                text.contains("BIG WIN") || text.contains("BIGWIN") -> "BIG WIN 🏆"
                text.contains("SUPER WIN") || text.contains("SUPER") -> "SUPER ✨"
                text.contains("MEGA WIN") || text.contains("MEGA") -> "MEGA WIN 💎"
                text.contains("BONUS") -> "BONUS 🎁"
                text.contains("MULTIPLIER") || text.contains("MULTI") -> "MULTIPLIER 🔥"
                else -> null
            }

            if (detected != null) {
                if (detected.contains("BIG") || detected.contains("SUPER") || detected.contains("MEGA")) {
                    bigwinIntervals.add(currentCount)
                    currentCount = 0
                }
                tvLastEvent.text = "শেষ: $detected"
                tvStatus.text = "✅ ডিটেক্ট করা হয়েছে"
            } else {
                tvStatus.text = "👁 স্ক্যান করছে..."
            }

            updateUI()
        }
    }

    private fun updateUI() {
        tvSpinCount.text = "স্পিন: $currentCount"
        val avg = if (bigwinIntervals.isNotEmpty())
            bigwinIntervals.average().toInt() else 0
        tvAvg.text = "গড়: ${if (avg > 0) "$avg স্পিন" else "—"}"
    }

    private fun resetStats() {
        totalSpins = 0; currentCount = 0
        bigwinIntervals.clear()
        tvLastEvent.text = "শেষ: —"
        tvStatus.text = "🔄 রিসেট হয়েছে"
        updateUI()
    }

    private fun toggleMinimize() {
        isMinimized = !isMinimized
        val contentLayout = overlayView.findViewById<LinearLayout>(R.id.contentLayout)
        contentLayout.visibility = if (isMinimized) View.GONE else View.VISIBLE
        btnMinimize.text = if (isMinimized) "▼" else "▲"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Spin Tracker",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Spin Tracker চলছে")
            .setContentText("স্ক্রিন স্ক্যান করা হচ্ছে")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        executor.shutdown()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
    }
}
