package jp.example.kyoshinviewer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import jp.example.kyoshinviewer.data.KwatchWebPageSource
import jp.example.kyoshinviewer.data.MonitorPageSource
import jp.example.kyoshinviewer.intensity.color.IntensityClassColors
import jp.example.kyoshinviewer.intensity.color.KyoshinShindoColorMap
import jp.example.kyoshinviewer.intensity.processor.CalibrationMonitor
import jp.example.kyoshinviewer.intensity.processor.IntensityClass
import jp.example.kyoshinviewer.intensity.processor.ProcessedSample
import jp.example.kyoshinviewer.measurement.SeismicMeasurementEngine
import jp.example.kyoshinviewer.recording.CsvRecorder
import jp.example.kyoshinviewer.ui.IntensityColorBarView
import jp.example.kyoshinviewer.ui.IntensityHistoryBandView
import jp.example.kyoshinviewer.ui.WaveformView
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class MainActivity : AppCompatActivity() {

    // Material 3 dark color roles. Intensity colors remain defined by the seismic scale.
    private val m3Background = Color.rgb(12, 18, 27)
    private val m3Surface = Color.rgb(18, 25, 36)
    private val m3SurfaceContainer = Color.rgb(25, 34, 47)
    private val m3SurfaceContainerLow = Color.rgb(21, 29, 41)
    private val m3SurfaceContainerHigh = Color.rgb(34, 45, 60)
    private val m3Primary = Color.rgb(174, 199, 255)
    private val m3OnPrimary = Color.rgb(15, 39, 76)
    private val m3PrimaryContainer = Color.rgb(43, 65, 101)
    private val m3OnPrimaryContainer = Color.rgb(220, 230, 255)
    private val m3OnSurface = Color.rgb(226, 232, 241)
    private val m3OnSurfaceVariant = Color.rgb(184, 196, 212)
    private val m3Outline = Color.rgb(112, 128, 148)
    private val m3OutlineVariant = Color.rgb(66, 80, 99)

    private lateinit var webView: WebView
    private lateinit var secondaryWebView: WebView
    private lateinit var webViewArea: LinearLayout
   private lateinit var primaryBrowserBar: LinearLayout
    private lateinit var secondaryBrowserBar: LinearLayout
    private lateinit var primaryBrowserUrlInput: EditText
    private lateinit var secondaryBrowserUrlInput: EditText
    private lateinit var splitToggleButton: Button
    private var splitEnabled = false
    private var topSplitRatio = 50f
    private lateinit var splitRatioSlider: Slider
    private lateinit var splitRatioLabel: TextView
    private lateinit var errorLayout: LinearLayout
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var measurementEngine: SeismicMeasurementEngine

    private lateinit var sensorStatusText: TextView
    private lateinit var intensityText: TextView
    private lateinit var intensityClassText: TextView
    private lateinit var pgaText: TextView
    private lateinit var sampleRateText: TextView
    private lateinit var calibrationText: TextView
    private lateinit var measureButton: Button
    private lateinit var recordButton: Button
    private lateinit var historyBand: IntensityHistoryBandView
    private lateinit var intensityColorBar: IntensityColorBarView
    private lateinit var accelerationColorBar: IntensityColorBarView
    private lateinit var waveformView: WaveformView

    private var drawerOpen = false
    private lateinit var drawerPanel: LinearLayout
    private lateinit var drawerScrim: View
    private lateinit var drawerToggleButton: View
    private lateinit var mainRoot: FrameLayout
    private var csvHistoryVisible = false

    private val appTypeface by lazy {
        resources.getFont(R.font.gen_zen_gothic_kaic)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val recorderLock = Any()
    private val csvRecorder by lazy { CsvRecorder(this) }
    private val calibrationMonitor = CalibrationMonitor(200)
    private val preTriggerBuffer = java.util.ArrayDeque<ProcessedSample>()

    private var autoRecording = false
    private var manualRecording = false

    private var autoStartIntensity = IntensityClass.LEVEL_2
    private var autoStopIntensity = IntensityClass.LEVEL_0

    private lateinit var autoStartSlider: Slider
    private lateinit var autoStopSlider: Slider
    private lateinit var autoStartLabel: TextView
    private lateinit var autoStopLabel: TextView
    private var updatingAutoSliders = false

    private var belowStopThresholdSinceNanos = 0L
    private var selectedExportFile: File? = null
    private var csvHistoryStatusText: TextView? = null
    private var csvHistoryListContent: LinearLayout? = null
    private val csvHistoryExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var playbackRunnable: Runnable? = null
    private var playbackDialog: AlertDialog? = null
    private var networkLost = false
    private var isMeasuring = false
    private var isDestroyed = false

    @Volatile
    private var lastUiUpdateNanos = 0L
    @Volatile private var lastHistoryUiUpdateNanos = 0L
    @Volatile private var lastSlowUiUpdateNanos = 0L
    private val oneSecondMaxGalBits = AtomicLong(java.lang.Double.doubleToRawLongBits(0.0))

    private fun accumulateOneSecondMaxGal(value: Double) {
        if (!value.isFinite() || value <= 0.0) return

        val newBits = java.lang.Double.doubleToRawLongBits(value)

        while (true) {
            val oldBits = oneSecondMaxGalBits.get()
            val oldValue = java.lang.Double.longBitsToDouble(oldBits)

            if (value <= oldValue) return
            if (oneSecondMaxGalBits.compareAndSet(oldBits, newBits)) return
        }
    }
    private val monitorPageSource: MonitorPageSource = KwatchWebPageSource()

    private val createCsvDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            val source = selectedExportFile
            selectedExportFile = null

            if (source == null) return@registerForActivityResult

            if (uri == null) {
                if (csvHistoryVisible) {
                    csvHistoryStatusText?.text = "CSV書き出しをキャンセルしました"
                }
                return@registerForActivityResult
            }

            csvHistoryStatusText?.text = "書き出し中…"

            csvHistoryExecutor.execute {
                val existedBefore = source.isFile
                val exported = if (existedBefore) {
                    runCatching {
                        synchronized(recorderLock) {
                            csvRecorder.exportFileTo(source, uri)
                        }
                    }.getOrDefault(false)
                } else {
                    false
                }

                val message = when {
                    !existedBefore || (!exported && !source.isFile) ->
                        "この記録は既に削除されています。更新してください。"
                    exported ->
                        "CSVを書き出しました"
                    else ->
                        "CSVの書き出しに失敗しました"
                }

                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread

                    if (csvHistoryVisible) {
                        csvHistoryStatusText?.text = message
                    } else {
                        showMessage(message)
                    }
                }
            }
        }
    private val retryRunnable = object : Runnable {
        override fun run() {
            if (!isDestroyed &&
                ::errorLayout.isInitialized &&
                errorLayout.visibility == View.VISIBLE
            ) {
                webView.reload()
                handler.postDelayed(this, 15000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val browserPrefs = getSharedPreferences("browser_settings", MODE_PRIVATE)
        topSplitRatio = browserPrefs.getFloat("split_ratio", 50f).coerceIn(10f, 90f)
        supportActionBar?.hide()
        hideSystemBars()

        KyoshinShindoColorMap.init(this)

        val autoRecordingPrefs =
            getSharedPreferences("auto_recording", MODE_PRIVATE)

        autoStartIntensity = IntensityClass.values().getOrElse(
            autoRecordingPrefs.getInt("start_intensity", IntensityClass.LEVEL_2.ordinal)
        ) { IntensityClass.LEVEL_2 }

        autoStopIntensity = IntensityClass.values().getOrElse(
            autoRecordingPrefs.getInt("stop_intensity", IntensityClass.LEVEL_0.ordinal)
        ) { IntensityClass.LEVEL_0 }

        if (autoStartIntensity.ordinal <= autoStopIntensity.ordinal) {
            autoStartIntensity = IntensityClass.LEVEL_2
            autoStopIntensity = IntensityClass.LEVEL_0
        }

        val outer = FrameLayout(this).apply {
            setBackgroundColor(m3Background)
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(m3Background)
        }

        createTopPanel().also { card ->
            page.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(12), dp(10), dp(12), dp(2))
            })
        }

        page.addView(text("出典: KyoshinShindoColorMap / ingen084氏", 9f).apply {
            setTextColor(m3OnSurfaceVariant)
            setPadding(dp(18), dp(4), dp(18), dp(6))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // WebView表示領域。画面全体ではなく、この領域だけを上下分割する。
        webViewArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(m3Background)
        }

        webView = createBrowserWebView(isMonitor = true)
        secondaryWebView = createBrowserWebView(isMonitor = false)

        webViewArea.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

       primaryBrowserBar = createBrowserUrlBar(isPrimary = true)
        secondaryBrowserBar = createBrowserUrlBar(isPrimary = false)
        webViewArea.addView(secondaryWebView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply {
            height = 0
        })
        secondaryWebView.visibility = View.GONE

        page.addView(webViewArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 画面本体をルートレイアウトに追加
        outer.addView(page, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 強震モニタが最上部にある状態で下へスワイプすると再読み込み
        var refreshStartY = 0f
        webView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    refreshStartY = event.y
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val pullDistance = event.y - refreshStartY
                    if (webView.scrollY <= 0 && pullDistance > dp(110)) {
                        webView.reload()
                        showMessage("ページを更新しています")
                    }
                }
            }
            false
        }

        // サイドバーの背景
        drawerScrim = View(this).apply {
            setBackgroundColor(Color.argb(150, 3, 7, 13))
            visibility = View.GONE
            setOnClickListener { setDrawerOpen(false) }
        }
        outer.addView(drawerScrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 右側の操作パネル
        val drawerWidth = minOf(
            dp(360),
            (resources.displayMetrics.widthPixels * 0.85f).toInt()
        )

        drawerPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(m3Surface, dp(28))
            clipToOutline = true
            elevation = dp(12).toFloat()
            setPadding(dp(10), dp(8), dp(10), dp(12))
        }

        val drawerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(4), dp(8))
        }

        drawerHeader.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("操作・センサー", 17f, true))
            addView(text("計測と表示の設定", 11f).apply {
                setTextColor(m3OnSurfaceVariant)
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, dp(54), 1f))

        drawerHeader.addView(createMaterialButton("閉じる", ButtonTone.TEXT).apply {
            setOnClickListener { setDrawerOpen(false) }
        }, LinearLayout.LayoutParams(dp(76), dp(42)))

               drawerPanel.addView(drawerHeader)

        val drawerScrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val drawerScrollView = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
        }

        drawerScrollView.addView(
            drawerScrollContent,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        drawerPanel.addView(
            drawerScrollView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        drawerScrollContent.addView(text("ブラウザ", 13f, true).apply {
            setTextColor(m3Primary)
            setPadding(dp(10), dp(8), 0, dp(2))
        })

        drawerScrollContent.addView(primaryBrowserBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        drawerScrollContent.addView(secondaryBrowserBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        splitToggleButton = createMaterialButton("分割ブラウザを表示", ButtonTone.TONAL).apply {
            setOnClickListener { setSplitEnabled(!splitEnabled) }
        }

        drawerScrollContent.addView(splitToggleButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                setMargins(dp(2), dp(6), dp(2), dp(12))
            }
        )

        val splitRatioPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(4))
        }

        splitRatioLabel = text(
            "上段 ${topSplitRatio.toInt()}% / 下段 ${(100f - topSplitRatio).toInt()}%",
            11f,
            true
        ).apply {
            setTextColor(m3OnSurfaceVariant)
        }
        splitRatioPanel.addView(splitRatioLabel)

        splitRatioSlider = Slider(this).apply {
            valueFrom = 10f
            valueTo = 90f
            stepSize = 1f
            value = topSplitRatio
            contentDescription = "上下ブラウザの分割比率"

            addOnChangeListener { _, value, _ ->
                topSplitRatio = value
                getSharedPreferences("browser_settings", MODE_PRIVATE)
                    .edit()
                    .putFloat("split_ratio", value)
                    .apply()
                splitRatioLabel.text =
                    "上段 ${value.toInt()}% / 下段 ${(100f - value).toInt()}%"

                if (splitEnabled) {
                    applySplitRatio()
                }
            }
        }

        splitRatioPanel.addView(splitRatioSlider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            )
        )

        drawerScrollContent.addView(splitRatioPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        drawerScrollContent.addView(createBottomPanel(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        drawerScrollContent.addView(
            createMaterialButton("設定を初期化", ButtonTone.OUTLINED).apply {
                setOnClickListener { showResetSettingsDialog() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                setMargins(dp(8), dp(12), dp(8), dp(16))
            }
        )
outer.addView(drawerPanel, FrameLayout.LayoutParams(
            drawerWidth,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END
        ))

        drawerPanel.translationX = drawerWidth.toFloat()

        // 画面右下の操作ボタン
        drawerToggleButton = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(m3PrimaryContainer)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), m3Outline)
            }
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "操作メニューを開く"
            setOnClickListener { setDrawerOpen(true) }

            repeat(3) {
                addView(View(this@MainActivity).apply {
                    setBackgroundColor(Color.WHITE)
                }, LinearLayout.LayoutParams(
                    dp(26),
                    dp(2)
                ).apply {
                    if (it != 2) bottomMargin = dp(5)
                })
            }
        }

        outer.addView(drawerToggleButton, FrameLayout.LayoutParams(
            dp(56),
            dp(56),
            Gravity.BOTTOM or Gravity.END
        ).apply {
            rightMargin = dp(16)
            bottomMargin = dp(18)
        })

        errorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(m3Surface)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "通信エラー"
                textSize = 20f
                setTextColor(m3OnSurface)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "通信状態または接続先サーバーを確認しています。"
                textSize = 14f
                setTextColor(m3OnSurfaceVariant)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(24))
            })
            addView(createMaterialButton("再読み込み", ButtonTone.PRIMARY).apply {
                setOnClickListener { webView.reload() }
            })
        }
        outer.addView(errorLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        mainRoot = outer
        setContentView(outer)

        measurementEngine = SeismicMeasurementEngine(
            context = this,
            onSample = { sample ->
                synchronized(recorderLock) {
                    updateAutomaticRecording(sample)
                }
                val stats = calibrationMonitor.push(
                    sample.axGal, sample.ayGal, sample.azGal
                )
                accumulateOneSecondMaxGal(sample.combinedGal)
                val timestampNanos = sample.timestampNanos

                // 数値表示：最大30Hz
                if (lastUiUpdateNanos == 0L ||
                    timestampNanos - lastUiUpdateNanos >= 33_333_333L
                ) {
                    lastUiUpdateNanos = timestampNanos
                    if (!isDestroyed) {
                        handler.post {
                            if (!isDestroyed) updateMeasurementNumbers(sample)
                        }
                    }
                }

                // 履歴帯・XYZ波形：最大10Hz
                if (lastHistoryUiUpdateNanos == 0L ||
                    timestampNanos - lastHistoryUiUpdateNanos >= 100_000_000L
                ) {
                    lastHistoryUiUpdateNanos = timestampNanos
                    if (!isDestroyed) {
                        handler.post {
                            if (!isDestroyed) updateMeasurementHistory(sample)
                        }
                    }
                }

                // 階級バッジ・色バー：最大1Hz
                if (lastSlowUiUpdateNanos == 0L ||
                    timestampNanos - lastSlowUiUpdateNanos >= 1_000_000_000L
                ) {
                    lastSlowUiUpdateNanos = timestampNanos

                    val pgaWindowMax = java.lang.Double.longBitsToDouble(
                        oneSecondMaxGalBits.getAndSet(
                            java.lang.Double.doubleToRawLongBits(0.0)
                        )
                    )

                    if (!isDestroyed) {
                        handler.post {
                            if (!isDestroyed) {
                                updateMeasurementSlow(sample, stats, pgaWindowMax)
                            }
                        }
                    }
                }
            }
        )

        sensorStatusText.text = if (measurementEngine.isAvailable) {
            "待機中 / ${measurementEngine.describeSensor()}"
        } else {
            "加速度センサーが見つかりません"
        }

        val connectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        networkMonitor = NetworkMonitor(
            connectivityManager = connectivityManager,
            onNetworkLost = {
                runOnUiThread {
                    networkLost = true
                    showError()
                }
            },
            onNetworkAvailable = {
                runOnUiThread {
                    if (networkLost) {
                        networkLost = false
                        handler.removeCallbacks(retryRunnable)
                        webView.reload()
                    }
                }
            }
        )
        networkMonitor.start()

        if (savedInstanceState != null) {
            savedInstanceState.getBundle("monitor_webview_state")?.let {
                webView.restoreState(it)
            }
            savedInstanceState.getBundle("secondary_webview_state")?.let {
                secondaryWebView.restoreState(it)
            }
            setSplitEnabled(browserPrefs.getBoolean("split_enabled", false))
        } else {
            setSplitEnabled(browserPrefs.getBoolean("split_enabled", false))

            browserPrefs.getString("primary_url", null)
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let { savedUrl ->
                    primaryBrowserUrlInput.setText(savedUrl)
                    webView.loadUrl(savedUrl)
                }

            browserPrefs.getString("secondary_url", null)
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?.let { savedUrl ->
                    secondaryBrowserUrlInput.setText(savedUrl)
                    secondaryWebView.loadUrl(savedUrl)
                }
        }

        if (savedInstanceState?.getBoolean("csv_history_visible") == true) {
            showRecordingList()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createBrowserWebView(isMonitor: Boolean): WebView {
        return WebView(this).apply {
            setBackgroundColor(m3Background)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                   if (isMonitor) hideError()

                    if (!url.isNullOrBlank() &&
                        (url.startsWith("http://") || url.startsWith("https://"))
                    ) {
                        if (isMonitor && ::primaryBrowserUrlInput.isInitialized) {
                            primaryBrowserUrlInput.setText(url)
                        } else if (!isMonitor &&
                            ::secondaryBrowserUrlInput.isInitialized
                        ) {
                            secondaryBrowserUrlInput.setText(url)
                        }

                        getSharedPreferences("browser_settings", MODE_PRIVATE)
                            .edit()
                            .putString(
                                if (isMonitor) "primary_url" else "secondary_url",
                                url
                            )
                            .apply()
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (isMonitor && request?.isForMainFrame == true) showError()
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    if (isMonitor && request?.isForMainFrame == true &&
                        (errorResponse?.statusCode ?: 0) >= 400
                    ) showError()
                }
            }
        }
    }

   private fun createBrowserUrlBar(isPrimary: Boolean): LinearLayout {
        val input = TextInputEditText(this).apply {
            typeface = appTypeface
            textSize = 14f
            setSingleLine(true)
            hint = "https://example.com"
            setTextColor(m3OnSurface)
            setHintTextColor(m3OnSurfaceVariant)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(4), 0, dp(4), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    if (isPrimary) loadPrimaryUrl() else loadSecondaryUrl()
                    true
                } else false
            }
        }

        if (isPrimary) {
            primaryBrowserUrlInput = input
        } else {
            secondaryBrowserUrlInput = input
        }

        val urlLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = "URL"
            setHintTextColor(ColorStateList.valueOf(m3OnSurfaceVariant))
            setBoxStrokeColorStateList(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf()
                    ),
                    intArrayOf(m3Primary, m3Outline)
                )
            )
            setBoxStrokeWidth(dp(1))
            setBoxStrokeWidthFocused(dp(2))
            setBoxCornerRadii(
                dp(12).toFloat(), dp(12).toFloat(),
                dp(12).toFloat(), dp(12).toFloat()
            )
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(urlLayout, LinearLayout.LayoutParams(0, dp(56), 1f))

            addView(createMaterialButton("開く", ButtonTone.PRIMARY).apply {
                setOnClickListener {
                    if (isPrimary) loadPrimaryUrl() else loadSecondaryUrl()
                }
            }, LinearLayout.LayoutParams(dp(76), dp(44)).apply {
                leftMargin = dp(8)
            })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(text(
                if (isPrimary) "上段ブラウザ" else "下段ブラウザ",
                11f,
                true
            ).apply {
                setTextColor(m3OnSurfaceVariant)
                setPadding(dp(2), dp(5), 0, dp(2))
            })

            addView(row)

            if (!isPrimary) visibility = View.GONE
        }
    }

    private fun loadPrimaryUrl() {
        loadBrowserUrl(primaryBrowserUrlInput, webView)
    }

    private fun loadSecondaryUrl() {
        loadBrowserUrl(secondaryBrowserUrlInput, secondaryWebView)
    }

    private fun loadBrowserUrl(input: EditText, target: WebView) {
        var address = input.text.toString().trim()

        if (address.isBlank()) {
            showMessage("URLを入力してください")
            return
        }

        if (!address.contains("://")) {
            address = "https://$address"
        }

        val uri = runCatching { Uri.parse(address) }.getOrNull()

        if (uri == null ||
            (uri.scheme != "http" && uri.scheme != "https") ||
            uri.host.isNullOrBlank()
        ) {
            showMessage("http:// または https:// のURLを入力してください")
            return
        }

        input.setText(address)
        target.loadUrl(uri.toString())

        val urlKey = if (target === webView) "primary_url" else "secondary_url"
        getSharedPreferences("browser_settings", MODE_PRIVATE)
            .edit()
            .putString(urlKey, uri.toString())
            .apply()

        val imm = getSystemService(INPUT_METHOD_SERVICE)
            as android.view.inputmethod.InputMethodManager

        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun applySplitRatio() {
        if (!::webView.isInitialized || !::secondaryWebView.isInitialized) return

        val primaryParams = webView.layoutParams as LinearLayout.LayoutParams
        val secondaryParams = secondaryWebView.layoutParams as LinearLayout.LayoutParams

        primaryParams.height = 0
        secondaryParams.height = 0

        primaryParams.weight =
            if (splitEnabled) topSplitRatio / 100f else 1f

        secondaryParams.weight =
            if (splitEnabled) (100f - topSplitRatio) / 100f else 0f

        webView.layoutParams = primaryParams
        secondaryWebView.layoutParams = secondaryParams
    }

    private fun setSplitEnabled(enabled: Boolean) {
        splitEnabled = enabled
        getSharedPreferences("browser_settings", MODE_PRIVATE)
            .edit()
            .putBoolean("split_enabled", enabled)
            .apply()

        if (!::secondaryBrowserBar.isInitialized) return

        secondaryBrowserBar.visibility = if (enabled) View.VISIBLE else View.GONE
        secondaryWebView.visibility = if (enabled) View.VISIBLE else View.GONE
        splitToggleButton.text =
            if (enabled) "分割ブラウザを閉じる" else "分割ブラウザを表示"

        applySplitRatio()
    }

    private fun createTopPanel(): MaterialCardView {
        val card = MaterialCardView(this).apply {
            setRadius(dp(24).toFloat())
            cardElevation = dp(2).toFloat()
            setStrokeWidth(dp(1))
            setStrokeColor(m3OutlineVariant)
            setCardBackgroundColor(m3SurfaceContainer)
            useCompatPadding = false
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // 上段：震度階級と震度履歴
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val classArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        classArea.addView(
            text("計測震度", 20f).apply {
                setTextColor(m3OnSurfaceVariant)
            }
        )

        intensityClassText = text("--", 32f, true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, dp(4))
            setTextColor(Color.WHITE)
            background = roundedBackground(m3PrimaryContainer, dp(12))
        }
        classArea.addView(intensityClassText, LinearLayout.LayoutParams(
            dp(80), dp(50)
        ).apply {
            leftMargin = dp(6)
        })

        val historyArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        historyArea.addView(label("震度履歴"))

        historyBand = IntensityHistoryBandView(this).apply {
            setCapacity(100)
        }
        historyArea.addView(historyBand, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(28)
        ).apply {
            topMargin = dp(6)
        })

        topRow.addView(classArea, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        topRow.addView(historyArea, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            leftMargin = dp(8)
        })
        panel.addView(topRow)
        panel.addView(View(this).apply {
            setBackgroundColor(m3OutlineVariant)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(6)
        })

        // 下段：左に計測値、右にXYZ波形
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, 0)
        }

        val valuesArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun addValueRow(caption: String, valueView: TextView): IntensityColorBarView {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(text(caption, 13f).apply {
                setTextColor(m3OnSurfaceVariant)
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueView)
            valuesArea.addView(row)

            return IntensityColorBarView(this@MainActivity).apply {
                setColor(Color.GRAY)
                valuesArea.addView(this, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(13)
                ).apply {
                    topMargin = dp(2)
                })
            }
        }

        intensityText = text("--.-", 22f, true).apply {
            includeFontPadding = false
        }
        intensityText.gravity = Gravity.END
        intensityColorBar = addValueRow("リアルタイム震度:", intensityText)

        pgaText = text("--.- gal", 22f, true).apply {
            includeFontPadding = false
            setPadding(0, 0, 0, dp(3))
        }
        pgaText.gravity = Gravity.END
        accelerationColorBar = addValueRow("リアルタイム加速度:", pgaText)

        val waveArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        waveArea.addView(label("XYZ 加速度波形（フィルタ後 / gal）"))

        waveformView = WaveformView(this).apply {
            setCapacity(100)
            setSeparatedSeries(true)
        }
        waveArea.addView(waveformView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(80)
        ).apply {
            topMargin = dp(2)
        })

        bottomRow.addView(valuesArea, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ))
        bottomRow.addView(waveArea, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ).apply {
            leftMargin = dp(8)
        })

        panel.addView(bottomRow)
        card.addView(panel)
        return card
    }
    private fun setDrawerOpen(open: Boolean) {
        if (drawerOpen == open) return

        drawerOpen = open
        drawerScrim.animate().cancel()
        drawerPanel.animate().cancel()

        if (open) {
            drawerToggleButton.visibility = View.GONE
            drawerScrim.visibility = View.VISIBLE
            drawerScrim.alpha = 0f

            drawerScrim.animate()
                .alpha(1f)
                .setDuration(180)
                .start()

            drawerPanel.animate()
                .translationX(0f)
                .setDuration(220)
                .start()
        } else {
            drawerToggleButton.visibility = View.VISIBLE
            drawerScrim.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction {
                    drawerScrim.visibility = View.GONE
                }
                .start()

            drawerPanel.animate()
                .translationX(drawerPanel.width.toFloat())
                .setDuration(220)
                .start()
        }
    }
    private fun createBottomPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(m3SurfaceContainerLow, dp(20))
        }

        panel.addView(text("センサー計測", 13f, true).apply {
            setTextColor(m3Primary)
            setPadding(0, 0, 0, dp(8))
        })

        sensorStatusText = text("センサー初期化中", 12f)
        panel.addView(sensorStatusText)

        sampleRateText = text("サンプル: -- Hz", 12f).apply {
            setTextColor(m3OnSurfaceVariant)
        }
        panel.addView(sampleRateText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(25)
        ))

        calibrationText = text("静止時ノイズ: --", 11f).apply {
            setTextColor(m3OnSurfaceVariant)
        }
        panel.addView(calibrationText)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        measureButton = createMaterialButton("計測開始", ButtonTone.PRIMARY).apply {
            setOnClickListener {
                if (isMeasuring) stopMeasurement() else startMeasurement()
            }
        }
        recordButton = createMaterialButton("CSV記録開始", ButtonTone.OUTLINED).apply {
            setOnClickListener {
                if (csvRecorder.isRecording) stopRecording() else startRecording()
            }
        }
        val listButton = createMaterialButton("記録一覧", ButtonTone.TONAL).apply {
            setOnClickListener { showRecordingList() }
        }
        buttons.addView(measureButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            rightMargin = dp(3)
        })
        buttons.addView(recordButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        })
        buttons.addView(listButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(3)
        })
        panel.addView(buttons)

        panel.addView(createAutoRecordingControls(),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )
        return panel
    }
    private fun createAutoRecordingControls(): LinearLayout {
        val levels = IntensityClass.values()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(8))
            background = roundedBackground(m3SurfaceContainer, dp(16))
        }

        container.addView(text("自動記録", 13f, true).apply {
            setTextColor(m3Primary)
            setPadding(dp(4), 0, 0, dp(8))
        })

        autoStartLabel = text(
            "開始震度: ${autoStartIntensity.label}",
            12f,
            true
        ).apply {
            setTextColor(m3OnSurface)
            setPadding(dp(4), dp(2), 0, 0)
        }
        container.addView(autoStartLabel)

        container.addView(text("終了震度より高い階級", 10f).apply {
            setTextColor(m3OnSurfaceVariant)
            setPadding(dp(4), 0, 0, 0)
        })

        autoStartSlider = Slider(this).apply {
            valueFrom = 1f
            valueTo = levels.lastIndex.toFloat()
            stepSize = 1f
            value = autoStartIntensity.ordinal.toFloat()
            contentDescription = "自動記録の開始震度"
        }
        container.addView(autoStartSlider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        autoStopLabel = text(
            "終了震度: ${autoStopIntensity.label}",
            12f,
            true
        ).apply {
            setTextColor(m3OnSurface)
            setPadding(dp(4), dp(5), 0, 0)
        }
        container.addView(autoStopLabel)

        container.addView(text("開始震度より低い階級", 10f).apply {
            setTextColor(m3OnSurfaceVariant)
            setPadding(dp(4), 0, 0, 0)
        })

        autoStopSlider = Slider(this).apply {
            valueFrom = 0f
            valueTo = (levels.lastIndex - 1).toFloat()
            stepSize = 1f
            value = autoStopIntensity.ordinal.toFloat()
            contentDescription = "自動記録の終了震度"
        }
        container.addView(autoStopSlider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        autoStartSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingAutoSliders) return@addOnChangeListener

            updatingAutoSliders = true
            try {
                autoStartIntensity = levels[
                    value.toInt().coerceIn(1, levels.lastIndex)
                ]

                if (autoStopIntensity.ordinal >= autoStartIntensity.ordinal) {
                    autoStopIntensity = levels[autoStartIntensity.ordinal - 1]
                    autoStopSlider.value = autoStopIntensity.ordinal.toFloat()
                }

                updateAutoRecordingLabels()
                saveAutoRecordingSettings()
            } finally {
                updatingAutoSliders = false
            }
        }

        autoStopSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingAutoSliders) return@addOnChangeListener

            updatingAutoSliders = true
            try {
                autoStopIntensity = levels[
                    value.toInt().coerceIn(0, levels.lastIndex - 1)
                ]

                if (autoStopIntensity.ordinal >= autoStartIntensity.ordinal) {
                    autoStartIntensity = levels[autoStopIntensity.ordinal + 1]
                    autoStartSlider.value = autoStartIntensity.ordinal.toFloat()
                }

                updateAutoRecordingLabels()
                saveAutoRecordingSettings()
            } finally {
                updatingAutoSliders = false
            }
        }

        return container
    }

    private fun updateAutoRecordingLabels() {
        autoStartLabel.text = "開始震度: ${autoStartIntensity.label}"
        autoStopLabel.text = "終了震度: ${autoStopIntensity.label}"
    }

    private fun saveAutoRecordingSettings() {
        getSharedPreferences("auto_recording", MODE_PRIVATE)
            .edit()
            .putInt("start_intensity", autoStartIntensity.ordinal)
            .putInt("stop_intensity", autoStopIntensity.ordinal)
            .apply()
    }

    private fun showResetSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("設定を初期化")
            .setMessage(
                "ブラウザのURL、分割表示・分割比率、自動記録の開始・終了震度を初期値に戻します。\n\nCSV記録ファイルと記録履歴は削除しません。"
            )
            .setNegativeButton("キャンセル", null)
            .setPositiveButton("初期化") { _, _ ->
                resetAppSettings()
            }
            .show()
    }

    private fun resetAppSettings() {
        // 設定情報だけを消去。CSVファイルには触れない。
        getSharedPreferences("browser_settings", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        getSharedPreferences("auto_recording", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        // ブラウザURLを初期化
        primaryBrowserUrlInput.setText("")
        secondaryBrowserUrlInput.setText("")

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }

        if (::secondaryWebView.isInitialized) {
            secondaryWebView.stopLoading()
            secondaryWebView.loadUrl("about:blank")
        }

        // 分割設定を初期値に戻す
        topSplitRatio = 50f
        setSplitEnabled(false)
        splitRatioSlider.value = 50f
        splitRatioLabel.text = "上段 50% / 下段 50%"

        // 自動記録の震度条件を初期値に戻す
        autoStartIntensity = IntensityClass.LEVEL_2
        autoStopIntensity = IntensityClass.LEVEL_0

        updatingAutoSliders = true
        try {
            autoStartSlider.value = autoStartIntensity.ordinal.toFloat()
            autoStopSlider.value = autoStopIntensity.ordinal.toFloat()
        } finally {
            updatingAutoSliders = false
        }

        updateAutoRecordingLabels()

        // 初期値を保存
        saveAutoRecordingSettings()

        getSharedPreferences("browser_settings", MODE_PRIVATE)
            .edit()
            .putFloat("split_ratio", 50f)
            .putBoolean("split_enabled", false)
            .remove("primary_url")
            .remove("secondary_url")
            .apply()

        showMessage("設定を初期化しました。CSV記録は保持されています。")
    }

    private enum class ButtonTone { PRIMARY, TONAL, OUTLINED, TEXT }

    private fun createMaterialButton(label: String, tone: ButtonTone): MaterialButton {
        val fillColor = when (tone) {
            ButtonTone.PRIMARY -> m3Primary
            ButtonTone.TONAL -> m3PrimaryContainer
            ButtonTone.OUTLINED -> m3SurfaceContainerHigh
            ButtonTone.TEXT -> Color.TRANSPARENT
        }
        val contentColor = when (tone) {
            ButtonTone.PRIMARY -> m3OnPrimary
            ButtonTone.TONAL -> m3OnPrimaryContainer
            ButtonTone.OUTLINED -> m3OnSurface
            ButtonTone.TEXT -> m3Primary
        }
        val outlined = tone == ButtonTone.OUTLINED

        return MaterialButton(this).apply {
            text = label
            typeface = appTypeface
            textSize = 13f
            isAllCaps = false
            setCornerRadius(dp(14))
            setInsetTop(0)
            setInsetBottom(0)
            minimumHeight = dp(44)
            backgroundTintList = ColorStateList.valueOf(fillColor)
            setTextColor(contentColor)
            setStrokeWidth(if (outlined) dp(1) else 0)
            setStrokeColor(ColorStateList.valueOf(
                if (outlined) m3Outline else Color.TRANSPARENT
            ))
            setRippleColor(ColorStateList.valueOf(Color.argb(36, 190, 210, 255)))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = roundedBackground(m3SurfaceContainerLow, dp(16))
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        params.setMargins(dp(3), dp(3), dp(3), dp(3))
        layoutParams = params
    }

    private fun weightedCardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun label(value: String): TextView = text(value, 10f, false).apply {
        setTextColor(m3OnSurfaceVariant)
    }

    private fun text(value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setLineSpacing(0f, 1f)
            setTextColor(m3OnSurface)
            typeface = Typeface.create(
                appTypeface,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), m3OutlineVariant)
        }

    private fun startMeasurement() {
        if (!measurementEngine.isAvailable) {
            sensorStatusText.text = "加速度センサーが利用できません"
            return
        }
        lastUiUpdateNanos = 0L
        lastHistoryUiUpdateNanos = 0L
        lastSlowUiUpdateNanos = 0L
        oneSecondMaxGalBits.set(java.lang.Double.doubleToRawLongBits(0.0))
        calibrationMonitor.reset()
        val started = measurementEngine.start()
        if (started) {
            isMeasuring = true
            sensorStatusText.text = "計測中 / ${measurementEngine.describeSensor()}"
            measureButton.text = "計測停止"
        } else {
            sensorStatusText.text = "センサーの開始に失敗しました"
        }
    }
    private fun stopMeasurement() {
        measurementEngine.stop()

        synchronized(recorderLock) {
            csvRecorder.stop()
            manualRecording = false
            autoRecording = false
            belowStopThresholdSinceNanos = 0L
            preTriggerBuffer.clear()
        }

        isMeasuring = false
        lastUiUpdateNanos = 0L
        lastHistoryUiUpdateNanos = 0L
        lastSlowUiUpdateNanos = 0L
        oneSecondMaxGalBits.set(java.lang.Double.doubleToRawLongBits(0.0))

        resetMeasurementDisplay()
    }

    private fun resetMeasurementDisplay() {
        sensorStatusText.text = "\u505C\u6B62\u4E2D"
        measureButton.text = "\u8A08\u6E2C\u958B\u59CB"
        intensityText.text = "--.-"
        intensityClassText.text = "--"
        pgaText.text = "--.- gal"
        sampleRateText.text = "\u30B5\u30F3\u30D7\u30EB: -- Hz"
        calibrationText.text = "\u9759\u6B62\u6642\u30CE\u30A4\u30BA: --"
        intensityColorBar.setColor(Color.GRAY)
        accelerationColorBar.setColor(Color.GRAY)
        historyBand.clear()
        waveformView.clearAll()
    }

    // 30Hz：リアルタイム数値
    private fun updateMeasurementNumbers(sample: ProcessedSample) {
        if (!isMeasuring) return

        intensityText.text = if (sample.rawIntensity.isFinite()) {
            String.format(Locale.US, "%.2f", sample.rawIntensity)
        } else "--"

        pgaText.text = if (sample.combinedGal.isFinite()) {
            String.format(Locale.US, "%.2f gal", sample.combinedGal)
        } else "--.-- gal"
    }

    // 10Hz：震度履歴とXYZ波形
    private fun updateMeasurementHistory(sample: ProcessedSample) {
        if (!isMeasuring) return

        val cls = IntensityClass.fromRawIntensity(sample.rawIntensity)
        historyBand.push(cls)

        waveformView.pushValue("X", sample.filteredXGal.toFloat(), Color.RED)
        waveformView.pushValue("Y", sample.filteredYGal.toFloat(), Color.GREEN)
        waveformView.pushValue("Z", sample.filteredZGal.toFloat(), Color.BLUE)
    }

    // 1Hz：階級バッジと色バー
    private fun updateMeasurementSlow(
        sample: ProcessedSample,
        stats: CalibrationMonitor.Stats,
        pgaWindowMaxGal: Double
    ) {
        if (!isMeasuring) return

        val cls = IntensityClass.fromRawIntensity(sample.rawIntensity)
        val colors = IntensityClassColors.colorsFor(cls)

        intensityClassText.text = cls.label
        intensityClassText.setTextColor(colors.text)
        intensityClassText.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(colors.background)
            cornerRadius = dp(7).toFloat()
            setStroke(dp(2), colors.border)
        }

        intensityColorBar.setColor(
            KyoshinShindoColorMap.colorForIntensity(sample.rawIntensity)
        )

        accelerationColorBar.setColor(
            KyoshinShindoColorMap.colorForPga(pgaWindowMaxGal)
        )

        sampleRateText.text = String.format(
            Locale.US, "サンプル: %.1f Hz", sample.sampleRateHz
        )

        calibrationText.text = String.format(
            Locale.US,
            "静止時ノイズ目安 SD X/Y/Z: %.2f / %.2f / %.2f gal",
            stats.sdX, stats.sdY, stats.sdZ
        )
    }
// CSV再生：30Hz・リアルタイム数値
    private fun updatePlaybackNumbers(
    combinedGal: Double,
    rawIntensity: Double
) {
    intensityText.text = if (rawIntensity.isFinite()) {
        String.format(Locale.US, "%.2f", rawIntensity)
    } else "--"

    pgaText.text = if (combinedGal.isFinite()) {
        String.format(Locale.US, "%.2f gal", combinedGal)
    } else "--.-- gal"
}

// CSV再生：10Hz・震度履歴とXYZ波形
    private fun updatePlaybackHistory(
    rawIntensity: Double,
    xGal: Double,
    yGal: Double,
    zGal: Double
) {
    val cls = IntensityClass.fromRawIntensity(rawIntensity)
    historyBand.push(cls)

    waveformView.pushValue("X", xGal.toFloat(), Color.RED)
    waveformView.pushValue("Y", yGal.toFloat(), Color.GREEN)
    waveformView.pushValue("Z", zGal.toFloat(), Color.BLUE)
}

// CSV再生：1Hz・階級バッジと色バー
    private fun updatePlaybackSlow(
    rawIntensity: Double,
    pgaWindowMaxGal: Double,
    sampleRateHz: Double
) {
    val cls = IntensityClass.fromRawIntensity(rawIntensity)
    val colors = IntensityClassColors.colorsFor(cls)

    intensityClassText.text = cls.label
    intensityClassText.setTextColor(colors.text)
    intensityClassText.background = android.graphics.drawable.GradientDrawable().apply {
        setColor(colors.background)
        cornerRadius = dp(7).toFloat()
        setStroke(dp(2), colors.border)
    }

    intensityColorBar.setColor(
        KyoshinShindoColorMap.colorForIntensity(rawIntensity)
    )

    accelerationColorBar.setColor(
        KyoshinShindoColorMap.colorForPga(pgaWindowMaxGal)
    )

    sampleRateText.text = String.format(
        Locale.US, "サンプル: %.1f Hz", sampleRateHz
    )

    calibrationText.text = "CSV再生データ"
}

private fun updateAutomaticRecording(sample: ProcessedSample) {
    // 直近5秒分をリングバッファに保持
    preTriggerBuffer.addLast(sample)

    val cutoffNanos = sample.timestampNanos - 5_000_000_000L
    while (preTriggerBuffer.isNotEmpty() &&
        preTriggerBuffer.peekFirst().timestampNanos < cutoffNanos
    ) {
        preTriggerBuffer.removeFirst()
    }

    // 記録中なら現在のサンプルを保存
    if (csvRecorder.isRecording) {
        csvRecorder.append(sample)
    }

    // 手動記録中は自動判定しない
    if (manualRecording) return

    val currentClass =
        IntensityClass.fromRawIntensity(sample.rawIntensity)

    if (!autoRecording) {
        if (currentClass.ordinal >= autoStartIntensity.ordinal) {
            val file = runCatching {
                csvRecorder.start()
            }.getOrNull() ?: return

            autoRecording = true
            belowStopThresholdSinceNanos = 0L

            // 開始直前の5秒分も保存
            preTriggerBuffer.forEach { bufferedSample ->
                csvRecorder.append(bufferedSample)
            }

            handler.post {
                if (!isDestroyed) {
                    recordButton.text = "自動記録中"
                    showMessage("自動記録開始: ${file.name}")
                }
            }
        }
    } else {
        if (currentClass.ordinal <= autoStopIntensity.ordinal) {
            if (belowStopThresholdSinceNanos == 0L) {
                belowStopThresholdSinceNanos = sample.timestampNanos
            } else if (
                sample.timestampNanos - belowStopThresholdSinceNanos >=
                10_000_000_000L
            ) {
                csvRecorder.stop()
                autoRecording = false
                belowStopThresholdSinceNanos = 0L

                handler.post {
                    if (!isDestroyed) {
                        recordButton.text = "CSV記録開始"
                        showMessage("自動記録を停止しました")
                    }
                }
            }
        } else {
            belowStopThresholdSinceNanos = 0L
        }
    }
}
    private fun startRecording() {
        if (!isMeasuring) {
            showMessage("CSV記録には先に計測を開始してください")
            return
        }

        val file = runCatching {
            synchronized(recorderLock) {
                val started = csvRecorder.start()
                if (started != null) {
                    manualRecording = true
                    autoRecording = false
                    belowStopThresholdSinceNanos = 0L
                }
                started
            }
        }.getOrNull()

        if (file != null) {
            recordButton.text = "記録停止"
            showMessage("記録開始: ${file.name}")
        } else {
            showMessage("CSV記録を開始できませんでした")
        }
    }

    private fun stopRecording() {
        synchronized(recorderLock) {
            csvRecorder.stop()
            manualRecording = false
            autoRecording = false
            belowStopThresholdSinceNanos = 0L
        }

        recordButton.text = "CSV記録開始"
        showMessage("CSV記録を停止しました")
    }

    private fun showRecordingList() {
        csvHistoryVisible = true

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(m3Background)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        // ヘッダーカード
        val headerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(13))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#152239"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#3A527D"))
            }
        }

        headerCard.addView(text("計測記録一覧", 20f, true).apply {
            setTextColor(m3OnSurface)
        })

        headerCard.addView(text(
            "記録をタップするとCSVを書き出せます。右端の削除ボタンから個別に削除できます。",
            12f
        ).apply {
            setTextColor(m3OnSurfaceVariant)
            setPadding(0, dp(7), 0, dp(10))
        })

        val statusText = text("読み込み中…", 13f, true).apply {
            setTextColor(m3Primary)
        }
        csvHistoryStatusText = statusText
        headerCard.addView(statusText)

        screen.addView(headerCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // スクロールする記録一覧
        val listContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(3), dp(1), dp(12))
        }
        csvHistoryListContent = listContent

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(
                listContent,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        screen.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ).apply {
            topMargin = dp(9)
        })

        // 下部ボタン
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        footer.addView(
            createMaterialButton("更新", ButtonTone.TONAL).apply {
                setOnClickListener { loadRecordingList() }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                rightMargin = dp(5)
            }
        )

        footer.addView(
            createMaterialButton("戻る", ButtonTone.OUTLINED).apply {
                setOnClickListener { returnToMainScreen() }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                leftMargin = dp(5)
            }
        )

        screen.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(46)
        ).apply {
            topMargin = dp(8)
        })

        setContentView(screen)
        hideSystemBars()
        loadRecordingList()
    }

    private fun loadRecordingList(successMessage: String? = null) {
        val statusView = csvHistoryStatusText ?: return

        statusView.text = if (successMessage == null) {
            "読み込み中…"
        } else {
            "$successMessage　読み込み中…"
        }

        csvHistoryListContent?.removeAllViews()

        csvHistoryExecutor.execute {
            val result = runCatching {
                synchronized(recorderLock) {
                    csvRecorder.listRecordingsWithStats()
                }
            }

            runOnUiThread {
                if (isDestroyed || isFinishing || !csvHistoryVisible) {
                    return@runOnUiThread
                }

                val currentStatus = csvHistoryStatusText ?: return@runOnUiThread

                if (result.isFailure) {
                    currentStatus.text = "記録の読み込みに失敗しました"
                    return@runOnUiThread
                }

                val recordings = result.getOrNull() ?: emptyList()

                val countMessage = if (recordings.isEmpty()) {
                    "記録はありません"
                } else {
                    "${recordings.size}件の記録があります（新しい日付順）"
                }

                currentStatus.text = if (successMessage == null) {
                    countMessage
                } else {
                    "$successMessage　$countMessage"
                }

                renderRecordingList(recordings)
            }
        }
    }

    private fun renderRecordingList(
        recordings: List<CsvRecorder.RecordingInfo>
    ) {
        val listContent = csvHistoryListContent ?: return
        listContent.removeAllViews()

        if (recordings.isEmpty()) {
            listContent.addView(text(
                "保存された計測記録はありません",
                13f
            ).apply {
                setTextColor(m3OnSurfaceVariant)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(36), dp(12), dp(36))
            })
            return
        }

        val grouped = recordings.groupBy {
            formatHistoryTime(recordingTimestampMillis(it), "yyyy/MM/dd")
        }

        grouped.keys.sortedDescending().forEach { date ->
            val dateHeader = text(date, 14f, true).apply {
                setTextColor(Color.parseColor("#E4ECFA"))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1B2D4D"))
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), Color.parseColor("#3A527D"))
                }
            }

            listContent.addView(dateHeader, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(9)
                bottomMargin = dp(6)
            })

            grouped[date]
                .orEmpty()
                .sortedByDescending { recordingTimestampMillis(it) }
                .forEach { info ->
                    listContent.addView(
                        createRecordingCard(info),
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            bottomMargin = dp(8)
                        }
                    )
                }
        }
    }

    private fun recordingTimestampMillis(
        info: CsvRecorder.RecordingInfo
    ): Long {
        val startTime = info.startDateTime?.let { value ->
            runCatching {
                java.time.OffsetDateTime.parse(value)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }

        return startTime ?: info.lastModified
    }

    private fun formatHistoryTime(
        timestampMillis: Long,
        pattern: String
    ): String {
        return java.text.SimpleDateFormat(pattern, Locale.JAPAN)
            .format(java.util.Date(timestampMillis))
    }

    private fun recordingCardBackground(cls: IntensityClass?): Int {
        return Color.parseColor(
            when (cls) {
                IntensityClass.LEVEL_0 -> "#304765"
                IntensityClass.LEVEL_1 -> "#1C465A"
                IntensityClass.LEVEL_2 -> "#1F3866"
                IntensityClass.LEVEL_3 -> "#3C6748"
                IntensityClass.LEVEL_4 -> "#716614"
                IntensityClass.LEVEL_5_LOWER -> "#76502D"
                IntensityClass.LEVEL_5_UPPER -> "#713A27"
                IntensityClass.LEVEL_6_LOWER -> "#792D29"
                IntensityClass.LEVEL_6_UPPER -> "#5C202C"
                IntensityClass.LEVEL_7 -> "#4F286F"
                null -> "#304765"
            }
        )
    }

    private fun createRecordingCard(
        info: CsvRecorder.RecordingInfo
    ): LinearLayout {
        val cls = IntensityClass.values().firstOrNull {
            it.label == info.maxIntensityClass
        }

        val badgeColors = cls?.let {
            IntensityClassColors.colorsFor(it)
        }

        val intensityText = info.maxMeasuredIntensity
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.2f", it) }
            ?: "--"

        val accelerationText = info.maxFilteredAccelerationGal
            ?.takeIf { it.isFinite() }
            ?.let { String.format(Locale.US, "%.1f", it) }
            ?: "--"

        val timestamp = recordingTimestampMillis(info)
        val dateTimeText = formatHistoryTime(
            timestamp,
            "yyyy/M/d HH:mm:ss"
        )

        val fileSizeKb = String.format(
            Locale.US,
            "%.1f",
            info.sizeBytes / 1024.0
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(9), dp(8), dp(9))
            background = GradientDrawable().apply {
                setColor(recordingCardBackground(cls))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#526686"))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { requestCsvExport(info) }
        }

        // 左側64dp階級バッジ
        val badge = TextView(this).apply {
            text = cls?.label ?: "--"
            textSize = 21f
            typeface = Typeface.create(appTypeface, Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(badgeColors?.text ?: Color.WHITE)
            background = GradientDrawable().apply {
                setColor(
                    badgeColors?.background ?: Color.parseColor("#526686")
                )
                cornerRadius = dp(11).toFloat()
                setStroke(
                    dp(1),
                    badgeColors?.border ?: Color.parseColor("#AAB7C8")
                )
            }
        }

        card.addView(badge, LinearLayout.LayoutParams(dp(64), dp(64)))

        // 中央の2行
        val dataColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(5), 0)
        }

        dataColumn.addView(text(
            dateTimeText,
            12.5f,
            true
        ).apply {
            setTextColor(Color.parseColor("#DDE7F6"))
        })

        dataColumn.addView(text(
            "計測震度 $intensityText",
            15f,
            true
        ).apply {
            setTextColor(Color.parseColor("#FFFFFF"))
            setPadding(0, dp(3), 0, 0)
        })

        dataColumn.addView(text(
            "フィルター後加速度 ${accelerationText} gal",
            12f
        ).apply {
            setTextColor(Color.parseColor("#E1E8F2"))
            setPadding(0, dp(2), 0, 0)
        })

        dataColumn.addView(text(
            "サンプル数 ${info.sampleCount} ・ ${fileSizeKb} KB",
            11.5f
        ).apply {
            setTextColor(Color.parseColor("#D4DCE8"))
            setPadding(0, dp(3), 0, 0)
        })

        card.addView(dataColumn, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        // 右側の再生・削除ボタン
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        val playButton = MaterialButton(this).apply {
            text = "再生"
            typeface = appTypeface
            isAllCaps = false
            textSize = 12f
            cornerRadius = dp(9)
            insetTop = 0
            insetBottom = 0
            minimumHeight = dp(34)
            setPadding(dp(2), 0, dp(2), 0)
            backgroundTintList = ColorStateList.valueOf(
                Color.parseColor("#8FA7E8")
            )
            setTextColor(Color.parseColor("#18243B"))
            setOnClickListener { playRecordingFromHistory(info) }
        }

        actions.addView(playButton, LinearLayout.LayoutParams(
            dp(62),
            dp(34)
        ))

        val deleteButton = MaterialButton(this).apply {
            text = "削除"
            typeface = appTypeface
            isAllCaps = false
            textSize = 12f
            cornerRadius = dp(9)
            insetTop = 0
            insetBottom = 0
            minimumHeight = dp(32)
            setPadding(dp(2), 0, dp(2), 0)
            backgroundTintList = ColorStateList.valueOf(
                Color.parseColor("#493943")
            )
            setTextColor(Color.parseColor("#F5E9EC"))
            contentDescription = "この記録を削除"
            setOnClickListener { confirmDeleteRecording(info) }
        }

        actions.addView(deleteButton, LinearLayout.LayoutParams(
            dp(62),
            dp(32)
        ).apply {
            topMargin = dp(5)
        })

        card.addView(actions)

        return card
    }

    private fun requestCsvExport(info: CsvRecorder.RecordingInfo) {
        csvHistoryStatusText?.text = "保存先を選択してください"

        csvHistoryExecutor.execute {
            val exists = info.file.isFile

            runOnUiThread {
                if (isDestroyed || isFinishing || !csvHistoryVisible) {
                    return@runOnUiThread
                }

                if (!exists) {
                    csvHistoryStatusText?.text =
                        "この記録は既に削除されています。更新してください。"
                    return@runOnUiThread
                }

                selectedExportFile = info.file
                createCsvDocument.launch(info.file.name)
            }
        }
    }

    private fun playRecordingFromHistory(info: CsvRecorder.RecordingInfo) {
        csvHistoryStatusText?.text = "再生準備中…"

        csvHistoryExecutor.execute {
            val exists = info.file.isFile

            runOnUiThread {
                if (isDestroyed || isFinishing || !csvHistoryVisible) {
                    return@runOnUiThread
                }

                if (!exists) {
                    csvHistoryStatusText?.text =
                        "この記録は既に削除されています。更新してください。"
                    return@runOnUiThread
                }

                returnToMainScreen()
                startPlayback(info.file)
            }
        }
    }

    private fun confirmDeleteRecording(info: CsvRecorder.RecordingInfo) {
        AlertDialog.Builder(this)
            .setTitle("記録の削除")
            .setMessage(
                "この記録を削除しますか？\nCSVと付随する記録情報を削除します。この操作は元に戻せません。"
            )
            .setPositiveButton("削除") { _, _ ->
                csvHistoryStatusText?.text = "削除中…"

                csvHistoryExecutor.execute {
                    val existedBefore = info.file.isFile
                    val deleted = if (existedBefore) {
                        runCatching {
                            synchronized(recorderLock) {
                                csvRecorder.deleteRecording(info.file)
                            }
                        }.getOrDefault(false)
                    } else {
                        false
                    }

                    val missingAfter = !info.file.isFile

                    runOnUiThread {
                        if (isDestroyed || isFinishing || !csvHistoryVisible) {
                            return@runOnUiThread
                        }

                        when {
                            !existedBefore || (!deleted && missingAfter) -> {
                                csvHistoryStatusText?.text =
                                    "この記録は既に削除されています。更新してください。"
                            }

                            deleted -> {
                                loadRecordingList("削除しました")
                            }

                            else -> {
                                csvHistoryStatusText?.text = "削除に失敗しました"
                            }
                        }
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun returnToMainScreen() {
        if (!csvHistoryVisible || !::mainRoot.isInitialized) return

        csvHistoryVisible = false
        csvHistoryStatusText = null
        csvHistoryListContent = null

        setContentView(mainRoot)
        hideSystemBars()
    }
    private fun startPlayback(file: File) {
        if (isMeasuring) {
            showMessage("再生するには計測を停止してください")
            return
        }

        if (csvRecorder.isRecording) {
            showMessage("CSV記録を停止してから再生してください")
            return
        }

        stopPlayback()

        val data = runCatching {
            synchronized(recorderLock) {
                csvRecorder.readPlaybackData(file)
            }
        }.getOrElse {
            showMessage("CSVの読み込みに失敗しました: ${it.message}")
            return
        }

        if (data.samples.isEmpty()) {
            showMessage("再生できるデータがありません")
            return
        }

        val statusText = TextView(this).apply {
            text = "再生中"
            textSize = 16f
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val progressText = TextView(this).apply {
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }

        val playbackWave = WaveformView(this).apply {
            setCapacity(160)
            setSeparatedSeries(true)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(statusText)
            addView(
                playbackWave,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(150)
                )
            )
            addView(progressText)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("CSV再生")
            .setView(layout)
            .setNegativeButton("閉じる", null)
            .create()

        playbackDialog = dialog
        // 再生開始時に観測カードを初期化
        resetMeasurementDisplay()
        sensorStatusText.text = "CSV再生中"

        val startNanos = SystemClock.elapsedRealtimeNanos()
        val totalNanos = data.samples.last().relativeNanos

        var sampleIndex = 0
        var lastNumberUpdateNanos = -1L
        var lastHistoryUpdateNanos = -1L
        var lastSlowUpdateNanos = -1L
        var pgaWindowMaxGal = 0.0

        val runnable = object : Runnable {
            override fun run() {
                if (playbackDialog !== dialog || !dialog.isShowing) {
                    return
                }

                val elapsedNanos =
                    SystemClock.elapsedRealtimeNanos() - startNanos

                var latestSample: CsvRecorder.PlaybackSample? = null

                // 再生時刻までに到達したサンプルをすべて処理
                while (
                    sampleIndex < data.samples.size &&
                    data.samples[sampleIndex].relativeNanos <= elapsedNanos
                ) {
                    val sample = data.samples[sampleIndex]

                    val combinedGal = kotlin.math.sqrt(
                        sample.xGal * sample.xGal +
                        sample.yGal * sample.yGal +
                        sample.zGal * sample.zGal
                    )

                    if (combinedGal.isFinite() && combinedGal > pgaWindowMaxGal) {
                        pgaWindowMaxGal = combinedGal
                    }

                    latestSample = sample
                    sampleIndex++
                }

                val sample = latestSample

                if (sample != null) {
                    val combinedGal = kotlin.math.sqrt(
                        sample.xGal * sample.xGal +
                        sample.yGal * sample.yGal +
                        sample.zGal * sample.zGal
                    )

                    val playbackIntensity = if (
                        combinedGal.isFinite() && combinedGal > 0.0
                    ) {
                        2.0 * kotlin.math.log10(combinedGal) + 0.94
                    } else {
                        0.0
                    }

                    // 数値表示：最大30Hz
                    if (
                        lastNumberUpdateNanos < 0L ||
                        elapsedNanos - lastNumberUpdateNanos >= 33_333_333L
                    ) {
                        lastNumberUpdateNanos = elapsedNanos
                        updatePlaybackNumbers(combinedGal, playbackIntensity)
                    }

                    // 履歴・波形：最大10Hz
                    if (
                        lastHistoryUpdateNanos < 0L ||
                        elapsedNanos - lastHistoryUpdateNanos >= 100_000_000L
                    ) {
                        lastHistoryUpdateNanos = elapsedNanos

                        updatePlaybackHistory(
                            playbackIntensity,
                            sample.xGal,
                            sample.yGal,
                            sample.zGal
                        )

                        playbackWave.pushValue("X", sample.xGal.toFloat(), Color.RED)
                        playbackWave.pushValue("Y", sample.yGal.toFloat(), Color.GREEN)
                        playbackWave.pushValue("Z", sample.zGal.toFloat(), Color.BLUE)
                    }

                    // 階級バッジ・色バー：最大1Hz
                    if (
                        lastSlowUpdateNanos < 0L ||
                        elapsedNanos - lastSlowUpdateNanos >= 1_000_000_000L
                    ) {
                        lastSlowUpdateNanos = elapsedNanos

                        updatePlaybackSlow(
                            playbackIntensity,
                            pgaWindowMaxGal,
                            data.sampleRateHz
                        )

                        pgaWindowMaxGal = 0.0
                    }
                }

                val currentSeconds = elapsedNanos / 1_000_000_000.0
                val totalSeconds = totalNanos / 1_000_000_000.0

                progressText.text = String.format(
                    Locale.US,
                    "%.1f / %.1f 秒   %.1f Hz",
                    currentSeconds.coerceAtMost(totalSeconds),
                    totalSeconds,
                    data.sampleRateHz
                )

                if (sampleIndex >= data.samples.size) {
                    statusText.text = "再生終了"
                    playbackRunnable = null
                    return
                }

                handler.postDelayed(this, 16L)
            }
        }

        playbackRunnable = runnable

        dialog.setOnDismissListener {
            stopPlayback()
        }

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            ?.setOnClickListener {
                dialog.dismiss()
            }

        handler.post(runnable)
    }

        private fun stopPlayback() {
        playbackRunnable?.let {
            handler.removeCallbacks(it)
        }
        playbackRunnable = null

        playbackDialog?.let {
            if (it.isShowing) {
                it.dismiss()
            }
        }
        playbackDialog = null
        resetMeasurementDisplay()
    }
    private fun showMessage(message: String) {
        if (!isFinishing && !isDestroyed) {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError() {
        if (!::errorLayout.isInitialized) return
        errorLayout.visibility = View.VISIBLE
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, 15000)
    }

    private fun hideError() {
        if (!::errorLayout.isInitialized) return
        errorLayout.visibility = View.GONE
        handler.removeCallbacks(retryRunnable)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun saveBrowserSettings() {
        val editor = getSharedPreferences("browser_settings", MODE_PRIVATE)
            .edit()
            .putFloat("split_ratio", topSplitRatio)
            .putBoolean("split_enabled", splitEnabled)

        if (::webView.isInitialized) {
            val url = webView.url
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

            if (url != null) {
                editor.putString("primary_url", url)
            } else {
                editor.remove("primary_url")
            }
        }

        if (::secondaryWebView.isInitialized) {
            val url = secondaryWebView.url
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

            if (url != null) {
                editor.putString("secondary_url", url)
            } else {
                editor.remove("secondary_url")
            }
        }

        editor.apply()
    }

    override fun onPause() {
        saveBrowserSettings()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) {
            val monitorState = Bundle()
            webView.saveState(monitorState)
            outState.putBundle("monitor_webview_state", monitorState)
        }
        if (::secondaryWebView.isInitialized) {
            val secondaryState = Bundle()
            secondaryWebView.saveState(secondaryState)
            outState.putBundle("secondary_webview_state", secondaryState)
        }
        outState.putBoolean("split_enabled", splitEnabled)
        outState.putFloat("split_ratio", topSplitRatio)
        outState.putBoolean("csv_history_visible", csvHistoryVisible)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (csvHistoryVisible) {
            returnToMainScreen()
            return
        }

        if (::secondaryWebView.isInitialized &&
            secondaryWebView.visibility == View.VISIBLE &&
            secondaryWebView.canGoBack()
        ) {
            secondaryWebView.goBack()
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopPlayback()
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        csvHistoryExecutor.shutdown()
        if (::measurementEngine.isInitialized) measurementEngine.stop()
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        synchronized(recorderLock) { csvRecorder.stop() }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        if (::secondaryWebView.isInitialized) {
            secondaryWebView.stopLoading()
            secondaryWebView.destroy()
        }
        super.onDestroy()
    }
}




