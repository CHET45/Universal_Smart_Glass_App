package com.fersaiyan.cyanbridge.ui.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

public final class ConnectorUtils {
    private ConnectorUtils() {
    }

    public static boolean isHexWepKey(String wepKey) {
        return com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.isHexWepKey(wepKey);
    }

    public static void reEnableNetworkIfPossible(WifiManager wifiMgr, ScanResult scanResult) {
        com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.reEnableNetworkIfPossible(wifiMgr, scanResult);
    }

    public static void reEnableNetworkIfPossible(WifiManager wifiMgr, String bssid) {
        com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.reEnableNetworkIfPossible(wifiMgr, bssid);
    }

    public static int getMaxPriority(WifiManager wifiManager) {
        return com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.getMaxPriority(wifiManager);
    }

    public static WifiConfiguration createWifiConfiguration(String security, String ssid, String password) {
        return com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.createWifiConfiguration(security, ssid, password);
    }

    public static int saveNetwork(WifiManager wifiManager, WifiConfiguration config) {
        return com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.saveNetwork(wifiManager, config);
    }

    public static boolean isAlreadyConnected(WifiManager wifiManager, String bssid) {
        return com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.isAlreadyConnected(wifiManager, bssid);
    }

    public static boolean isAlreadyConnected(WifiManager wifiManager, ScanResult scanResult) {
        return com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.isAlreadyConnected(wifiManager, scanResult);
    }

    public static void disconnectFromAll(WifiManager wifiManager) {
        com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.disconnectFromAll(wifiManager);
    }

    public static WifiConfiguration disableOthers(WifiManager wifiManager, WifiConfiguration config) {
        return com.heycyan.core.connectivity.wifi.ConnectorUtilsCore.disableOthers(wifiManager, config);
    }
}
