package jp.example.kyoshinviewer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/** センサーデータの複数系列をリングバッファで表示するチャートView。 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val seriesData = LinkedHashMap<String, FloatArray>()
    private val seriesColors = LinkedHashMap<String, Int>()
    private val writeIndices = HashMap<String, Int>()
    private val filledCounts = HashMap<String, Int>()
    private var capacity = 300
    // XYZ波形など、複数系列をそれぞれ独立した水平位置に配置する表示モード。
    // 振幅の比較は維持しつつ、各系列の基準線を縦方向にずらして重なりを防ぐ。
    private var separatedSeries = false

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#142748")
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50678E")
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2f * density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A527D")
        strokeWidth = density
        style = Paint.Style.STROKE
    }
    private val currentPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = Path()
    private val bounds = RectF()
    // 既存の波形系列色は維持し、グラフの面・目盛り・線端だけをMaterial 3風に整える。
    private val defaultColors = intArrayOf(
        Color.parseColor("#1976D2"), Color.parseColor("#D32F2F"),
        Color.parseColor("#388E3C"), Color.parseColor("#F57C00")
    )

    /** 複数系列を上下に分けて表示する。XYZ波形で使用する。 */
    fun setSeparatedSeries(enabled: Boolean) {
        if (separatedSeries == enabled) return
        separatedSeries = enabled
        invalidate()
    }

    fun setCapacity(n: Int) {
        capacity = n.coerceAtLeast(1)
        seriesData.keys.toList().forEach { key ->
            seriesData[key] = FloatArray(capacity)
            writeIndices[key] = 0
            filledCounts[key] = 0
        }
        invalidate()
    }

    /** nameは系列名。colorは初回登録時だけ適用される。追加処理はO(1)。 */
    fun pushValue(name: String, value: Float, color: Int? = null) {
        val arr = seriesData.getOrPut(name) {
            seriesColors[name] = color ?: defaultColors[seriesColors.size % defaultColors.size]
            writeIndices[name] = 0
            filledCounts[name] = 0
            FloatArray(capacity)
        }
        val idx = writeIndices[name] ?: 0
        arr[idx] = value
        writeIndices[name] = (idx + 1) % arr.size
        filledCounts[name] = minOf((filledCounts[name] ?: 0) + 1, arr.size)
        invalidate()
    }

    fun clearAll() {
        seriesData.keys.toList().forEach { key ->
            seriesData[key] = FloatArray(capacity)
            writeIndices[key] = 0
            filledCounts[key] = 0
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        bounds.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density)
        val outerRadius = 20f * density
        canvas.drawRoundRect(bounds, outerRadius, outerRadius, backgroundPaint)
        canvas.drawRoundRect(bounds, outerRadius, outerRadius, outlinePaint)

        clipPath.reset()
        clipPath.addRoundRect(bounds, outerRadius, outerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val padX = 8f * density
        val padY = 7f * density
        val left = padX
        val right = w - padX
        val top = padY
        val bottom = h - padY
        val plotWidth = right - left
        val plotHeight = bottom - top
        if (plotWidth <= 0f || plotHeight <= 0f) {
            canvas.restore()
            return
        }

        var maxAbs = 1e-6f
        for (arr in seriesData.values) {
            for (v in arr) if (abs(v) > maxAbs) maxAbs = abs(v)
        }

        val entries = seriesData.entries.toList()
        val useSeparatedLayout = separatedSeries && entries.size > 1

        if (useSeparatedLayout) {
            // 各系列のゼロ位置を等間隔に配置する。
            // 振幅は全系列共通のmaxAbsで正規化するため、X/Y/Z間の相対的な
            // 大きさは保たれる。一方、最大振幅を各レーン高さの36%に制限し、
            // 隣接する波形同士の間に十分な空白を確保する。
            val laneHeight = plotHeight / entries.size.toFloat()
            val maxAmplitude = laneHeight * 0.36f

            // 各系列の基準線を波形の背面に描画。レーン境界は描かず、
            // 線が増えすぎない落ち着いた表示にする。
            entries.forEachIndexed { index, _ ->
                val centerY = top + laneHeight * (index + 0.5f)
                axisPaint.alpha = 82
                canvas.drawLine(left, centerY, right, centerY, axisPaint)
            }

            entries.forEachIndexed { seriesIndex, entry ->
                val name = entry.key
                val arr = entry.value
                val filled = filledCounts[name] ?: 0
                if (filled < 2) return@forEachIndexed

                val start = writeIndices[name] ?: 0
                val oldestPos = if (filled < arr.size) 0 else start
                val centerY = top + laneHeight * (seriesIndex + 0.5f)
                val amplitudeScale = maxAmplitude / maxAbs
                linePaint.color = seriesColors[name] ?: Color.WHITE

                var prevX = left
                var prevY = centerY - arr[oldestPos] * amplitudeScale
                for (i in 1 until filled) {
                    val idx = (oldestPos + i) % arr.size
                    val x = left + plotWidth * i / (filled - 1)
                    val y = centerY - arr[idx] * amplitudeScale
                    canvas.drawLine(prevX, prevY, x, y, linePaint)
                    prevX = x
                    prevY = y
                }

                currentPointPaint.color = linePaint.color
                canvas.drawCircle(prevX, prevY, 2.6f * density, currentPointPaint)
            }
        } else {
            // 単一系列のグラフは従来どおり中央基準で表示する。
            // M3チャートらしい薄い水平グリッド。中央線だけ少し強調する。
            for (i in 0..4) {
                axisPaint.alpha = if (i == 2) 100 else 48
                val y = top + plotHeight * i / 4f
                canvas.drawLine(left, y, right, y, axisPaint)
            }

            for ((name, arr) in entries) {
                val filled = filledCounts[name] ?: 0
                if (filled < 2) continue
                val start = writeIndices[name] ?: 0
                val oldestPos = if (filled < arr.size) 0 else start
                linePaint.color = seriesColors[name] ?: Color.WHITE

                var prevX = left
                var prevY = top + plotHeight / 2f - (arr[oldestPos] / maxAbs) * (plotHeight / 2f * 0.88f)
                for (i in 1 until filled) {
                    val idx = (oldestPos + i) % arr.size
                    val x = left + plotWidth * i / (filled - 1)
                    val y = top + plotHeight / 2f - (arr[idx] / maxAbs) * (plotHeight / 2f * 0.88f)
                    canvas.drawLine(prevX, prevY, x, y, linePaint)
                    prevX = x
                    prevY = y
                }

                // 右端の現在点を小さく示し、折れ線の終端を見分けやすくする。
                currentPointPaint.color = linePaint.color
                canvas.drawCircle(prevX, prevY, 2.6f * density, currentPointPaint)
            }
        }
        canvas.restore()
    }
}
