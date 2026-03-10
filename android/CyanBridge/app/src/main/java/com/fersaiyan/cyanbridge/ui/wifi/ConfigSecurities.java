package com.fersaiyan.cyanbridge.ui.wifi;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;

final class ConfigSecurities {
    static final String SECURITY_EAP = com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.SECURITY_EAP;
    static final String SECURITY_NONE = com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.SECURITY_NONE;
    static final String SECURITY_PSK = com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.SECURITY_PSK;
    static final String SECURITY_WEP = com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.SECURITY_WEP;

    ConfigSecurities() {
    }

    static void setupSecurity(WifiConfiguration config, String security, String password) {
        com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.setupSecurity(config, security, password);
    }

    static void setupSecurityHidden(WifiConfiguration config, String security, String password) {
        com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.setupSecurityHidden(config, security, password);
    }

    static void setupWifiNetworkSpecifierSecurities(WifiNetworkSpecifier.Builder builder, String security, String password) {
        com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.setupWifiNetworkSpecifierSecurities(builder, security, password);
    }

    static WifiConfiguration getWifiConfiguration(WifiManager wifiMgr, WifiConfiguration configToFind) {
        return com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.getWifiConfiguration(wifiMgr, configToFind);
    }

    static WifiConfiguration getWifiConfiguration(WifiManager wifiManager, String ssid) {
        return com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.getWifiConfiguration(wifiManager, ssid);
    }

    static WifiConfiguration getWifiConfiguration(WifiManager wifiManager, ScanResult scanResult) {
        return com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.getWifiConfiguration(wifiManager, scanResult);
    }

    static String getSecurity(WifiConfiguration config) {
        return com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.getSecurity(config);
    }

    static String getSecurity(ScanResult result) {
        return com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.getSecurity(result);
    }

    static String getSecurity(String result) {
        return com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.getSecurity(result);
    }

    public static String getSecurityPrettyPlusWps(ScanResult scanResult) {
        return com.heycyan.core.connectivity.wifi.ConfigSecuritiesCore.getSecurityPrettyPlusWps(scanResult);
    }
}
