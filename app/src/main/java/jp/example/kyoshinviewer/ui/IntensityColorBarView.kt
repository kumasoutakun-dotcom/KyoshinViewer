package jp.example.kyoshinviewer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 現在値の色を示すためのMaterial 3風カラーバー。
 * 色の算出は呼び出し元の既存カラーマップに任せ、このViewは見た目だけを担当する。
 */
class IntensityColorBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private var barColor: Int = Color.GRAY

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#283C64")
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(82, 243, 246, 255)
        style = Paint.Style.STROKE
        strokeWidth = density
    }

    fun setColor(color: Int) {
        if (barColor == color) return
        barColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = density
        val bounds = RectF(
            inset,
            inset,
            width.toFloat() - inset,
            height.toFloat() - inset
        )
        if (bounds.width() <= 0f || bounds.height() <= 0f) return

        val radius = bounds.height() / 2f
        canvas.drawRoundRect(bounds, radius, radius, trackPaint)
        fillPaint.color = barColor
        canvas.drawRoundRect(bounds, radius, radius, fillPaint)
        canvas.drawRoundRect(bounds, radius, radius, outlinePaint)
    }
}
