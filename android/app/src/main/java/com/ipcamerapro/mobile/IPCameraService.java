package com.ipcamerapro.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicitly user-started camera foreground service.
 * Keeps the camera connection alive while the Activity is in the background.
 * The user can stop it in the app or via the ongoing Android notification.
 */
public final class IPCameraService extends Service {
    public static final String ACTION_START = "com.ipcamerapro.mobile.START";
    public static final String ACTION_STOP = "com.ipcamerapro.mobile.STOP";
    public static final String ACTION_SWITCH = "com.ipcamerapro.mobile.SWITCH";
    public static final String EXTRA_FRONT = "front";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_FPS = "fps";
    private static final String CHANNEL_ID = "ipcam-stream";
    private static final int NOTIFICATION_ID = 1408;
    private static final String TAG = "IPCamService";

    public final class LocalBinder extends Binder {
        public IPCameraService getService() { return IPCameraService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong frames = new AtomicLong();
    private volatile byte[] latestJpeg;
    private volatile String status = "Hazır · Yayın kapalı";
    private volatile boolean running;
    private volatile boolean front;
    private volatile boolean switching;
    private int width = 1280, height = 720, fps = 10;
    private MjpegServer server;
    private CameraStreamer camera;
    private WifiSessionGuard wifiGuard;
    private static final long WIFI_CHECK_INTERVAL_MS = 2000;
    private final Runnable wifiWatchdog = new Runnable() {
        @Override public void run() {
            if (!running || wifiGuard == null) return;
            String currentIp = NetworkUtils.localWifiIpv4(IPCameraService.this);
            if (wifiGuard.shouldStop(currentIp)) {
                stopCapture();
                status = "Wi-Fi bağlantısı değişti veya koptu; yayını yeniden başlat.";
                Log.w(TAG, "Wi-Fi address changed or lost; camera stream stopped");
                stopSelf();
                return;
            }
            main.postDelayed(this, WIFI_CHECK_INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        ensureChannel();
        camera = new CameraStreamer(this, new CameraStreamer.Listener() {
            @Override public void onStatus(String message) {
                if (running) {
                    status = message;
                    Log.i(TAG, message);
                }
            }
            @Override public void onJpeg(byte[] bytes) {
                if (running) {
                    latestJpeg = bytes;
                    MjpegServer source = server;
                    if (source != null) source.offerJpeg(bytes);
                    long count = frames.incrementAndGet();
                    if (count == 1 || count == 10 || count % 100 == 0) {
                        Log.i(TAG, "Camera JPEG frames=" + count + " bytes=" + bytes.length);
                    }
                }
            }
            @Override public void onError(String error) {
                Log.e(TAG, "Camera callback failure: " + error);
                main.post(() -> {
                    if (!running) return;
                    if (switching) {
                        // If new lens fails, keep the authenticated HTTP session,
                        // and return to the last verified working camera instead.
                        switching = false;
                        status = "Kamera değişimi başarısız; eski kamera geri açılıyor: " + error;
                        camera.start(null, front, width, height, fps);
                    } else {
                        stopCapture();
                        status = "Kamera hatası: " + error;
                    }
                });
            }
            @Override public void onLensActivated(boolean activatedFront, String cameraId) {
                if (!running) return;
                front = activatedFront;
                Log.i(TAG, "Lens activated: " + cameraId + " actualFront=" + activatedFront);
                switching = false;
                status = (front ? "Ön" : "Arka") + " kamera aktif (ID: " + cameraId + ")";
            }
        });
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SWITCH.equals(action)) {
            swapCamera();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (!running) {
                boolean wantedFront = intent.getBooleanExtra(EXTRA_FRONT, false);
                int wantedWidth = intent.getIntExtra(EXTRA_WIDTH, 1280);
                int wantedHeight = intent.getIntExtra(EXTRA_HEIGHT, 720);
                int wantedFps = intent.getIntExtra(EXTRA_FPS, 10);
                beginCapture(wantedFront, wantedWidth, wantedHeight, wantedFps);
            }
        }
        return START_NOT_STICKY;
    }

    private void ensureChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "IPCamera Pro kamera yayını", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Aktif IP kamera yayınını ve durdurma seçeneğini gösterir");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    private Notification makeNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent launch = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, IPCameraService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent terminate = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("IPCamera Pro · Kamera yayında")
                .setContentText("Wi-Fi kamera yayını aktif · Bildirimde Durdur seçeneği")
                .setContentIntent(launch)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause, "Yayını durdur", terminate).build())
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private synchronized void beginCapture(boolean useFront, int desiredWidth,
                                           int desiredHeight, int desiredFps) {
        if (running) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = "Kamera izni gerekli";
            stopSelf();
            return;
        }
        String ip = NetworkUtils.localWifiIpv4(this);
        if (ip == null) {
            status = "Telefon Wi-Fi ağına bağlı değil";
            stopSelf();
            return;
        }
        if (!((desiredWidth == 640 && desiredHeight == 480)
                || (desiredWidth == 1280 && desiredHeight == 720))
                || (desiredFps != 10 && desiredFps != 15 && desiredFps != 20)) {
            status = "Geçersiz kamera ayarı";
            stopSelf();
            return;
        }
        try {
            // Android 14+ checks the CAMERA foreground-service type and grants here.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, makeNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTIFICATION_ID, makeNotification());
            }
            MjpegServer session = new MjpegServer();
            session.start(ip);
            server = session;
            front = useFront;
            switching = false;
            width = desiredWidth;
            height = desiredHeight;
            fps = desiredFps;
            running = true;
            frames.set(0);
            latestJpeg = null;
            status = "Telefon kamerası açılıyor…";
            camera.start(null, front, width, height, fps);
            wifiGuard = new WifiSessionGuard(ip, 3);
            main.removeCallbacks(wifiWatchdog);
            main.postDelayed(wifiWatchdog, WIFI_CHECK_INTERVAL_MS);
        } catch (IOException | RuntimeException error) {
            stopCapture();
            status = "Yayın başlatılamadı: " + error.getMessage();
            stopSelf();
        }
    }

    private synchronized void swapCamera() {
        if (!running || switching) return;
        boolean wantedFront = !front;
        if (!camera.hasCamera(wantedFront)) {
            status = wantedFront
                    ? "Ön kamera aygıtı bulunamadı."
                    : "Arka kamera aygıtı bulunamadı.";
            return;
        }
        switching = true;
        // Discard old-lens frames while Camera2 closes and opens the new lens.
        MjpegServer currentServer = server;
        if (currentServer != null) currentServer.clearFrame();
        latestJpeg = null;
        frames.set(0);
        status = (wantedFront ? "Ön" : "Arka") + " kameraya geçiliyor…";
        // start() serializes the old Camera2 close and delayed new open.
        // Keep the same MJPEG server and URL/token during camera switching.
        camera.start(null, wantedFront, width, height, fps);
    }

    private synchronized void stopCapture() {
        main.removeCallbacks(wifiWatchdog);
        wifiGuard = null;
        running = false;
        switching = false;
        if (camera != null) camera.stop();
        MjpegServer active = server;
        server = null;
        if (active != null) active.close();
        latestJpeg = null;
        frames.set(0);
        stopForeground(STOP_FOREGROUND_REMOVE);
        status = "Yayın durduruldu";
    }

    public boolean isStreaming() { return running; }
    public boolean isFront() { return front; }
    public boolean isSwitching() { return switching; }
    public boolean hasFrontCamera() { return camera != null && camera.hasCamera(true); }
    public boolean hasBackCamera() { return camera != null && camera.hasCamera(false); }
    public long getFrameCount() { return frames.get(); }
    public byte[] getLatestJpeg() { return latestJpeg; }
    public String getStatus() { return status; }

    public String getStreamUrl() {
        MjpegServer s = server;
        if (!running || s == null) return null;
        String token = s.getToken();
        String ip = NetworkUtils.localWifiIpv4(this);
        // A Wi-Fi change invalidates the listening address. Never advertise a
        // new address while the old socket is still bound to the old IP.
        if (token == null || ip == null || !ip.equals(s.getBoundIp())) return null;
        return "http://" + ip + ":" + MjpegServer.PORT + "/video?token=" + token;
    }

    @Override public void onDestroy() {
        stopCapture();
        if (camera != null) camera.close();
        super.onDestroy();
    }
}
