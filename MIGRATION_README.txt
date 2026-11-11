KyoshinViewer - RealtimeIntensity UI parts migration

Extract this ZIP into the Android project root (the folder containing app/).
It adds reusable UI custom views, the original intensity color mapping, a sensor
calibration monitor, CSV recorder, and card drawables. It does not replace
MainActivity.kt or activity_main.xml.

Added Kotlin packages:
- jp.example.kyoshinviewer.ui
- jp.example.kyoshinviewer.intensity.color
- jp.example.kyoshinviewer.intensity.processor.CalibrationMonitor
- jp.example.kyoshinviewer.recording.CsvRecorder

The CSV recorder is adapted to this project's ProcessedSample and IntensityClass
package paths. The UI view classes retain the original rendering logic.

The next integration stage will place the original two-column intensity card
above the Kyoshin Monitor WebView and move technical details, waveforms, and
recording controls below the WebView.
