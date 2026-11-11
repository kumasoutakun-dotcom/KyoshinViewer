package jp.example.kyoshinviewer.data

/**
 * 強震モニタの表示元を定義するインターフェース。
 */
interface MonitorPageSource {
    val startPageUrl: String
}

/**
 * kwatch-24hの強震モニタページを表示するデータソース。
 */
class KwatchWebPageSource : MonitorPageSource {

    override val startPageUrl: String =
        "https://kwatch-24h.net/2sec_alm_t2.html"
}
