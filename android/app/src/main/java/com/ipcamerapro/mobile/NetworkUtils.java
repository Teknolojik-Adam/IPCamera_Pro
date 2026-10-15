package com.ipcamerapro.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/** Only advertises a private LAN address, never a cellular/public IP. */
public final class NetworkUtils {
    private NetworkUtils() { }

    public static String localWifiIpv4(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();
            if (net != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                LinkProperties props = cm.getLinkProperties(net);
                if (caps != null && props != null
                        && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    for (LinkAddress addr : props.getLinkAddresses()) {
                        InetAddress ip = addr.getAddress();
                        if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) {
                            return ip.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        // Hotspot devices may report cellular as the active network. Look for
        // a WLAN interface only (not tun, rmnet, cellular or VPN interfaces).
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface iface = all.nextElement();
                String name = iface.getName().toLowerCase(java.util.Locale.ROOT);
                if (!(name.startsWith("wlan") || name.startsWith("wifi")
                        || name.startsWith("ap"))) continue;
                if (!iface.isUp() || iface.isLoopback()) continue;
                for (Enumeration<InetAddress> addresses = iface.getInetAddresses();
                     addresses.hasMoreElements();) {
                    InetAddress ip = addresses.nextElement();
                    if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) {
                        return ip.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
