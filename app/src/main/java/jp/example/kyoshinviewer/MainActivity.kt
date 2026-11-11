package jp.example.kyoshinviewer

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.appcompat.app.AppCompatActivity
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
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

    private val handler = Handler(Looper.getMainLooper())
    private val recorderLock = Any()
    private val csvRecorder by lazy { CsvRecorder(this) }
    private val calibrationMonitor = CalibrationMonitor(200)
    private val preTriggerBuffer = java.util.ArrayDeque<ProcessedSample>()

    private var autoRecording = false
    private var manualRecording = false

    private var autoStartIntensity = IntensityClass.LEVEL_2
    private var autoStopIntensity = IntensityClass.LEVEL_0

    private var belowStopThresholdSinceNanos = 0L
    private var selectedExportFile: File? = null
    private var playbackRunnable: Runnable? = null
    private var playbackDialog: AlertDialog? = null
    private var networkLost = false
    private var isMeasuring = false
    private var isDestroyed = false

    @Volatile
    private var lastUiUpdateNanos = 0L

    private val monitorPageSource: MonitorPageSource = KwatchWebPageSource()

    private val createCsvDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
            val source = selectedExportFile
            selectedExportFile = null
            if (uri == null || source == null) return@registerForActivityResult
            val exported = runCatching {
                synchronized(recorderLock) {
                    csvRecorder.exportFileTo(source, uri)
                }
            }.getOrDefault(false)
            showMessage(if (exported) "CSVを書き出しました" else "CSVの書き出しに失敗しました")
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

        val outer = FrameLayout(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 14, 24))
        }

        createTopPanel().also {
            page.addView(it, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        webView = WebView(this).apply {
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
                    hideError()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    val host = request?.url?.host.orEmpty()
                    if (request?.isForMainFrame == true ||
                        host.contains("kmoni.bosai.go.jp") ||
                        host.contains("lmoni.bosai.go.jp")
                    ) showError()
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    val host = request?.url?.host.orEmpty()
                    if ((request?.isForMainFrame == true ||
                            host.contains("kmoni.bosai.go.jp") ||
                            host.contains("lmoni.bosai.go.jp")) &&
                        (errorResponse?.statusCode ?: 0) >= 400
                    ) showError()
                }
            }
        }

        page.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        page.addView(createBottomPanel(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }
        scrollView.addView(page, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        outer.addView(scrollView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        errorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(25, 25, 30))
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            addView(TextView(context).apply {
                text = "通信エラー"
                textSize = 20f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "通信状態または接続先サーバーを確認しています。"
                textSize = 14f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(24))
            })
            addView(Button(context).apply {
                text = "再読み込み"
                setOnClickListener { webView.reload() }
            })
        }
        outer.addView(errorLayout, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

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
                if (lastUiUpdateNanos == 0L ||
                    sample.timestampNanos - lastUiUpdateNanos >= 200_000_000L
                ) {
                    lastUiUpdateNanos = sample.timestampNanos
                    if (!isDestroyed) {
                        handler.post {
                            if (!isDestroyed) updateMeasurementDisplay(sample, stats)
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
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(monitorPageSource.startPageUrl)
        }
    }

    private fun createTopPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBackground(Color.rgb(40, 60, 100), dp(28))
            elevation = dp(4).toFloat()
            clipToOutline = true
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
                setTextColor(Color.rgb(192, 200, 216))
            }
        )

        intensityClassText = text("--", 32f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(Color.rgb(100, 130, 170), dp(7))
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
            setCapacity(36)
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

        // 下段：左に計測値、右にXYZ波形
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
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
                setTextColor(Color.rgb(192, 200, 216))
            }, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueView)
            valuesArea.addView(row)

            return IntensityColorBarView(this@MainActivity).apply {
                setColor(Color.GRAY)
                valuesArea.addView(this, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(13)
                ).apply {
                    topMargin = dp(4)
                })
            }
        }

        intensityText = text("--.-", 22f, true)
        intensityText.gravity = Gravity.END
        intensityColorBar = addValueRow("リアルタイム震度:", intensityText)

        pgaText = text("--.- gal", 22f, true)
        pgaText.gravity = Gravity.END
        accelerationColorBar = addValueRow("リアルタイム加速度:", pgaText)

        val waveArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        waveArea.addView(label("XYZ 加速度波形（フィルタ後 / gal）"))

        waveformView = WaveformView(this).apply {
            setCapacity(120)
            setSeparatedSeries(true)
        }
        waveArea.addView(waveformView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(80)
        ).apply {
            topMargin = dp(4)
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
        return panel
    }
    private fun createBottomPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(7))
            setBackgroundColor(Color.rgb(18, 27, 40))
        }

        sensorStatusText = text("センサー初期化中", 11f)
        panel.addView(sensorStatusText)

        sampleRateText = text("サンプル: -- Hz", 12f)
        panel.addView(sampleRateText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(25)
        ))

        calibrationText = text("静止時ノイズ: --", 11f)
        panel.addView(calibrationText)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        measureButton = Button(this).apply {
            text = "計測開始"
            setOnClickListener {
                if (isMeasuring) stopMeasurement() else startMeasurement()
            }
        }
        recordButton = Button(this).apply {
            text = "CSV記録開始"
            setOnClickListener {
                if (csvRecorder.isRecording) stopRecording() else startRecording()
            }
        }
        val listButton = Button(this).apply {
            text = "記録一覧"
            setOnClickListener { showRecordingList() }
        }
        buttons.addView(measureButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        buttons.addView(recordButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        buttons.addView(listButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        panel.addView(buttons)

        val settingsButton = Button(this).apply {
            text = "自動記録設定"
            setOnClickListener { showAutoRecordingSettings() }
        }
        panel.addView(settingsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38)
        ))

        return panel
    }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = roundedBackground(Color.rgb(25, 39, 58), dp(9))
        val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        params.setMargins(dp(3), dp(3), dp(3), dp(3))
        layoutParams = params
    }

    private fun weightedCardParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(2), dp(2), dp(2), dp(2))
        }

    private fun label(value: String): TextView = text(value, 10f, false).apply {
        setTextColor(Color.LTGRAY)
    }

    private fun text(value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.WHITE)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
        }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), Color.argb(70, 210, 225, 255))
        }

    private fun startMeasurement() {
        if (!measurementEngine.isAvailable) {
            sensorStatusText.text = "加速度センサーが利用できません"
            return
        }
        lastUiUpdateNanos = 0L
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
        sensorStatusText.text = "停止中"
        measureButton.text = "計測開始"
        intensityText.text = "--.-"
        intensityClassText.text = "--"
        pgaText.text = "--.- gal"
        sampleRateText.text = "サンプル: -- Hz"
        calibrationText.text = "静止時ノイズ: --"
        intensityColorBar.setColor(Color.GRAY)
        accelerationColorBar.setColor(Color.GRAY)
        historyBand.clear()
        waveformView.clearAll()
    }

    private fun updateMeasurementDisplay(
        sample: ProcessedSample,
        stats: CalibrationMonitor.Stats
    ) {
        if (!isMeasuring) return
        val cls = IntensityClass.fromRawIntensity(sample.rawIntensity)
        val colors = IntensityClassColors.colorsFor(cls)

        intensityText.text = if (sample.rawIntensity.isFinite()) {
            String.format(Locale.US, "%.2f", sample.rawIntensity)
        } else "--.-"

        intensityClassText.text = "震度 ${cls.label}"
        intensityClassText.setTextColor(colors.text)
        intensityClassText.background = roundedBackground(colors.background, dp(7))

        historyBand.push(cls)

        intensityColorBar.setColor(
            KyoshinShindoColorMap.colorForIntensity(sample.rawIntensity)
        )

        pgaText.text = if (sample.combinedGal.isFinite()) {
            String.format(Locale.US, "%.1f gal", sample.combinedGal)
        } else "--.- gal"

        accelerationColorBar.setColor(
            KyoshinShindoColorMap.colorForPga(sample.combinedGal)
        )
        sampleRateText.text = String.format(
            Locale.US, "サンプル: %.1f Hz", sample.sampleRateHz
        )
        calibrationText.text = String.format(
            Locale.US,
            "静止時ノイズ目安 SD X/Y/Z: %.2f / %.2f / %.2f gal",
            stats.sdX, stats.sdY, stats.sdZ
        )

        waveformView.pushValue("X", sample.filteredXGal.toFloat(), Color.rgb(80, 170, 255))
        waveformView.pushValue("Y", sample.filteredYGal.toFloat(), Color.rgb(255, 105, 105))
        waveformView.pushValue("Z", sample.filteredZGal.toFloat(), Color.rgb(110, 220, 150))
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

    private fun showAutoRecordingSettings() {
        val levels = IntensityClass.values()
        val labels = levels.map { it.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("自動記録の開始震度")
            .setSingleChoiceItems(
                labels,
                autoStartIntensity.ordinal
            ) { dialog, which ->
                val selected = levels[which]

                if (selected.ordinal <= autoStopIntensity.ordinal) {
                    showMessage("開始震度は終了震度より高くしてください")
                    return@setSingleChoiceItems
                }

                autoStartIntensity = selected
                dialog.dismiss()
                showAutoStopIntensitySettings()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showAutoStopIntensitySettings() {
        val levels = IntensityClass.values()
        val labels = levels.map { it.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("自動記録の終了震度")
            .setSingleChoiceItems(
                labels,
                autoStopIntensity.ordinal
            ) { dialog, which ->
                val selected = levels[which]

                if (selected.ordinal >= autoStartIntensity.ordinal) {
                    showMessage("終了震度は開始震度より低くしてください")
                    return@setSingleChoiceItems
                }

                autoStopIntensity = selected

                getSharedPreferences("auto_recording", MODE_PRIVATE)
                    .edit()
                    .putInt("start_intensity", autoStartIntensity.ordinal)
                    .putInt("stop_intensity", autoStopIntensity.ordinal)
                    .apply()

                dialog.dismiss()
                showMessage(
                    "自動記録設定: 開始 ${autoStartIntensity.label} / 終了 ${autoStopIntensity.label}"
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
    private fun showRecordingList() {
        val recordings = runCatching {
            synchronized(recorderLock) { csvRecorder.listRecordingsWithStats() }
        }.getOrDefault(emptyList())

        if (recordings.isEmpty()) {
            showMessage("保存済みのCSVはありません")
            return
        }

        val labels = recordings.map { info ->
            val date = info.startDateTime ?: info.file.name
            val intensity = info.maxMeasuredIntensity?.let {
                String.format(Locale.US, "震度相当 %.2f", it)
            } ?: "震度相当 --"
            "$date\n$intensity / ${info.sampleCount} samples"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("記録一覧")
            .setItems(labels) { _, index ->
                showRecordingActions(recordings[index])
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun showRecordingActions(info: CsvRecorder.RecordingInfo) {
        val details = buildString {
            appendLine("サンプル数: ${info.sampleCount}")
            appendLine("ファイルサイズ: ${info.sizeBytes} bytes")
            appendLine("最大震度: ${info.maxIntensityClass}")
            info.startDateTime?.let {
                appendLine("開始日時: $it")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("記録データ")
            .setMessage(details)
            .setPositiveButton("CSV再生") { _, _ ->
                startPlayback(info.file)
            }
            .setNeutralButton("CSVを書き出す") { _, _ ->
                selectedExportFile = info.file
                createCsvDocument.launch(info.file.name)
            }
            .setNegativeButton("削除") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("この記録を削除しますか？")
                    .setPositiveButton("削除") { _, _ ->
                        val deleted = synchronized(recorderLock) {
                            csvRecorder.deleteRecording(info.file)
                        }
                        showMessage(
                            if (deleted) "削除しました"
                            else "削除に失敗しました"
                        )
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
            .show()
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

        val startNanos = SystemClock.elapsedRealtimeNanos()
        val totalNanos = data.samples.last().relativeNanos
        var sampleIndex = 0

        val runnable = object : Runnable {
            override fun run() {
                if (playbackDialog !== dialog || !dialog.isShowing) {
                    return
                }

                val elapsedNanos =
                    SystemClock.elapsedRealtimeNanos() - startNanos

                while (
                    sampleIndex < data.samples.size &&
                    data.samples[sampleIndex].relativeNanos <= elapsedNanos
                ) {
                    val sample = data.samples[sampleIndex]

                    playbackWave.pushValue(
                        "X", sample.xGal.toFloat(), Color.RED
                    )
                    playbackWave.pushValue(
                        "Y", sample.yGal.toFloat(), Color.GREEN
                    )
                    playbackWave.pushValue(
                        "Z", sample.zGal.toFloat(), Color.CYAN
                    )

                    sampleIndex++
                }

                val currentSeconds = elapsedNanos / 1_000_000_000.0
                val totalSeconds = totalNanos / 1_000_000_000.0

                progressText.text = String.format(
                    Locale.US,
                    "%.1f / %.1f 秒    %.1f Hz",
                    currentSeconds.coerceAtMost(totalSeconds),
                    totalSeconds,
                    data.sampleRateHz
                )

                if (sampleIndex >= data.samples.size) {
                    statusText.text = "再生終了"
                    playbackRunnable = null
                    return
                }

                handler.postDelayed(this, 50L)
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

    override fun onSaveInstanceState(outState: Bundle) {
        if (::webView.isInitialized) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        stopPlayback()
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        if (::measurementEngine.isInitialized) measurementEngine.stop()
        if (::networkMonitor.isInitialized) networkMonitor.stop()
        synchronized(recorderLock) { csvRecorder.stop() }
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}






