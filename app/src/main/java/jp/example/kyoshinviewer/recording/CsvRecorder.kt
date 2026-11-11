package jp.example.kyoshinviewer.recording

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import jp.example.kyoshinviewer.intensity.processor.ProcessedSample
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 計測データをCSVへ保存する。
 *
 * CSVには重力の直流成分を抑えたフィルタ後X/Y/Z軸加速度[gal]を記録する。
 * 各行にセンサーイベントの時刻をISO 8601形式で記録する。
 * SensorEvent.timestamp は起動後の単調増加時刻なので、計測開始時に
 * SystemClock.elapsedRealtimeNanos() と壁時計を対応付けて変換する。
 */
class CsvRecorder(private val context: Context) {

    data class RecordingInfo(
        val file: File,
        val sampleCount: Long,
        val sizeBytes: Long,
        val lastModified: Long,
        val maxIntensityClass: String,
        val maxMeasuredIntensity: Double?,
        val maxFilteredAccelerationGal: Double?,
        val startDateTime: String?
    )

    /** 再生用の1サンプル。時刻はファイル先頭からの相対ナノ秒。 */
    data class PlaybackSample(
        val relativeNanos: Long,
        val xGal: Double,
        val yGal: Double,
        val zGal: Double
    )

    data class PlaybackData(
        val samples: List<PlaybackSample>,
        val sampleRateHz: Double
    )

    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private var headerWritten = false
    private var recordingStartWallClockMillis: Long = 0L
    private var anchorWallClockNanos: Long = 0L
    private var anchorElapsedRealtimeNanos: Long = 0L
    private var maxRawIntensity: Double = Double.NEGATIVE_INFINITY
    private var maxIntensityClassLabel: String = "不明"
    private var maxFilteredAccelerationGal: Double = Double.NEGATIVE_INFINITY
    private var firstSampleTimestamp: String? = null

    val isRecording: Boolean get() = writer != null

    private fun recordingDirectory(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "recordings").apply { mkdirs() }
    }

    fun start(): File {
        stop()

        val dir = recordingDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date())
        val baseName = "rtintensity_$timestamp"
        var file = File(dir, "$baseName.csv")
        var suffix = 1
        while (file.exists()) {
            file = File(dir, "${baseName}_${suffix.toString().padStart(2, '0')}.csv")
            suffix++
        }

        // wall clock取得を挟む前後のelapsedRealtimeの中点を基準時刻にする。
        // これにより、センサーイベント時刻を実際の日時へ変換できる。
        val elapsedBefore = SystemClock.elapsedRealtimeNanos()
        val wallClockMillis = System.currentTimeMillis()
        val elapsedAfter = SystemClock.elapsedRealtimeNanos()
        anchorElapsedRealtimeNanos = elapsedBefore + (elapsedAfter - elapsedBefore) / 2L
        anchorWallClockNanos = wallClockMillis * NANOS_PER_MILLI

        writer = BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8))
        currentFile = file
        headerWritten = false
        recordingStartWallClockMillis = 0L
        maxRawIntensity = Double.NEGATIVE_INFINITY
        maxIntensityClassLabel = "不明"
        maxFilteredAccelerationGal = Double.NEGATIVE_INFINITY
        firstSampleTimestamp = null
        return file
    }

    private fun wallClockNanosFor(sensorTimestampNanos: Long): Long =
        anchorWallClockNanos + (sensorTimestampNanos - anchorElapsedRealtimeNanos)

    private fun formatTimestamp(epochNanos: Long): String {
        val seconds = Math.floorDiv(epochNanos, NANOS_PER_SECOND)
        val nanos = Math.floorMod(epochNanos, NANOS_PER_SECOND)
        return ISO_TIMESTAMP_FORMATTER.format(
            Instant.ofEpochSecond(seconds, nanos).atZone(ZoneId.systemDefault())
        )
    }

    private fun writeHeader(samplingHz: Double) {
        val w = writer ?: return

        val initialTime = SimpleDateFormat(
            "yyyy MM dd HH mm ss",
            Locale.US
        ).format(Date(recordingStartWallClockMillis))

        val frequency = maxOf(1, samplingHz.roundToInt())

        w.write("SITE CODE= RealtimeIntensity,,\n")
        w.write("LAT.=  ,,\n")
        w.write("LON.=  ,,\n")
        w.write("Sampling Freq= ${frequency}Hz,,\n")
        w.write("UNIT  = gal(cm/s/s),,\n")
        w.write("INITIAL TIME = $initialTime,,\n")
        w.write("TIMESTAMP,X,Y,Z\n")
    }

    fun append(sample: ProcessedSample) {
        val w = writer ?: return
        val sampleEpochNanos = wallClockNanosFor(sample.timestampNanos)

        if (!headerWritten) {
            // ヘッダ時刻も、最初に保存するセンサーサンプルの時刻にそろえる。
            recordingStartWallClockMillis = sampleEpochNanos / NANOS_PER_MILLI
            writeHeader(sample.sampleRateHz)
            headerWritten = true
        }

        val timestamp = formatTimestamp(sampleEpochNanos)
        if (firstSampleTimestamp == null) firstSampleTimestamp = timestamp
        if (sample.rawIntensity.isFinite() && sample.rawIntensity > maxRawIntensity) {
            maxRawIntensity = sample.rawIntensity
            maxIntensityClassLabel = jp.example.kyoshinviewer.intensity.processor.IntensityClass.fromRawIntensity(sample.rawIntensity).label
        }
        if (sample.combinedGal.isFinite() && sample.combinedGal > maxFilteredAccelerationGal) {
            maxFilteredAccelerationGal = sample.combinedGal
        }
        // filteredX/Y/Zは各軸にNIED系の帯域フィルタを適用した値。
        // 直流成分(静止時の重力バイアス)を抑えた地動相当の三軸加速度を出力する。
        w.write("$timestamp,${sample.filteredXGal},${sample.filteredYGal},${sample.filteredZGal}\n")
    }

    fun stop() {
        val file = currentFile
        writer?.flush()
        writer?.close()
        writer = null
        if (file != null && headerWritten && file.exists()) {
            try {
                val meta = File(file.parentFile, file.nameWithoutExtension + ".meta")
                meta.writeText(buildString {
                    appendLine("maxIntensityClass=$maxIntensityClassLabel")
                    appendLine("maxMeasuredIntensity=${maxRawIntensity.takeIf { it.isFinite() } ?: ""}")
                    appendLine("maxFilteredAccelerationGal=${maxFilteredAccelerationGal.takeIf { it.isFinite() } ?: ""}")
                    appendLine("startDateTime=${firstSampleTimestamp ?: ""}")
                }, Charsets.UTF_8)
            } catch (_: Exception) { /* CSV自体の保存を優先 */ }
        }
    }

    fun currentFilePath(): String? =
        currentFile?.takeIf { it.exists() }?.absolutePath

    fun currentFileName(): String? =
        currentFile?.takeIf { it.exists() }?.name

    fun hasRecordedData(): Boolean {
        val file = currentFile ?: return false
        return file.exists() && file.length() > 0L && headerWritten
    }

    /** 保存済みのCSV一覧を新しい順に返す。読み込みはワーカースレッドから呼ぶこと。 */
    fun listRecordingsWithStats(): List<RecordingInfo> {
        return recordingDirectory().listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("rtintensity_") && it.name.endsWith(".csv") }
            .filter { it.length() > 0L }
            .map { file ->
                val metaFile = File(file.parentFile, file.nameWithoutExtension + ".meta")
                val meta = java.util.Properties().apply {
                    if (metaFile.isFile) metaFile.reader(Charsets.UTF_8).use { load(it) }
                }
                RecordingInfo(
                    file = file,
                    sampleCount = countSamples(file),
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    maxIntensityClass = meta.getProperty("maxIntensityClass", "未記録"),
                    maxMeasuredIntensity = meta.getProperty("maxMeasuredIntensity")?.toDoubleOrNull(),
                    maxFilteredAccelerationGal = meta.getProperty("maxFilteredAccelerationGal")?.toDoubleOrNull(),
                    startDateTime = meta.getProperty("startDateTime")?.takeIf { it.isNotBlank() }
                )
            }
            .sortedByDescending { it.lastModified }
            .toList()
    }

    private fun countSamples(file: File): Long {
        var foundColumnHeader = false
        var count = 0L
        BufferedReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (!foundColumnHeader) {
                    val columns = line.removePrefix("\uFEFF").split(',').map { it.trim().uppercase(Locale.ROOT) }
                    foundColumnHeader = columns == listOf("X", "Y", "Z") ||
                        columns == listOf("NS", "EW", "UD") ||
                        columns == listOf("TIMESTAMP", "X", "Y", "Z")
                    continue
                }
                if (line.isNotBlank()) count++
            }
        }
        return count
    }


    /**
     * 保存済み記録を再生用に読み込む。
     * 対象はアプリ専用recordingsフォルダ内のCSVに限定する。
     * TIMESTAMP,X,Y,Z形式と、時刻列を持たない旧X,Y,Z / NS,EW,UD形式に対応する。
     */
    fun readPlaybackData(source: File): PlaybackData {
        val canonicalSource = source.canonicalFile
        if (canonicalSource.parentFile != recordingDirectory().canonicalFile ||
            !canonicalSource.isFile ||
            !canonicalSource.name.startsWith("rtintensity_") ||
            !canonicalSource.name.endsWith(".csv")) {
            throw IllegalArgumentException("アプリ内の保存済みCSVを選択してください")
        }

        val lines = canonicalSource.readLines(Charsets.UTF_8)
            .map { it.removePrefix("\uFEFF").trimEnd('\r') }
        val frequencyLine = lines.firstOrNull { it.startsWith("Sampling Freq", ignoreCase = true) }
        val declaredHz = frequencyLine?.substringAfter('=')?.trim()?.removeSuffix(",")
            ?.trim()?.removeSuffix("Hz")?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }

        val headerIndex = lines.indexOfFirst { line ->
            val columns = line.split(',').map { it.trim().uppercase(Locale.ROOT) }
            columns == listOf("TIMESTAMP", "X", "Y", "Z") ||
                columns == listOf("X", "Y", "Z") ||
                columns == listOf("NS", "EW", "UD")
        }
        if (headerIndex < 0) {
            throw IllegalArgumentException("TIMESTAMP,X,Y,Z または X,Y,Z の列ヘッダがありません")
        }

        val header = lines[headerIndex].split(',').map { it.trim().uppercase(Locale.ROOT) }
        val hasTimestamp = header.size == 4
        val stepNanos = (1_000_000_000.0 / (declaredHz ?: 100.0)).toLong().coerceAtLeast(1L)
        val samples = ArrayList<PlaybackSample>()
        var firstTimestamp: Long? = null
        var previousRelativeNanos = -1L

        for (lineIndex in headerIndex + 1 until lines.size) {
            val line = lines[lineIndex].trim()
            if (line.isEmpty()) continue
            val columns = line.split(',').map { it.trim() }
            if (columns.size != header.size) {
                throw IllegalArgumentException("CSVの列数が一致しません (行${lineIndex + 1})")
            }
            val offset = if (hasTimestamp) 1 else 0
            val x = columns[offset].toDoubleOrNull()
            val y = columns[offset + 1].toDoubleOrNull()
            val z = columns[offset + 2].toDoubleOrNull()
            if (x == null || y == null || z == null ||
                !x.isFinite() || !y.isFinite() || !z.isFinite()) {
                throw IllegalArgumentException("加速度を数値として読み込めません (行${lineIndex + 1})")
            }

            val parsedTimestamp = if (hasTimestamp) parseTimestampNanos(columns[0]) else null
            val relativeNanos = if (parsedTimestamp != null) {
                val origin = firstTimestamp ?: parsedTimestamp.also { firstTimestamp = it }
                (parsedTimestamp - origin).coerceAtLeast(0L)
            } else {
                (samples.size.toLong()) * stepNanos
            }
            val strictlyIncreasing = if (previousRelativeNanos < 0L) 0L
                else maxOf(relativeNanos, previousRelativeNanos + 1L)
            samples.add(
                PlaybackSample(
                    relativeNanos = if (samples.isEmpty()) 0L else strictlyIncreasing,
                    xGal = x,
                    yGal = y,
                    zGal = z
                )
            )
            previousRelativeNanos = if (samples.size == 1) 0L else strictlyIncreasing
        }

        if (samples.isEmpty()) throw IllegalArgumentException("再生できる加速度データがありません")

        val estimatedHz = if (samples.size >= 2) {
            val intervals = samples.zipWithNext { a, b -> b.relativeNanos - a.relativeNanos }
                .filter { it > 0L }
                .sorted()
            if (intervals.isNotEmpty()) 1_000_000_000.0 / intervals[intervals.size / 2] else null
        } else null
        return PlaybackData(samples, estimatedHz?.takeIf { it.isFinite() && it > 0.0 } ?: declaredHz ?: 100.0)
    }

    private fun parseTimestampNanos(value: String): Long? {
        return try {
            val instant = try {
                OffsetDateTime.parse(value).toInstant()
            } catch (_: Exception) {
                try {
                    Instant.parse(value)
                } catch (_: Exception) {
                    LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant()
                }
            }
            instant.epochSecond * NANOS_PER_SECOND + instant.nano
        } catch (_: Exception) {
            null
        }
    }

    /** 一覧から選んだ保存済みCSVと、その付随メタデータを削除する。 */
    fun deleteRecording(source: File): Boolean {
        val canonicalSource = try {
            source.canonicalFile
        } catch (_: Exception) {
            return false
        }
        val recordingsDir = try {
            recordingDirectory().canonicalFile
        } catch (_: Exception) {
            return false
        }

        // アプリ専用recordingsフォルダ内の対象CSVだけを削除する。
        if (canonicalSource.parentFile != recordingsDir ||
            !canonicalSource.name.startsWith("rtintensity_") ||
            !canonicalSource.name.endsWith(".csv") ||
            !canonicalSource.isFile) {
            return false
        }

        // 計測中のファイルは誤操作や書き込み競合を避けるため削除しない。
        if (isRecording && currentFile?.canonicalFile == canonicalSource) return false

        if (!canonicalSource.delete()) return false

        val metaFile = File(recordingsDir, canonicalSource.nameWithoutExtension + ".meta")
        if (metaFile.isFile) {
            // CSV削除後にメタデータだけ残っても一覧には影響しないため、削除失敗は許容する。
            metaFile.delete()
        }
        return true
    }

    /** 現在の記録を書き出す既存API。 */
    fun exportTo(uri: Uri): Boolean {
        val source = currentFile ?: return false
        return exportFileTo(source, uri)
    }

    /** 一覧から選んだ任意の保存済みCSVを書き出す。 */
    fun exportFileTo(source: File, uri: Uri): Boolean {
        if (isRecording && source.canonicalFile == currentFile?.canonicalFile) return false
        if (!source.exists() || !source.isFile) return false

        // アプリのrecordingsフォルダ内のファイルのみを対象にする。
        val parent = source.canonicalFile.parentFile
        if (parent != recordingDirectory().canonicalFile ||
            !source.name.startsWith("rtintensity_") || !source.name.endsWith(".csv")) {
            return false
        }

        val output = context.contentResolver.openOutputStream(uri) ?: return false
        output.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        }
        return true
    }

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private val ISO_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}
