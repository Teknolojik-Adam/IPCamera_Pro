package com.ipcamerapro.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time LAN discovery for Fedora IPCamera Pro.
 * The user only enters the six-digit pairing PIN. The phone then discovers
 * Fedora by Wi-Fi UDP broadcast and POSTs its authenticated camera stream URL.
 * Intended only for a trusted local network (not WAN/public Wi-Fi).
 */
public final class PairDiscovery {
    public interface Callback {
        void onResult(boolean ok, String explanation);
    }

    private static final byte[] QUERY =
            "IPCAM_PRO_DISCOVER_V1".getBytes(StandardCharsets.US_ASCII);
    private static final String REPLY_PREFIX = "IPCAM_PRO_PAIR_V1|";
    private static final int PORT_FIRST = 1755;
    private static final int PORT_LAST = 1760;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PairDiscovery() { }

    private static final class WifiPath {
        final Network network;
        final InetAddress broadcast;
        WifiPath(Network network, InetAddress broadcast) {
            this.network = network;
            this.broadcast = broadcast;
        }
    }

    private static InetAddress broadcastAddress(byte[] ip, int prefix) throws IOException {
        if (ip.length != 4 || prefix < 1 || prefix > 32) {
            throw new IOException("Invalid Wi-Fi IPv4 subnet");
        }
        int value = 0;
        for (byte b : ip) value = (value << 8) | (b & 0xff);
        int mask = prefix == 32 ? -1 : -1 << (32 - prefix);
        int broadcast = (value & mask) | ~mask;
        byte[] output = {
                (byte)(broadcast >>> 24), (byte)(broadcast >>> 16),
                (byte)(broadcast >>> 8), (byte)broadcast
        };
        return InetAddress.getByAddress(output);
    }

    private static WifiPath findWifi(Context ctx) throws IOException {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) throw new IOException("Android network manager unavailable");
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                continue;
            }
            LinkProperties props = cm.getLinkProperties(network);
            if (props == null) continue;
            for (LinkAddress a : props.getLinkAddresses()) {
                InetAddress ip = a.getAddress();
                if (ip instanceof Inet4Address && ip.isSiteLocalAddress()) {
                    return new WifiPath(network,
                            broadcastAddress(ip.getAddress(), a.getPrefixLength()));
                }
            }
        }
        throw new IOException("Telefonu PC ile aynı Wi-Fi ağına bağla.");
    }

    private static boolean privateIpv4(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr instanceof Inet4Address && addr.isSiteLocalAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String submit(WifiPath wifi, String host, int port,
                                 String pin, String cameraUrl) throws IOException {
        URL address = new URL("http://" + host + ":" + port + "/pair");
        HttpURLConnection conn = (HttpURLConnection) wifi.network.openConnection(address);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(4000);
        conn.setRequestMethod("POST");
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        conn.setRequestProperty("Cache-Control", "no-store");
        conn.setDoOutput(true);
        String body = "pin=" + URLEncoder.encode(pin, "UTF-8")
                + "&url=" + URLEncoder.encode(cameraUrl, "UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int status = conn.getResponseCode();
            if (status == 200) return null;
            if (status == 403) return "6 haneli kod yanlış. Fedora'daki yeni kodu gir.";
            if (status == 422) return "Telefonun IP adresi farklı veya kamera bağlantısı geçersiz. "
                    + "VPN'i kapatıp yayını yeniden başlat.";
            if (status == 429) return "Çok fazla deneme. Fedora'da eşleştirmeyi tekrar başlat.";
            return "PC eşleştirmeyi kabul etmedi (HTTP " + status + ").";
        } finally {
            conn.disconnect();
        }
    }

    public static void pairAsync(Context context, String pin,
                                 String cameraUrl, Callback callback) {
        if (pin == null || !pin.matches("[0-9]{6}")) {
            callback.onResult(false, "Fedora terminalindeki 6 haneli kodu gir.");
            return;
        }
        if (cameraUrl == null || cameraUrl.isEmpty()) {
            callback.onResult(false, "Önce Yayını Başlat düğmesine bas.");
            return;
        }
        Context app = context.getApplicationContext();
        new Thread(() -> {
            String error = null;
            try {
                WifiPath wifi = findWifi(app);
                List<InetAddress> targets = new ArrayList<>();
                targets.add(wifi.broadcast);
                InetAddress global = InetAddress.getByName("255.255.255.255");
                if (!wifi.broadcast.equals(global)) targets.add(global);

                try (DatagramSocket socket = new DatagramSocket()) {
                    // Enforce Wi-Fi even when Android prefers mobile data.
                    wifi.network.bindSocket(socket);
                    socket.setBroadcast(true);
                    socket.setSoTimeout(350);
                    byte[] responseBytes = new byte[256];

                    for (int attempt = 0; attempt < 4; attempt++) {
                        for (InetAddress addr : targets) {
                            for (int port = PORT_FIRST; port <= PORT_LAST; port++) {
                                socket.send(new DatagramPacket(
                                        QUERY, QUERY.length, addr, port));
                            }
                        }
                        long deadline = android.os.SystemClock.elapsedRealtime() + 1400;
                        while (android.os.SystemClock.elapsedRealtime() < deadline) {
                            try {
                                DatagramPacket response = new DatagramPacket(
                                        responseBytes, responseBytes.length);
                                socket.receive(response);
                                String text = new String(response.getData(), response.getOffset(),
                                        response.getLength(), StandardCharsets.US_ASCII);
                                if (!text.startsWith(REPLY_PREFIX)) continue;
                                String[] fields = text.split("\\|", -1);
                                if (fields.length != 3) continue;
                                String host = fields[1];
                                if (!privateIpv4(host)
                                        || !response.getAddress().getHostAddress().equals(host)) {
                                    continue;
                                }
                                int hostPort;
                                try { hostPort = Integer.parseInt(fields[2]); }
                                catch (NumberFormatException e) { continue; }
                                if (hostPort < PORT_FIRST || hostPort > PORT_LAST) continue;
                                String failure = submit(wifi, host, hostPort, pin, cameraUrl);
                                if (failure == null) {
                                    MAIN.post(() -> callback.onResult(true,
                                            "✅ Fedora bulundu ve kamera bağlandı! PC'de IPCamera-Pro'yu seç."));
                                } else {
                                    MAIN.post(() -> callback.onResult(false, failure));
                                }
                                return;
                            } catch (java.net.SocketTimeoutException ignored) {
                                // Broadcast again until the discovery deadline.
                            }
                        }
                    }
                }
                error = "PC bulunamadı. Fedora'da IPCamera Pro - Telefonu Bağla "
                        + "kısayolunu aç; telefon ve PC aynı Wi-Fi ağında olsun.";
            } catch (IOException | RuntimeException exception) {
                error = "Eşleştirme başarısız: " + exception.getMessage();
            }
            final String message = error;
            MAIN.post(() -> callback.onResult(false, message));
        }, "ipcam-wifi-discovery").start();
    }
}
