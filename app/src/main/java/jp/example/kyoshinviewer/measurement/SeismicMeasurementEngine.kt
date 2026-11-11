package jp.example.kyoshinviewer.measurement

import android.content.Context
import jp.example.kyoshinviewer.intensity.processor.ProcessedSample
import jp.example.kyoshinviewer.intensity.processor.SeismicProcessor
import jp.example.kyoshinviewer.sensor.AccelerometerSource

/**
 * 加速度センサーの取得とリアルタイム震度計算を統合するクラス。
 *
 * センサー取得・計算はバックグラウンドスレッドで実行する。
 * onSampleもセンサー処理スレッドから呼び出される。
 */
class SeismicMeasurementEngine(
    context: Context,
    private val onSample: (ProcessedSample) -> Unit
) {

    private val accelerometerSource = AccelerometerSource(context)
    private val seismicProcessor = SeismicProcessor()

    @Volatile
    private var running = false

    val isAvailable: Boolean
        get() = accelerometerSource.isAvailable

    fun describeSensor(): String {
        return accelerometerSource.describeSensor()
    }

    /**
     * センサー取得と震度計算を開始する。
     *
     * 開始できた場合はtrueを返す。
     */
    fun start(): Boolean {
        if (running) return true

        seismicProcessor.reset()

        val started = accelerometerSource.start {
                ax, ay, az, timestampNanos ->

            if (!running) return@start

            val result = seismicProcessor.onNewSample(
                ax,
                ay,
                az,
                timestampNanos
            )

            if (result != null) {
                onSample(result)
            }
        }

        running = started
        return started
    }

    /**
     * センサー取得を停止し、計算状態を初期化する。
     */
    fun stop() {
        running = false
        accelerometerSource.stop()
        seismicProcessor.reset()
    }
}
