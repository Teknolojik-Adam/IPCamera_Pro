package com.ipcamerapro.mobile;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** JVM protocol test. This does not require Android or a physical camera. */
public final class MjpegServerSmoke {
    private static byte[] jpeg(int step) throws Exception {
        BufferedImage image = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, 320, 240);
        graphics.setColor(Color.ORANGE);
        graphics.fillRect((step * 8) % 280, 40, 40, 70);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 145, 320, 65);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) throw new AssertionError("JPG encoder missing");
        return out.toByteArray();
    }

    private static HttpURLConnection connect(String value) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(value).openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(4000);
        conn.setRequestProperty("User-Agent", "IPCameraPro-Smoke/1.0");
        return conn;
    }

    private static boolean contains(byte[] big, byte[] small) {
        outer: for (int i=0; i<=big.length-small.length; i++) {
            for (int j=0; j<small.length; j++) if (big[i+j]!=small[j]) continue outer;
            return true;
        }
        return false;
    }

    private static void expectStatus(String url, int expected) throws Exception {
        HttpURLConnection conn = connect(url);
        try {
            int actual = conn.getResponseCode();
            if (actual != expected) {
                throw new AssertionError("Expected HTTP " + expected + ", got " + actual);
            }
        } finally { conn.disconnect(); }
    }

    private static void testNoOldFrame(MjpegServer server, String url) throws Exception {
        server.offerJpeg(jpeg(1));
        server.clearFrame();
        HttpURLConnection conn = connect(url);
        conn.setReadTimeout(300);
        try {
            if (conn.getResponseCode() != 200) throw new AssertionError("MJPEG refused");
            InputStream in = conn.getInputStream();
            try {
                int first = in.read();
                throw new AssertionError("Old lens frame leaked after clearFrame(): " + first);
            } catch (SocketTimeoutException expected) {
                // No stale frame may be sent until the new lens supplies one.
            }
            server.offerJpeg(jpeg(2));
            conn.setReadTimeout(3000);
            if (in.read() < 0) throw new AssertionError("New lens frame missing");
        } finally { conn.disconnect(); }
    }

    public static void main(String[] args) throws Exception {
        String previousToken;
        try (MjpegServer server = new MjpegServer()) {
            try {
                server.start("0.0.0.0");
                throw new AssertionError("Wildcard bind must be rejected");
            } catch (IOException expected) { }
            try {
                server.start("8.8.8.8");
                throw new AssertionError("Public IPv4 bind must be rejected");
            } catch (IOException expected) { }
            if (server.isRunning()) throw new AssertionError("Failed bind started server");
            String secret = server.start("127.0.0.1");
            previousToken = secret;
            if (secret.length() != 32) throw new AssertionError("Unexpected token size");
            String base = "http://127.0.0.1:" + MjpegServer.PORT + "/video?token=";
            if (args.length > 0 && args[0].equals("--serve")) {
                System.out.println("TEST_URL=" + base + secret);
                System.out.flush();
                long until = System.currentTimeMillis() + 13000L;
                int i=0;
                while (System.currentTimeMillis() < until) {
                    server.offerJpeg(jpeg(i++));
                    Thread.sleep(85);
                }
                return;
            }

            HttpURLConnection denied = connect(base + "wrong");
            int status = denied.getResponseCode();
            denied.disconnect();
            if (status != 403) throw new AssertionError("Unauthorized should be 403, got "+status);

            expectStatus(base + secret + "&token=" + secret, 403);
            expectStatus(base + secret + "&extra=1", 403);
            expectStatus(base + secret.substring(0, 31), 403);
            expectStatus(base.replace("/video?", "/admin?") + secret, 403);
            testNoOldFrame(server, base + secret);

            HttpURLConnection allowed = connect(base + secret);
            int authorized = allowed.getResponseCode();
            if (authorized != 200) throw new AssertionError("Authorized should be 200");
            String contentType = allowed.getHeaderField("Content-Type");
            if (contentType == null || !contentType.contains("multipart/x-mixed-replace")) {
                throw new AssertionError("Not an MJPEG content type: " + contentType);
            }
            server.offerJpeg(jpeg(4));
            byte[] received = new byte[8192];
            int offset=0;
            InputStream stream = allowed.getInputStream();
            while (offset < received.length) {
                int n=stream.read(received,offset,received.length-offset);
                if (n<0) break;
                offset+=n;
                byte[] partial=Arrays.copyOf(received,offset);
                if (contains(partial,"Content-Type: image/jpeg".getBytes("US-ASCII")) &&
                    contains(partial,new byte[]{(byte)0xff,(byte)0xd8})) break;
            }
            allowed.disconnect();
            byte[] bytes=Arrays.copyOf(received,offset);
            if (!contains(bytes,"--ipcamframe".getBytes("US-ASCII")))
                throw new AssertionError("Multipart boundary missing");
            if (!contains(bytes,new byte[]{(byte)0xff,(byte)0xd8}))
                throw new AssertionError("JPEG start-of-image missing");
            System.out.println("PASS: wildcard/public binding rejected");
            System.out.println("PASS: duplicate/extra/malformed URL rejected (403)");
            System.out.println("PASS: clearFrame suppresses stale frame and accepts new frame");
            System.out.println("PASS: auth rejects invalid token (403)");
            System.out.println("PASS: valid stream responds 200 and multipart JPEG frames");
            System.out.println("PASS: received "+offset+" bytes; token changes per server start");
        }
        // Simulate reconnect after a stopped stream: new token, old token denied.
        try (MjpegServer reopened = new MjpegServer()) {
            String newToken = reopened.start("127.0.0.1");
            if (newToken.equals(previousToken)) throw new AssertionError("Token reused");
            String base = "http://127.0.0.1:" + MjpegServer.PORT + "/video?token=";
            expectStatus(base + previousToken, 403);
            HttpURLConnection conn = connect(base + newToken);
            try {
                if (conn.getResponseCode() != 200) {
                    throw new AssertionError("New stream refused after reconnect");
                }
            } finally { conn.disconnect(); }
        }
        System.out.println("PASS: reconnect rotates token and denies previous session");
        System.out.println("PASS: server shuts down cleanly");
    }
}
