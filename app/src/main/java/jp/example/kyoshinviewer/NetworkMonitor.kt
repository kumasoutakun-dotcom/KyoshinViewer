package jp.example.kyoshinviewer

import android.net.ConnectivityManager
import android.net.Network

/**
 * ネットワークの接続・切断を監視するクラス。
 */
class NetworkMonitor(
    private val connectivityManager: ConnectivityManager,
    private val onNetworkLost: () -> Unit,
    private val onNetworkAvailable: () -> Unit
) {

    private var isRegistered = false

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {

            override fun onLost(network: Network) {
                onNetworkLost()
            }

            override fun onAvailable(network: Network) {
                onNetworkAvailable()
            }
        }

    /**
     * ネットワーク監視を開始する。
     */
    fun start() {
        if (isRegistered) return

        connectivityManager.registerDefaultNetworkCallback(
            networkCallback
        )

        isRegistered = true
    }

    /**
     * ネットワーク監視を終了する。
     */
    fun stop() {
        if (!isRegistered) return

        connectivityManager.unregisterNetworkCallback(
            networkCallback
        )

        isRegistered = false
    }
}
