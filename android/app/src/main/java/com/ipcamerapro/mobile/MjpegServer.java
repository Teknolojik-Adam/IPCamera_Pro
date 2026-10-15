package com.ipcamerapro.mobile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LAN-only MJPEG producer. Every start creates a new unguessable URL token.
 * The server is active only while the user has explicitly started the camera.
 * No file sharing, command execution, or remote camera-control endpoints.
 */
public final class MjpegServer implements AutoCloseable {
    public static final int PORT = 8787;
    private static final String BOUNDARY = "ipcamframe";
    private static final int MAX_REQUEST_LINE = 4096;
    private static final int MAX_CLIENTS = 2;
    private final Object frameLock = new Object();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running;
    private volatile String token;
    private volatile String boundIp;
    private volatile Frame latest;
    private long sequence;
    private ServerSocket listener;
    private Thread acceptThread;
    private ThreadPoolExecutor pool;

    private static final class Frame {
        final long id;
        final byte[] jpeg;
        Frame(long id, byte[] jpeg) { this.id = id; this.jpeg = jpeg; }
    }

    public synchronized String start(String wifiIp) throws IOException {
        if (running) throw new IllegalStateException("Server already started");
        byte[] secret = new byte[24];
        random.nextBytes(secret);
        String newToken = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        // Do not expose the bearer-token camera stream on cellular, VPN or
        // other interfaces. Listen only on the verified local Wi-Fi address.
        InetAddress bindAddress = InetAddress.getByName(wifiIp);
        // Loopback is also safe and lets the JVM protocol smoke test run
        // without exposing a test camera to the local network.
        if (!(bindAddress instanceof Inet4Address)
                || (!bindAddress.isSiteLocalAddress() && !bindAddress.isLoopbackAddress())) {
            throw new IOException("Private LAN or loopback IPv4 address required");
        }
        ServerSocket listening = new ServerSocket();
        listening.setReuseAddress(true);
        listening.bind(new InetSocketAddress(bindAddress, PORT), 4);
        listening.setSoTimeout(1000);

        pool = new ThreadPoolExecutor(
                MAX_CLIENTS, MAX_CLIENTS, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2),
                r -> { Thread t = new Thread(r, "ipcam-http-client"); t.setDaemon(true); return t; },
                new ThreadPoolExecutor.AbortPolicy());

        token = newToken;
        boundIp = wifiIp;
        sequence = 0;
        latest = null;
        listener = listening;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "ipcam-http-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return newToken;
    }

    public boolean isRunning() { return running; }
    public String getToken() { return token; }
    public String getBoundIp() { return boundIp; }

    // A camera/lens transition must not keep serving the previous lens frame.
    public void clearFrame() {
        synchronized (frameLock) {
            latest = null;
            frameLock.notifyAll();
        }
    }

    public void offerJpeg(byte[] jpeg) {
        if (!running || jpeg == null || jpeg.length < 4 || jpeg.length > 5_000_000) return;
        // Only the freshest frame is stored; stalled clients never accumulate a frame queue.
        synchronized (frameLock) {
            latest = new Frame(++sequence, jpeg);
            frameLock.notifyAll();
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = listener.accept();
                if (!running) { safeClose(s); break; }
                s.setSoTimeout(5000);
                s.setTcpNoDelay(true);
                try {
                    pool.execute(() -> handle(s));
                } catch (RuntimeException busy) {
                    sendResponse(s, 503, "Busy");
                    safeClose(s);
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // Check for stop without occupying the accept thread permanently.
            } catch (SocketException closed) {
                if (running) break;
            } catch (IOException ignored) {
                if (!running) break;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() <= MAX_REQUEST_LINE) {
            int c = in.read();
            if (c == -1) return null;
            if (c == '\n') return line.toString();
            if (c != '\r') line.append((char) c);
        }
        throw new IOException("Oversized HTTP request");
    }

    private static void sendResponse(Socket socket, int status, String text) {
        try {
            String body = text + "\n";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 " + status + " " + text + "\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            OutputStream out = socket.getOutputStream();
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) { }
    }

    private boolean validPath(String path) {
        // A camera URL is exactly /video?token=<32 base64url characters>.
        // Reject duplicate token keys, extra parameters, and ambiguous paths
        // so the Android server matches Fedora's strict URL validator.
        final String prefix = "/video?token=";
        if (path == null || !path.startsWith(prefix)) return false;
        String supplied = path.substring(prefix.length());
        if (supplied.length() != 32) return false;
        for (int i = 0; i < supplied.length(); i++) {
            char c = supplied.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        String required = token;
        return required != null && MessageDigest.isEqual(
                required.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private void handle(Socket socket) {
        sockets.add(socket);
        try {
            InputStream in = socket.getInputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] request = line.split(" ");
            if (request.length != 3 || !"GET".equals(request[0])
                    || !request[2].startsWith("HTTP/1.")) {
                sendResponse(socket, 400, "Bad Request");
                return;
            }
            // Bounded header processing, without trusting arbitrary client input.
            boolean end = false;
            for (int i = 0; i < 50; i++) {
                String header = readLine(in);
                if (header == null) return;
                if (header.isEmpty()) { end = true; break; }
            }
            if (!end) { sendResponse(socket, 431, "Headers Too Large"); return; }
            if (!validPath(request[1])) { sendResponse(socket, 403, "Forbidden"); return; }

            socket.setSoTimeout(0);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 96 * 1024);
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n" +
                    "Cache-Control: no-store, no-cache\r\n" +
                    "Pragma: no-cache\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long lastSent = -1;
            while (running && !socket.isClosed()) {
                Frame f;
                synchronized (frameLock) {
                    while (running && (latest == null || latest.id == lastSent)) {
                        try { frameLock.wait(1000); }
                        catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (!running) break;
                    f = latest;
                }
                if (f == null) continue;
                byte[] jpeg = f.jpeg;
                String part = "--" + BOUNDARY + "\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: " + jpeg.length + "\r\n\r\n";
                out.write(part.getBytes(StandardCharsets.US_ASCII));
                out.write(jpeg);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                lastSent = f.id;
            }
        } catch (IOException ignored) {
            // The phone/PC disconnecting is normal.
        } finally {
            sockets.remove(socket);
            safeClose(socket);
        }
    }

    private static void safeClose(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    @Override
    public synchronized void close() {
        if (!running) return;
        running = false;
        token = null;
        boundIp = null;
        try { listener.close(); } catch (IOException ignored) { }
        for (Socket s : sockets) safeClose(s);
        if (pool != null) pool.shutdownNow();
        synchronized (frameLock) {
            latest = null;
            frameLock.notifyAll();
        }
    }
}
