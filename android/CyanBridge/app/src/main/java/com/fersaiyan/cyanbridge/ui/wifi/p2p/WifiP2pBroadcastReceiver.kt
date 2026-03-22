package com.fersaiyan.cyanbridge.ui.wifi.p2p

import android.net.wifi.p2p.WifiP2pDevice
import com.heycyan.core.connectivity.p2p.CoreWifiP2pBroadcastReceiver
import com.heycyan.core.connectivity.p2p.WifiP2pBroadcastHandler

class WifiP2pBroadcastReceiver(
    private val wifiP2pManagerSingleton: WifiP2pManagerSingleton
) : CoreWifiP2pBroadcastReceiver(
    object : WifiP2pBroadcastHandler {
        override fun onWifiP2pEnabled() {
            wifiP2pManagerSingleton.onWifiP2pEnabled()
        }

        override fun onWifiP2pDisabled() {
            wifiP2pManagerSingleton.onWifiP2pDisabled()
        }

        override fun requestPeers() {
            wifiP2pManagerSingleton.requestPeers()
        }

        override fun requestConnectionInfo() {
            wifiP2pManagerSingleton.requestConnectionInfo()
        }

        override fun onDisconnected() {
            wifiP2pManagerSingleton.onDisconnected()
        }

        override fun onThisDeviceChanged(device: WifiP2pDevice) {
            wifiP2pManagerSingleton.onThisDeviceChanged(device)
        }
    },
    TAG,
) {
    companion object {
        private const val TAG = "WifiP2pBroadcastReceiver"
    }
}
