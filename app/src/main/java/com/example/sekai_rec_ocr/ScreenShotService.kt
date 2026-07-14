package com.example.sekai_rec_ocr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import android.app.usage.UsageStatsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ScreenShotService: バックグラウンドでスクリーンショットの保存を監視し、
 * 指定アプリがフォアグラウンドの際に自動で日本語OCRを実行するフォアグラウンドサービス。
 */
class ScreenShotService : Service() {

    companion object {
        private const val TAG = "ScreenShotService"
        private const val NOTIFICATION_CHANNEL_ID = "screenshot_channel"
        private const val SERVICE_NOTIFICATION_ID = 1
        private const val TARGET_APP_PACKAGE = "com.sega.pjsekai" // ターゲットアプリ（プロセカ）のパッケージ名
        private const val DUPLICATE_PREVENTION_TIME_MS = 3000L // 同一スクショイベントの重複処理を防ぐしきい値（3秒）
        private const val FILE_WRITE_WAIT_TIME_MS = 500L // 画像ファイルの書き込み完了待ち時間
    }

    private lateinit var contentObserver: ContentObserver
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastProcessedUri: String? = null
    private var lastProcessedTime: Long = 0

    // HTTP POST送信用のスレッドプール
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // クエリ結果と作成日時をまとめるための内部クラス
    private data class ScreenshotInfo(val uri: Uri, val dateTakenMillis: Long)

    // OCRの各行の座標情報を保持するための内部クラス
    private data class OcrLine(val text: String, val rect: Rect) {
        val yCenter: Int = rect.centerY()
        val height: Int = rect.height()
        val xLeft: Int = rect.left
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: サービスを開始します")
        
        // 通知チャネルの作成
        createNotificationChannel()

        // サービスの常駐に必要なフォアグラウンド通知を作成
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("スクショ監視中")
            .setContentText("バックグラウンドでスクリーンショットを監視しています")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        // Android 10 (Q) 以降は dataSync タイプを指定してフォアグラウンド実行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }

        // ギャラリーへの新規画像追加（スクリーンショット）を検知するためのContentObserverを設定
        contentObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                Log.d(TAG, "ContentObserver.onChange: selfChange=$selfChange, uri=$uri")
                // onChangeのuri引数がnullやディレクトリ名の場合があるため、内部で最新画像を再スキャンする
                triggerImageScan()
            }
        }

        // 外部ストレージの画像ディレクトリを監視対象として登録
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        Log.d(TAG, "ContentObserverを登録しました: ${MediaStore.Images.Media.EXTERNAL_CONTENT_URI}")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: サービスを停止します")
        super.onDestroy()
        // メモリリーク防止のため、登録したContentObserverを解除
        contentResolver.unregisterContentObserver(contentObserver)
        // 遅延実行待ちのタスクがあればすべてキャンセル
        mainHandler.removeCallbacksAndMessages(null)
    }

    /**
     * 画像スキャン処理の遅延実行トリガー
     */
    private fun triggerImageScan() {
        // OSによる画像ファイルの保存書き込みが完全に完了するまで少し待ってから処理を実行
        mainHandler.removeCallbacksAndMessages(null) // 既存の待機タスクがあればキャンセルして最新のみ実行
        mainHandler.postDelayed({
            processLatestScreenshot()
        }, FILE_WRITE_WAIT_TIME_MS)
    }

    /**
     * 最新のスクリーンショット画像を取得し、条件を満たしていればOCRを実行する
     */
    private fun processLatestScreenshot() {
        val screenshotInfo = getRecentScreenshotInfo()
        if (screenshotInfo == null) {
            Log.d(TAG, "有効な最近のスクリーンショットが見つかりませんでした。処理をスキップします。")
            return
        }

        val uri = screenshotInfo.uri
        val currentUriString = uri.toString()
        val currentTime = System.currentTimeMillis()

        // 1. すでに処理済みのURIと同じ場合は、時間に関わらずスキップ（ContentObserverの多重発火対策）
        if (currentUriString == lastProcessedUri) {
            Log.d(TAG, "同一URIの処理スキップ: $currentUriString")
            return
        }

        // 2. 前回の処理から3秒未満の場合は、別の画像であっても同一スクショイベントの連動ファイルとみなしてスキップ
        if ((currentTime - lastProcessedTime) < DUPLICATE_PREVENTION_TIME_MS) {
            Log.d(TAG, "短時間での連続処理防止のためスキップ: 前回の処理から${currentTime - lastProcessedTime}ms")
            return
        }

        // タイムスタンプとURIを更新して処理を続行
        lastProcessedUri = currentUriString
        lastProcessedTime = currentTime

        Log.d(TAG, "スクリーンショット処理開始: $currentUriString")

        try {
            // 3. 現在、ターゲットとなるアプリが最前面（フォアグラウンド）で動いているかチェック
            if (!isTargetAppForeground()) {
                Log.d(TAG, "指定アプリ以外（非ターゲット）でのスクショのため、処理をスキップしました")
                return
            }

            // 4. 画像のロードおよびクロップ処理の実行
            val contentResolver = this.contentResolver
            val sourceBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }

            if (sourceBitmap == null) {
                Log.e(TAG, "Bitmapのロードに失敗しました")
                return
            }

            val width = sourceBitmap.width
            val height = sourceBitmap.height
            Log.d(TAG, "画像サイズ: width=$width, height=$height")

            // クロップ1: 左上で縦幅/4 横幅/2
            val crop1W = (width / 2.0).toInt()
            val crop1H = (height / 4.0).toInt()
            val bitmap1 = cropBitmap(sourceBitmap, 0, 0, crop1W, crop1H)

            // アスペクト比（長辺/短辺）から最適な横幅の除数を決定（21:9などの超縦長タイプは2.8、それ以外は3.0）
            val aspectRatio = max(width, height).toFloat() / min(width, height)
            val divisorX = if (aspectRatio > 2.25f) 2.8 else 3.0

            // クロップ2: 左下で縦幅/2 横幅/divisorX
            val crop2W = (width / divisorX).toInt()
            val crop2H = (height / 2.0).toInt()
            val crop2Y = height - crop2H
            val bitmap2 = cropBitmap(sourceBitmap, 0, crop2Y, crop2W, crop2H)

            val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            val tasks = mutableListOf<Task<Text>>()

            if (bitmap1 != null) {
                Log.d(TAG, "クロップ1のOCRタスクを追加: size=${bitmap1.width}x${bitmap1.height}")
                tasks.add(recognizer.process(InputImage.fromBitmap(bitmap1, 0)))
            }
            if (bitmap2 != null) {
                Log.d(TAG, "クロップ2のOCRタスクを追加: size=${bitmap2.width}x${bitmap2.height}")
                tasks.add(recognizer.process(InputImage.fromBitmap(bitmap2, 0)))
            }

            if (tasks.isEmpty()) {
                Log.e(TAG, "クロップ画像がどちらも作成できませんでした")
                sourceBitmap.recycle()
                return
            }

            // 5. OCRタスクの完了を待機して結合
            Log.d(TAG, "並列OCR処理を開始します")
            Tasks.whenAllComplete(tasks).addOnCompleteListener {
                // タスク完了後にBitmapリソースを即座に解放してメモリ節約
                sourceBitmap.recycle()
                bitmap1?.recycle()
                bitmap2?.recycle()
                Log.d(TAG, "Bitmapリソースを解放しました")

                // 各タスク結果の取得
                val resultText1 = if (bitmap1 != null && tasks[0].isSuccessful) {
                    reconstructTextByCoordinates(tasks[0].result)
                } else ""

                val resultText2 = if (bitmap2 != null) {
                    val task2 = if (bitmap1 != null) tasks[1] else tasks[0]
                    if (task2.isSuccessful) {
                        reconstructTextByCoordinates(task2.result)
                    } else ""
                } else ""

                Log.d(TAG, "OCR結果 - 左上: [${resultText1.replace("\n", " ")}], 左下: [${resultText2.replace("\n", " ")}]")

                // OCR結果の結合
                val combinedText = StringBuilder()
                if (resultText1.isNotBlank()) {
                    combinedText.append("【左上エリア】\n").append(resultText1).append("\n\n")
                }
                if (resultText2.isNotBlank()) {
                    combinedText.append("【左下エリア】\n").append(resultText2)
                }

                val finalResult = combinedText.toString().trim()
                if (finalResult.isNotEmpty()) {
                    // APIトークンを取得して送信
                    val sharedPrefs = getSharedPreferences("sekai_rec_ocr_prefs", MODE_PRIVATE)
                    val token = sharedPrefs.getString("ingest_token", "") ?: ""
                    
                    if (token.isEmpty()) {
                        Log.d(TAG, "APIトークンが設定されていません。送信をスキップします。")
                        showOcrNotification(finalResult, "⚠️ トークン未設定のため送信スキップ")
                        return@addOnCompleteListener
                    }
                    
                    val isoTime = formatEpochToIso8601(screenshotInfo.dateTakenMillis)
                    
                    postOcrText(token, finalResult, isoTime) { statusCode, responseBody, error ->
                        val statusMsg = when {
                            error != null -> {
                                mainHandler.post {
                                    Toast.makeText(applicationContext, "失敗: 通信エラー", Toast.LENGTH_LONG).show()
                                }
                                "通信エラー"
                            }
                            statusCode == 200 -> {
                                val toastMsg = parseSuccessToastMessage(responseBody)
                                mainHandler.post {
                                    Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_LONG).show()
                                }
                                "保存完了"
                            }
                            else -> {
                                val errDetail = parseErrorDetail(responseBody) ?: "HTTP $statusCode"
                                mainHandler.post {
                                    Toast.makeText(applicationContext, "失敗: $errDetail", Toast.LENGTH_LONG).show()
                                }
                                "送信エラー: $errDetail"
                            }
                        }
                        showOcrNotification(finalResult, statusMsg)
                    }
                } else {
                    Log.d(TAG, "OCRで有効な文字が検出されませんでした")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "画像処理中に例外が発生しました: ${e.message}", e)
        }
    }

    /**
     * OCRの出力座標（バウンディングボックス）に基づき、左右に大きく離れてしまっているテキスト同士を
     * 同じ高さ（Y軸座標）の行として再グループ化・ソートして結合する。
     */
    private fun reconstructTextByCoordinates(visionText: Text): String {
        val allLines = mutableListOf<OcrLine>()

        // 全てのテキストブロックから個々のLineを抽出
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val rect = line.boundingBox
                if (rect != null) {
                    allLines.add(OcrLine(line.text, rect))
                }
            }
        }

        if (allLines.isEmpty()) {
            return visionText.text
        }

        // Y軸の中心座標順でソート
        allLines.sortBy { it.yCenter }

        // 行グループの作成
        val groupedRows = mutableListOf<MutableList<OcrLine>>()

        for (line in allLines) {
            var bestRow: MutableList<OcrLine>? = null
            var minDiff = Int.MAX_VALUE

            for (row in groupedRows) {
                val representative = row[0]
                val yDiff = abs(line.yCenter - representative.yCenter)
                // 両テキストの最大高さの 60% を許容誤差（しきい値）とする
                val threshold = (max(line.height, representative.height) * 0.6).toInt()

                if (yDiff <= threshold && yDiff < minDiff) {
                    minDiff = yDiff
                    bestRow = row
                }
            }

            if (bestRow != null) {
                bestRow.add(line)
            } else {
                groupedRows.add(mutableListOf(line))
            }
        }

        // 行グループ全体の平均Y座標を基準に、垂直方向にソート
        groupedRows.sortBy { row -> row.map { it.yCenter }.average() }

        val sortedRowsText = mutableListOf<String>()
        for (row in groupedRows) {
            // 同一行の中でX座標（左端の開始位置）順に水平ソート
            row.sortBy { it.xLeft }
            // 半角スペースで結合
            val rowText = row.joinToString(" ") { it.text }
            sortedRowsText.add(rowText)
        }

        return sortedRowsText.joinToString("\n")
    }

    /**
     * MediaStoreから最も新しく追加されたスクリーンショットの情報（URIと作成日時のミリ秒）を取得する。
     * 存在しない、または最近（30秒以内）追加されたものでない場合はnullを返す。
     */
    private fun getRecentScreenshotInfo(): ScreenshotInfo? {
        val projection = arrayOf(
            MediaStore.Images.ImageColumns._ID,
            MediaStore.Images.ImageColumns.DISPLAY_NAME,
            MediaStore.Images.ImageColumns.DATE_ADDED,
            MediaStore.Images.ImageColumns.DATE_TAKEN
        )
        val sortOrder = "${MediaStore.Images.ImageColumns.DATE_ADDED} DESC"

        return try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DISPLAY_NAME)
                    val dateAddedIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_ADDED)
                    val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.ImageColumns.DATE_TAKEN)

                    val id = cursor.getLong(idIndex)
                    val fileName = cursor.getString(nameIndex) ?: ""
                    val dateAddedSeconds = cursor.getLong(dateAddedIndex)
                    val dateTakenMillis = cursor.getLong(dateTakenIndex)

                    val currentTimeSeconds = System.currentTimeMillis() / 1000

                    Log.d(TAG, "最新画像クエリ結果: 名前=$fileName, DATE_ADDED=$dateAddedSeconds, DATE_TAKEN=$dateTakenMillis, 現在時間=$currentTimeSeconds")

                    // 30秒以内に追加され、ファイル名に「Screenshot」が含まれる場合のみ採用
                    if (fileName.contains("Screenshot", ignoreCase = true) &&
                        abs(currentTimeSeconds - dateAddedSeconds) < 30) {
                        
                        val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                        // DATE_TAKENがあればミリ秒をそのまま使用、なければDATE_ADDED(秒)をミリ秒にして使用
                        val resolvedTimeMillis = if (dateTakenMillis > 0) dateTakenMillis else (dateAddedSeconds * 1000)
                        
                        ScreenshotInfo(uri, resolvedTimeMillis)
                    } else {
                        Log.d(TAG, "最新画像がスクリーンショットではないか、追加から30秒以上経過しています")
                        null
                    }
                } else {
                    Log.d(TAG, "MediaStoreに画像が見つかりません")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "最新画像のクエリ中にエラーが発生しました: ${e.message}")
            null
        }
    }

    /**
     * 指定された矩形範囲でBitmapをクロップする安全なヘルパー関数
     */
    @Suppress("SameParameterValue")
    private fun cropBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        
        // 座標とサイズが元画像の範囲内に収まるよう調整（境界値エラーの防止）
        val safeX = x.coerceIn(0, source.width - 1)
        val safeY = y.coerceIn(0, source.height - 1)
        val safeW = width.coerceAtMost(source.width - safeX)
        val safeH = height.coerceAtMost(source.height - safeY)

        if (safeW <= 0 || safeH <= 0) return null
        return try {
            Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
        } catch (e: Exception) {
            Log.e(TAG, "Bitmapのクロップ中にエラーが発生しました: ${e.message}", e)
            null
        }
    }

    /**
     * 現在フォアグラウンドにいるアプリが指定のターゲットアプリ（プロセカ）か判定する
     */
    private fun isTargetAppForeground(): Boolean {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()

        // 直近10秒間のアプリ使用状況データを取得
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            time - 10000,
            time
        )

        if (stats != null && stats.isNotEmpty()) {
            // 最後に使用された時間が最も新しい（＝現在フォアグラウンドである）アプリを特定
            val latestApp = stats.maxByOrNull { it.lastTimeUsed }
            val currentForegroundPackage = latestApp?.packageName

            Log.d(TAG, "現在フォアグラウンドのアプリ: $currentForegroundPackage")
            return currentForegroundPackage == TARGET_APP_PACKAGE
        }
        Log.d(TAG, "フォアグラウンドのアプリ使用状況データを取得できませんでした")
        return false
    }

    /**
     * ミリ秒エポックタイムをISO 8601形式のタイムスタンプ文字列に変換
     */
    private fun formatEpochToIso8601(epochMillis: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return sdf.format(Date(epochMillis))
    }

    /**
     * インジェストAPIへのOCR結果POST処理
     */
    private fun postOcrText(
        token: String,
        fullText: String,
        takenAtIso: String,
        onResult: (statusCode: Int, responseBody: String?, error: Throwable?) -> Unit
    ) {
        networkExecutor.execute {
            var connection: java.net.HttpURLConnection? = null
            try {
                val url = java.net.URL("https://sekai-rec.shf.blue/api/ingest/ocr-text")
                connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.setRequestProperty("X-Ingest-Token", token)
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val escapedText = escapeJson(fullText)
                val jsonBody = "{\"full_text\":\"$escapedText\",\"taken_at\":\"$takenAtIso\"}"

                connection.outputStream.use { os ->
                    java.io.OutputStreamWriter(os, "UTF-8").use { writer ->
                        writer.write(jsonBody)
                        writer.flush()
                    }
                }

                val statusCode = connection.responseCode
                val responseBody = if (statusCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }

                onResult(statusCode, responseBody, null)
            } catch (e: Exception) {
                onResult(-1, null, e)
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * JSON用に特殊文字をエスケープ
     */
    private fun escapeJson(value: String): String {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * レスポンスボディからエラーメッセージキーワードを切り出す
     */
    private fun parseErrorDetail(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        val errorKeys = listOf(
            "empty_full_text", "missing_token", "invalid_token", 
            "song_not_identified", "missing_song_id", "judgments_invalid", 
            "incomplete_judgments", "invalid_point", "bad_song_id", 
            "internal", "postprocess_failed", "postprocess_error", 
            "node_unavailable", "postprocess_timeout"
        )
        for (key in errorKeys) {
            if (responseBody.contains(key)) {
                return key
            }
        }
        return responseBody.take(40).trim()
    }

    /**
     * レスポンスボディから曲情報と判定結果を抽出してトーストメッセージを作成
     */
    private fun parseSuccessToastMessage(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) return "成功 (詳細不明)"
        
        val titleRegex = "\"songTitle\"\\s*:\\s*\"([^\"]+)\"".toRegex(RegexOption.IGNORE_CASE)
        val diffRegex = "\"difficulty\"\\s*:\\s*\"([^\"]+)\"".toRegex(RegexOption.IGNORE_CASE)
        val greatRegex = "\"great\"\\s*:\\s*\"?(\\d+)\"?".toRegex(RegexOption.IGNORE_CASE)
        val goodRegex = "\"good\"\\s*:\\s*\"?(\\d+)\"?".toRegex(RegexOption.IGNORE_CASE)
        val badRegex = "\"bad\"\\s*:\\s*\"?(\\d+)\"?".toRegex(RegexOption.IGNORE_CASE)
        val missRegex = "\"miss\"\\s*:\\s*\"?(\\d+)\"?".toRegex(RegexOption.IGNORE_CASE)
        
        val songTitle = titleRegex.find(responseBody)?.groups?.get(1)?.value ?: "曲名不明"
        val difficulty = diffRegex.find(responseBody)?.groups?.get(1)?.value
        
        val great = greatRegex.find(responseBody)?.groups?.get(1)?.value ?: "-"
        val good = goodRegex.find(responseBody)?.groups?.get(1)?.value ?: "-"
        val bad = badRegex.find(responseBody)?.groups?.get(1)?.value ?: "-"
        val miss = missRegex.find(responseBody)?.groups?.get(1)?.value ?: "-"
        
        return if (difficulty != null) {
            "成功: $songTitle $difficulty($great-$good-$bad-$miss)"
        } else {
            "成功: $songTitle ($great-$good-$bad-$miss)"
        }
    }

    /**
     * OCRで認識された文字列を通知領域に表示する
     */
    private fun showOcrNotification(text: String, apiStatus: String? = null) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        // 改行をスペースに変換したプレビュー用テキストを作成
        val previewText = text.replace("\n", " ")
        val contentText = if (apiStatus != null) "[$apiStatus] $previewText" else previewText

        val ocrNotification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("【OCR文字認識結果】")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (apiStatus != null) "【API送信ステータス: $apiStatus】\n\n$text" else text
            )) // 展開時にAPIステータスも含めて表示
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setAutoCancel(true) // タップ時に自動で通知を消去
            .build()

        // 通知ごとに一意のID（タイムスタンプ）を使用して発行
        notificationManager?.notify(System.currentTimeMillis().toInt(), ocrNotification)
        Log.d(TAG, "OCR結果を通知しました")
    }

    /**
     * 通知チャネルの作成 (Android 8.0 O 以降で必須)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "スクショ監視サービス",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}