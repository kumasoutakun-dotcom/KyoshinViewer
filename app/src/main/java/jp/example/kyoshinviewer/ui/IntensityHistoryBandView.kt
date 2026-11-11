package jp.example.kyoshinviewer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import jp.example.kyoshinviewer.intensity.color.IntensityClassColors
import jp.example.kyoshinviewer.intensity.processor.IntensityClass
import kotlin.math.min

/** 直近の震度階級の時間変化を、過去(左)から現在(右)へ表示する履歴帯。 */
class IntensityHistoryBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var capacity = 100
    private var classes = arrayOfNulls<IntensityClass>(capacity)
    private var writeIndex = 0
    private var filled = 0

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#142748")
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50678E")
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val clipPath = Path()
    private val rect = RectF()

    /** 表示するコマ数(時間幅)を変更し、リングバッファも同じ長さに作り直す。 */
    fun setCapacity(n: Int) {
        capacity = n.coerceAtLeast(1)
        classes = arrayOfNulls(capacity)
        resetInternal()
        invalidate()
    }

    /** 新しい震度階級を右端に追加する。 */
    fun push(intensityClass: IntensityClass) {
        classes[writeIndex] = intensityClass
        writeIndex = (writeIndex + 1) % classes.size
        if (filled < classes.size) filled++
        invalidate()
    }

    fun clear() {
        resetInternal()
        invalidate()
    }

    private fun resetInternal() {
        classes.fill(null)
        writeIndex = 0
        filled = 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        rect.set(0.5f * density, 0.5f * density, w - 0.5f * density, h - 0.5f * density)
        val outerRadius = rect.height() / 2f
        canvas.drawRoundRect(rect, outerRadius, outerRadius, backgroundPaint)
        canvas.drawRoundRect(rect, outerRadius, outerRadius, outlinePaint)
        if (filled == 0) return

        val inset = 2f * density
        val innerLeft = inset
        val innerRight = w - inset
        val innerTop = inset
        val innerBottom = h - inset
        val innerWidth = innerRight - innerLeft
        val innerHeight = innerBottom - innerTop
        if (innerWidth <= 0f || innerHeight <= 0f) return

        // 1サンプル=1スロットを保ったまま、極細の溝でMaterial 3風に分節する。
        val gap = 0.65f * density
        val cellWidth = ((innerWidth - gap * (capacity - 1)) / capacity).coerceAtLeast(0f)
        val step = cellWidth + gap
        val leftPad = (capacity - filled) * step
        val corner = min(2f * density, min(cellWidth / 2f, innerHeight / 2f))

        clipPath.reset()
        clipPath.addRoundRect(rect, outerRadius, outerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        val oldestPos = if (filled < classes.size) 0 else writeIndex
        for (i in 0 until filled) {
            val idx = (oldestPos + i) % classes.size
            val cls = classes[idx] ?: continue
            fillPaint.color = IntensityClassColors.colorsFor(cls).background
            val left = innerLeft + leftPad + i * step
            val right = left + cellWidth
            if (right <= left) continue
            canvas.drawRoundRect(
                left,
                innerTop,
                right,
                innerBottom,
                corner,
                corner,
                fillPaint
            )
        }
        canvas.restore()
    }
}
