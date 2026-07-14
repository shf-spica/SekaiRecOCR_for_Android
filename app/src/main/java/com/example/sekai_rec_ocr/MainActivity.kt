package com.example.sekai_rec_ocr

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import android.app.AppOpsManager
import android.provider.Settings
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * MainActivity: アプリ起動時のパーミッション管理およびサービス制御を行うアクティビティ。
 * また、APIトークンの保存（確定/解除機能付き）、手動画像選択（複数選択・順次処理・開閉式結果カード表示）を提供します。
 */
@SuppressLint("SetTextI18n") // 動的生成UIでのハードコード文字列に関するLint警告を抑制
class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // Android OS of version dynamically sets permissions
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,  // スクリーンショット（画像）の読み取り権限
            Manifest.permission.POST_NOTIFICATIONS  // 通知の表示権限（Android 13以降で必須）
        )
    } else {
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE // ストレージの読み取り権限（Android 12以前）
        )
    }

    private lateinit var statusTextView: TextView
    private lateinit var actionButton: Button
    private lateinit var pickImageButton: Button
    private lateinit var resultContainer: LinearLayout // 結果表示エリアをカード格納用コンテナに変更
    private lateinit var descTextView: TextView

    // HTTP POST送信用のスレッドプール
    private val networkExecutor = Executors.newSingleThreadExecutor()

    // 複数画像選択用のランチャー登録（手動OCR用）
    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            processPickedImages(uris)
        }
    }

    // OCRの各行の座標情報を保持するための内部クラス
    private data class OcrLine(val text: String, val rect: Rect) {
        val yCenter: Int = rect.centerY()
        val height: Int = rect.height()
        val xLeft: Int = rect.left
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. ルートとなるScrollViewの作成
        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor("#121212".toColorInt()) // 深いダークテーマ背景
            isFillViewport = true
        }

        // 2. メインの垂直レイアウト
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 64, 48, 64)
        }

        // 3. アプリタイトル
        val titleView = TextView(this).apply {
            text = "Sekai Rec OCR"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }
        layout.addView(titleView)

        // 4. ステータス表示カード (角丸&ボーダー付き)
        val statusCardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor("#1E1E1E".toColorInt())
            cornerRadius = 24f
            setStroke(2, "#2D2D2D".toColorInt())
        }
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = statusCardBg
            setPadding(48, 48, 48, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 32
            }
        }
        statusTextView = TextView(this).apply {
            text = "ステータス: 取得中..."
            textSize = 18f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
        }
        statusCard.addView(statusTextView)
        layout.addView(statusCard)

        // 4.5 APIトークン設定カード (確定/削除付きデザイン)
        val tokenCardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor("#1E1E1E".toColorInt())
            cornerRadius = 24f
            setStroke(2, "#2D2D2D".toColorInt())
        }
        val tokenCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = tokenCardBg
            setPadding(48, 48, 48, 48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 32
            }
        }
        val tokenLabel = TextView(this).apply {
            text = "API トークン設定"
            textSize = 12f
            setTextColor("#888888".toColorInt())
            setPadding(0, 0, 0, 16)
        }
        
        val tokenEditText = EditText(this).apply {
            hint = "トークンを入力してください"
            textSize = 15f
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        val tokenButton = Button(this).apply {
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
            }
        }

        val sharedPrefs = getSharedPreferences("sekai_rec_ocr_prefs", MODE_PRIVATE)

        // トークンUIの状態更新関数
        fun updateTokenUi() {
            val token = sharedPrefs.getString("ingest_token", "") ?: ""
            if (token.isNotEmpty()) {
                tokenEditText.setText(token)
                tokenEditText.isEnabled = false
                tokenEditText.setTextColor("#888888".toColorInt()) // グレー文字
                tokenEditText.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor("#181818".toColorInt()) // 暗い背景
                    cornerRadius = 12f
                    setStroke(2, "#262626".toColorInt())
                }
                tokenEditText.setPadding(24, 16, 24, 16)
                tokenButton.text = "トークンを削除 🗑️"
                tokenButton.backgroundTintList = android.content.res.ColorStateList.valueOf("#E57373".toColorInt()) // 薄赤
            } else {
                tokenEditText.setText("")
                tokenEditText.isEnabled = true
                tokenEditText.setTextColor(android.graphics.Color.WHITE) // 白文字
                tokenEditText.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor("#2D2D2D".toColorInt()) // 通常背景
                    cornerRadius = 12f
                }
                tokenEditText.setPadding(24, 16, 24, 16)
                tokenButton.text = "トークンを確定 🔐"
                tokenButton.backgroundTintList = android.content.res.ColorStateList.valueOf("#009688".toColorInt()) // ティール
            }
        }

        tokenButton.setOnClickListener {
            val currentToken = sharedPrefs.getString("ingest_token", "") ?: ""
            if (currentToken.isNotEmpty()) {
                // 削除処理
                sharedPrefs.edit { remove("ingest_token") }
                updateTokenUi()
                updateUiState()
                Toast.makeText(this, "トークンを削除しました", Toast.LENGTH_SHORT).show()
            } else {
                // 確定処理
                val input = tokenEditText.text.toString().trim()
                if (input.isEmpty()) {
                    Toast.makeText(this, "トークンを入力してください", Toast.LENGTH_SHORT).show()
                } else {
                    sharedPrefs.edit { putString("ingest_token", input) }
                    updateTokenUi()
                    updateUiState()
                    Toast.makeText(this, "トークンを保存・確定しました", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 初期ロード
        updateTokenUi()

        tokenCard.addView(tokenLabel)
        tokenCard.addView(tokenEditText)
        tokenCard.addView(tokenButton)
        layout.addView(tokenCard)

        // 5. 制御ボタン（監視開始/停止）
        actionButton = Button(this).apply {
            text = "..."
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 20
            }
        }
        layout.addView(actionButton)

        // 6. 画像選択ボタン
        pickImageButton = Button(this).apply {
            text = "画像を選択して登録（複数可）️"
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 48
            }
            backgroundTintList = android.content.res.ColorStateList.valueOf("#009688".toColorInt()) // ティール色
            setOnClickListener {
                pickImagesLauncher.launch("image/*")
            }
        }
        layout.addView(pickImageButton)

        // 7. 手動OCR結果表示カード (スクロール型カード)
        val resultCardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor("#171717".toColorInt())
            cornerRadius = 24f
            setStroke(2, "#262626".toColorInt())
        }
        val resultLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resultCardBg
            setPadding(36, 36, 36, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 48
            }
        }
        val resultHeader = TextView(this).apply {
            text = "手動OCR結果"
            textSize = 12f
            setTextColor("#888888".toColorInt())
            setPadding(0, 0, 0, 16)
        }
        
        // カードを収容するコンテナ
        resultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // 初期プレースホルダー
        val placeholder = TextView(this).apply {
            text = "ここに選択した画像の文字認識結果が表示されます（選択・コピー可能）。"
            textSize = 14f
            setTextColor("#DDDDDD".toColorInt())
        }
        resultContainer.addView(placeholder)
        
        resultLayout.addView(resultHeader)
        resultLayout.addView(resultContainer)
        layout.addView(resultLayout)

        // 8. アプリ説明
        descTextView = TextView(this).apply {
            text = "プロセカのプレイ中にスクリーンショットを撮影すると、自動的に画面の左上・左下を検知して日本語文字認識(OCR)を行い、結果を通知します。"
            textSize = 13f
            setTextColor(android.graphics.Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        layout.addView(descTextView)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    /**
     * 権限やサービスの起動状態に応じてUIおよびボタンのアクションを更新します
     */
    private fun updateUiState() {
        val hasStandard = areStandardPermissionsGranted()
        val hasUsage = isUsageStatsPermissionGranted()

        // 権限が不足している場合は手動OCRボタンを無効化
        pickImageButton.isEnabled = hasStandard

        if (!hasStandard) {
            statusTextView.text = "権限が必要です 🔴"
            statusTextView.setTextColor("#FF6B6B".toColorInt()) // 赤色
            actionButton.text = "標準権限を許可する"
            actionButton.backgroundTintList = android.content.res.ColorStateList.valueOf("#FF6B6B".toColorInt())
            actionButton.setOnClickListener {
                ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
            }
            descTextView.text = "スクリーンショットの検知と通知表示のため、権限を許可してください。"
        } else if (!hasUsage) {
            statusTextView.text = "使用状況権限が必要です 🟡"
            statusTextView.setTextColor("#FFD93D".toColorInt()) // 黄色
            actionButton.text = "設定画面を開く"
            actionButton.backgroundTintList = android.content.res.ColorStateList.valueOf("#FFD93D".toColorInt())
            actionButton.setOnClickListener {
                requestUsageStatsPermission()
            }
            descTextView.text = "現在プレイ中のゲーム（プロセカ）を認識するために「使用状況へのアクセス」許可が必要です。"
        } else {
            // 全て権限クリアしている場合、サービスの状態をチェック
            val isRunning = isServiceRunning(ScreenShotService::class.java)
            if (isRunning) {
                statusTextView.text = "自動監視中 🟢"
                statusTextView.setTextColor("#6BCB77".toColorInt()) // 緑色
                actionButton.text = "監視サービスを停止"
                actionButton.backgroundTintList = android.content.res.ColorStateList.valueOf("#E57373".toColorInt()) // 薄赤色
                actionButton.setOnClickListener {
                    stopScreenShotService()
                    updateUiState()
                }
            } else {
                statusTextView.text = "自動監視停止中 🔴"
                statusTextView.setTextColor("#FF6B6B".toColorInt()) // 赤色
                actionButton.text = "自動監視サービスを開始"
                actionButton.backgroundTintList = android.content.res.ColorStateList.valueOf("#4CAF50".toColorInt()) // 緑色
                actionButton.setOnClickListener {
                    startScreenShotService()
                    updateUiState()
                }
            }
            descTextView.text = "プロセカのプレイ中にスクリーンショットを撮影すると、自動的に画面の左上・左下を検知して日本語文字認識(OCR)を行い、結果を通知します。"
        }
    }

    /**
     * 手動選択された複数の画像を順にクロップし、日本語OCRおよびAPI送信を実行する
     */
    private fun processPickedImages(uris: List<Uri>) {
        statusTextView.text = "手動OCR: 処理中... "
        statusTextView.setTextColor("#FFD93D".toColorInt())
        
        runOnUiThread {
            resultContainer.removeAllViews() // 前回の表示結果をクリア
        }

        val totalCount = uris.size

        // 再帰的に1枚ずつ処理していくヘルパー関数
        fun processNext(index: Int) {
            if (index >= totalCount) {
                // すべての画像の処理が完了した場合
                runOnUiThread {
                    updateUiState()
                }
                return
            }

            val uri = uris[index]
            val imageNum = index + 1
            runOnUiThread {
                statusTextView.text = "処理中 ($imageNum / $totalCount)... 🔄"
            }

            try {
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
                    runOnUiThread {
                        addOcrResultCard(
                            imageNum, totalCount, "画像の読み込みに失敗しました。",
                            "読込失敗 ❌", false, "❌ 画像データをデコードできませんでした。"
                        )
                    }
                    processNext(index + 1)
                    return
                }

                val width = sourceBitmap.width
                val height = sourceBitmap.height

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
                    tasks.add(recognizer.process(InputImage.fromBitmap(bitmap1, 0)))
                }
                if (bitmap2 != null) {
                    tasks.add(recognizer.process(InputImage.fromBitmap(bitmap2, 0)))
                }

                if (tasks.isEmpty()) {
                    sourceBitmap.recycle()
                    runOnUiThread {
                        addOcrResultCard(
                            imageNum, totalCount, "クロップ画像を作成できませんでした。",
                            "クロップ失敗 ❌", false, "❌ 画像のクロップ切り抜きが正しく処理されませんでした。"
                        )
                    }
                    processNext(index + 1)
                    return
                }

                Tasks.whenAllComplete(tasks).addOnCompleteListener {
                    // リソースの解放
                    sourceBitmap.recycle()
                    bitmap1?.recycle()
                    bitmap2?.recycle()

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

                    // 結果の結合
                    val combinedText = StringBuilder()
                    if (resultText1.isNotBlank()) {
                        combinedText.append("【左上エリア】\n").append(resultText1).append("\n\n")
                    }
                    if (resultText2.isNotBlank()) {
                        combinedText.append("【左下エリア】\n").append(resultText2)
                    }

                    val finalResult = combinedText.toString().trim()
                    if (finalResult.isNotEmpty()) {
                        // トークンを取得してサーバーへPOST
                        val sharedPrefs = getSharedPreferences("sekai_rec_ocr_prefs", MODE_PRIVATE)
                        val token = sharedPrefs.getString("ingest_token", "") ?: ""

                        if (token.isEmpty()) {
                            runOnUiThread {
                                addOcrResultCard(
                                    imageNum, totalCount, finalResult,
                                    "送信スキップ (トークン未設定) ⚠️", false,
                                    "⚠️ APIトークンが設定されていません。手動確定を行ってください。"
                                )
                            }
                            processNext(index + 1)
                            return@addOnCompleteListener
                        }

                        val imageTime = getImageTimeMillis(uri)
                        val isoTime = formatEpochToIso8601(imageTime)

                        postOcrText(token, finalResult, isoTime) { statusCode, responseBody, error ->
                            runOnUiThread {
                                val isSuccess = (error == null && statusCode == 200)
                                val summary: String
                                val detailLog: String

                                if (isSuccess) {
                                    val songTitle = parseSongTitleValue(responseBody) ?: "曲名不明"
                                    val difficulty = parseDifficultyValue(responseBody) ?: "不明"
                                    val great = parseValue(responseBody, "great")
                                    val good = parseValue(responseBody, "good")
                                    val bad = parseValue(responseBody, "bad")
                                    val miss = parseValue(responseBody, "miss")
                                    
                                    summary = "$songTitle【$difficulty】($great-$good-$bad-$miss)"
                                    detailLog = "✅ API送信成功 (200 OK)"
                                } else {
                                    if (error != null) {
                                        summary = "通信エラー ❌"
                                        detailLog = "❌ 通信エラー: ${error.message}"
                                    } else {
                                        val errCode = parseErrorDetail(responseBody) ?: "HTTP $statusCode"
                                        summary = "送信エラー ($errCode) ❌"
                                        detailLog = "❌ APIエラーコード: $errCode"
                                    }
                                }

                                addOcrResultCard(imageNum, totalCount, finalResult, summary, isSuccess, detailLog)
                                processNext(index + 1)
                            }
                        }
                    } else {
                        runOnUiThread {
                            addOcrResultCard(
                                imageNum, totalCount, "文字は検出されませんでした。",
                                "文字検出なし ❌", false, "❌ OCRで認識可能な日本語文字が見つかりませんでした。"
                            )
                        }
                        processNext(index + 1)
                    }
                }

            } catch (_: Exception) {
                runOnUiThread {
                    addOcrResultCard(
                        imageNum, totalCount, "エラーが発生しました。",
                        "システムエラー ❌", false, "❌ 処理の実行中に内部で例外が発生しました。"
                    )
                }
                processNext(index + 1)
            }
        }

        // 1枚目の処理を開始
        processNext(0)
    }

    /**
     * アプリ画面に展開可能な開閉式結果カード(アコーディオン型)を動的に追加する
     */
    private fun addOcrResultCard(
        imageNum: Int,
        totalCount: Int,
        rawOcrText: String,
        summaryText: String,
        isSuccess: Boolean,
        detailLog: String
    ) {
        // カードの角丸背景デザイン
        val cardBg = android.graphics.drawable.GradientDrawable().apply {
            setColor("#222222".toColorInt())
            cornerRadius = 16f
            setStroke(2, if (isSuccess) "#009688".toColorInt() else "#E57373".toColorInt())
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        // タップ可能なヘッダーバー
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
        }

        val statusIndicator = TextView(this).apply {
            text = if (isSuccess) "✅ " else "❌ "
            textSize = 15f
        }

        val summaryTextView = TextView(this).apply {
            text = "[$imageNum/$totalCount] $summaryText"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val arrowIcon = TextView(this).apply {
            text = "▼"
            textSize = 12f
            setTextColor("#888888".toColorInt())
        }

        headerLayout.addView(statusIndicator)
        headerLayout.addView(summaryTextView)
        headerLayout.addView(arrowIcon)

        // 開閉されるOCRテキスト詳細表示枠（デフォルト非表示）
        val detailLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 16, 0, 0)
        }

        val logView = TextView(this).apply {
            text = detailLog
            textSize = 12f
            setTextColor(if (isSuccess) "#80CBC4".toColorInt() else "#FFAB91".toColorInt())
            setPadding(0, 0, 0, 16)
        }

        val ocrHeader = TextView(this).apply {
            text = "OCR検出テキスト (長押しでコピー可):"
            textSize = 11f
            setTextColor("#888888".toColorInt())
            setPadding(0, 0, 0, 8)
        }

        val rawTextView = TextView(this).apply {
            text = rawOcrText
            textSize = 13f
            setTextColor("#DDDDDD".toColorInt())
            setTextIsSelectable(true) // 選択コピー可能にする
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor("#161616".toColorInt())
                cornerRadius = 10f
            }
            setPadding(16, 16, 16, 16)
        }

        detailLayout.addView(logView)
        detailLayout.addView(ocrHeader)
        detailLayout.addView(rawTextView)

        cardLayout.addView(headerLayout)
        cardLayout.addView(detailLayout)

        // タップでアコーディオンを切り替え
        headerLayout.setOnClickListener {
            if (detailLayout.visibility == View.VISIBLE) {
                detailLayout.visibility = View.GONE
                arrowIcon.text = "▼"
            } else {
                detailLayout.visibility = View.VISIBLE
                arrowIcon.text = "▲"
            }
        }

        resultContainer.addView(cardLayout)
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
     * 指定された矩形範囲でBitmapをクロップする安全なヘルパー関数
     */
    @Suppress("SameParameterValue")
    private fun cropBitmap(source: Bitmap, x: Int, y: Int, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val safeX = x.coerceIn(0, source.width - 1)
        val safeY = y.coerceIn(0, source.height - 1)
        val safeW = width.coerceAtMost(source.width - safeX)
        val safeH = height.coerceAtMost(source.height - safeY)

        if (safeW <= 0 || safeH <= 0) return null
        return try {
            Bitmap.createBitmap(source, safeX, safeY, safeW, safeH)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 画像のURIから作成日時のエポックミリ秒を取得する
     */
    private fun getImageTimeMillis(uri: Uri): Long {
        val projection = arrayOf(
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.Images.ImageColumns.DATE_ADDED
        )
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateTakenIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_ADDED)
                    
                    val dateTaken = if (dateTakenIndex != -1) cursor.getLong(dateTakenIndex) else 0L
                    val dateAdded = if (dateAddedIndex != -1) cursor.getLong(dateAddedIndex) else 0L
                    
                    if (dateTaken > 0) {
                        dateTaken
                    } else if (dateAdded > 0) {
                        dateAdded * 1000 // 秒からミリ秒に変換
                    } else {
                        System.currentTimeMillis()
                    }
                } else {
                    System.currentTimeMillis()
                }
            } ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    /**
     * ミリ秒エポックタイムをISO 8601形式 of タイムスタンプ文字列に変換
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
     * レスポンスボディから曲名（songTitle）を正規表現で抽出
     */
    private fun parseSongTitleValue(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        val regex = "\"songTitle\"\\s*:\\s*\"([^\"]+)\"".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(responseBody)?.groups?.get(1)?.value
    }

    /**
     * レスポンスボディから難易度（difficulty）を正規表現で抽出
     */
    private fun parseDifficultyValue(responseBody: String?): String? {
        if (responseBody.isNullOrBlank()) return null
        val regex = "\"difficulty\"\\s*:\\s*\"([^\"]+)\"".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(responseBody)?.groups?.get(1)?.value
    }

    /**
     * レスポンスボディから特定のキー（great, good, bad, missなど）の数値を正規表現で抽出
     */
    private fun parseValue(responseBody: String?, key: String): String {
        if (responseBody.isNullOrBlank()) return "-"
        val regex = "\"$key\"\\s*:\\s*\"?(\\d+)\"?".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(responseBody)?.groups?.get(1)?.value ?: "-"
    }

    /**
     * レスポンスボディから曲情報と判定結果を抽出してトーストメッセージを作成
     */
    private fun parseSuccessToastMessage(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) return "成功 (詳細不明)"
        
        val songTitle = parseSongTitleValue(responseBody) ?: "曲名不明"
        val difficulty = parseDifficultyValue(responseBody)
        
        val great = parseValue(responseBody, "great")
        val good = parseValue(responseBody, "good")
        val bad = parseValue(responseBody, "bad")
        val miss = parseValue(responseBody, "miss")
        
        return if (difficulty != null) {
            "成功: $songTitle $difficulty($great-$good-$bad-$miss)"
        } else {
            "成功: $songTitle ($great-$good-$bad-$miss)"
        }
    }

    /**
     * 標準権限（画像アクセス、通知）が全て許可されているか確認
     */
    private fun areStandardPermissionsGranted(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * システムの使用状況アクセス権限が許可されているか確認
     */
    @Suppress("DEPRECATION")
    private fun isUsageStatsPermissionGranted(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 使用状況アクセス権限の設定画面を開くようユーザーに促す
     */
    private fun requestUsageStatsPermission() {
        Toast.makeText(
            this,
            "「使用状況へのアクセス」からこのアプリを許可してください",
            Toast.LENGTH_LONG
        ).show()
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        startActivity(intent)
    }

    /**
     * 標準権限の要求結果を受け取るコールバック
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            updateUiState()
        }
    }

    /**
     * スクショ監視サービス（ScreenShotService）の起動
     */
    private fun startScreenShotService() {
        val intent = Intent(this, ScreenShotService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "スクショ監視を開始しました", Toast.LENGTH_SHORT).show()
    }

    /**
     * スクショ監視サービス（ScreenShotService）の停止
     */
    private fun stopScreenShotService() {
        val intent = Intent(this, ScreenShotService::class.java)
        stopService(intent)
        Toast.makeText(this, "スクショ監視を停止しました", Toast.LENGTH_SHORT).show()
    }

    /**
     * 指定のサービスが現在起動中かチェックするヘルパー
     */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
