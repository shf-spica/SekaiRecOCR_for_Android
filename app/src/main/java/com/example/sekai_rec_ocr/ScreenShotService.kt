package com.example.sekai_rec_ocr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

class ScreenShotService : Service() {

    private lateinit var contentObserver: ContentObserver

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, "screenshot_channel")
            .setContentTitle("スクショ監視中")
            .setContentText("バックグラウンドでスクリーンショットを監視しています")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                // 機種対策：uriがnullで来ることがあるため、安全に処理
                uri?.let { processLatestImage(it) }
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
    }

    private fun processLatestImage(uri: Uri) {
        // 画像生成のわずかなタイムラグで落ちないよう、少しだけ（0.5秒）処理を遅らせる安全策
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val inputImage = InputImage.fromFilePath(this, uri)
                val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val extractedText = visionText.text
                        Log.d("OCR_RESULT", "認識した文字: $extractedText")

                        if (extractedText.isNotEmpty()) {
                            Toast.makeText(applicationContext, "【OCR成功】\n$extractedText", Toast.LENGTH_LONG).show()
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("OCR_ERROR", "文字認識失敗", e)
                    }
            } catch (e: Exception) {
                // ここでエラーをキャッチすればアプリは強制終了（停止）しなくなります
                Log.e("SERVICE_ERROR", "画像読み込みスキップ: ${e.message}")
            }
        }, 500)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screenshot_channel",
                "スクショ監視サービス",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}