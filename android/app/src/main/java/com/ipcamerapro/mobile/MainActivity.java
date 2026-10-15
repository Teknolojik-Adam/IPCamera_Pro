package com.ipcamerapro.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Native Android screen: a controller for the independent foreground camera service. */
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 501;
    private static final int BG = Color.rgb(11, 16, 26);
    private static final int PANEL = Color.rgb(22, 32, 47);
    private static final int BORDER = Color.rgb(49, 66, 90);
    private static final int TEXT = Color.rgb(236, 243, 255);
    private static final int MUTED = Color.rgb(162, 177, 198);
    private static final int ACCENT = Color.rgb(55, 133, 233);
    private static final int GREEN = Color.rgb(91, 214, 157);
    private static final int AMBER = Color.rgb(255, 202, 117);

    private TextView status, address, wifi, frameCount, facing, pairingInfo;
    private TextView previewHint;
    private ImageView preview;
    private Button start, stop, switchCamera, copy, pair;
    private Spinner quality;

    private IPCameraService service;
    private boolean bound, front, pairing;
    private long previewFrame = -1;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateUi();
            main.postDelayed(this, 250);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((IPCameraService.LocalBinder) binder).getService();
            bound = true;
            updateUi();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            // Binding still exists; onStop must unbind even after process disconnect.
            service = null;
            updateUi();
        }
    };

    private int dp(float value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
    private GradientDrawable background(int color, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(color);
        v.setTextSize(sp);
        v.setLineSpacing(dp(3), 1f);
        if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD);
        return v;
    }
    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private Button button(String title, int color) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setBackground(background(color, 0, 11));
        return b;
    }
    private LinearLayout.LayoutParams lineParams(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        return lp;
    }
    private void enabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : .48f);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        refreshNetwork();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = vertical();
        root.setPadding(dp(18), dp(18), dp(18), dp(36));
        scroll.addView(root);
        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int top = insets.getInsets(WindowInsets.Type.systemBars()).top;
                int bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
                view.setPadding(dp(18), dp(18) + top, dp(18), dp(36) + bottom);
                return insets;
            });
        }

        root.addView(text("◉  IPCamera Pro", 29, TEXT, true));
        root.addView(text("Telefon kameranı Fedora'da webcam olarak kullan.",
                14, MUTED, false), lineParams(6));

        status = text("Hazır · Yayın kapalı", 14, AMBER, true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(background(PANEL, BORDER, 12));
        root.addView(status, lineParams(18));

        FrameLayout display = new FrameLayout(this);
        display.setBackground(background(Color.BLACK, BORDER, 15));
        display.setClipToOutline(true);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(255));
        previewParams.topMargin = dp(16);
        root.addView(display, previewParams);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(Color.BLACK);
        display.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewHint = text("CANLI ÖNİZLEME\nYayını başlatınca görüntü burada açılır.",
                15, MUTED, false);
        previewHint.setGravity(Gravity.CENTER);
        previewHint.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        previewHint.setBackgroundColor(Color.rgb(5, 10, 17));
        display.addView(previewHint, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions, lineParams(14));
        start = button("▶  Yayını Başlat", ACCENT);
        stop = button("■  Durdur", Color.rgb(132, 48, 68));
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(50), 2);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(50), 1);
        b.leftMargin = dp(10);
        actions.addView(start, a);
        actions.addView(stop, b);
        start.setOnClickListener(v -> requestStart());
        stop.setOnClickListener(v -> sendServiceAction(IPCameraService.ACTION_STOP));
        enabled(stop, false);

        LinearLayout options = vertical();
        options.setPadding(dp(16), dp(16), dp(16), dp(16));
        options.setBackground(background(PANEL, BORDER, 15));
        root.addView(options, lineParams(18));
        options.addView(text("Kamera ayarları", 18, TEXT, true));
        facing = text("Aktif kamera: Arka", 13, MUTED, false);
        options.addView(facing, lineParams(10));
        switchCamera = button("Ön / Arka kamera değiştir", Color.rgb(41, 62, 85));
        switchCamera.setOnClickListener(v -> switchCamera());
        options.addView(switchCamera, lineParams(8));
        options.addView(text("Çözünürlük ve hedef FPS", 13, MUTED, false), lineParams(14));
        quality = new Spinner(this);
        String[] presets = {
                "640 × 480 · 10 FPS (az veri)",
                "1280 × 720 · 10 FPS",
                "1280 × 720 · 15 FPS"
        };
        quality.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, presets));
        quality.setSelection(1);
        options.addView(quality, lineParams(7));

        LinearLayout share = vertical();
        share.setPadding(dp(16), dp(16), dp(16), dp(16));
        share.setBackground(background(PANEL, BORDER, 15));
        root.addView(share, lineParams(18));
        share.addView(text("Bilgisayara otomatik bağlan", 18, TEXT, true));
        share.addView(text("Fedora'da IPCamera Pro - Telefonu Bağla kısayolunu aç. "
                + "Oradaki 6 haneli kodu buraya gir; IP ve uzun URL yazmana gerek yok.",
                13, MUTED, false), lineParams(7));
        pair = button("🔗  PC'ye Bağlan · 6 Haneli Kod", ACCENT);
        enabled(pair, false);
        pair.setOnClickListener(v -> showPairDialog());
        share.addView(pair, lineParams(13));
        pairingInfo = text("Önce kamera yayınını başlat.", 13, MUTED, false);
        share.addView(pairingInfo, lineParams(9));
        wifi = text("Wi-Fi kontrol ediliyor…", 13, MUTED, false);
        share.addView(wifi, lineParams(8));
        address = text("Yayını başlatınca bağlantı adresi burada görünecek.",
                13, TEXT, false);
        address.setTextIsSelectable(true);
        address.setPadding(dp(12), dp(12), dp(12), dp(12));
        address.setBackground(background(BG, BORDER, 10));
        share.addView(address, lineParams(12));
        copy = button("Gelişmiş: Uzun bağlantı adresini kopyala",
                Color.rgb(41, 62, 85));
        copy.setOnClickListener(v -> copyUrl());
        enabled(copy, false);
        share.addView(copy, lineParams(9));
        share.addView(text(
                "Telefon ve PC aynı Wi-Fi ağında olmalı. Yayın arka planda bildirimle "
                + "devam edebilir. Uzun bağlantı adresi yalnızca eski sürümler için; "
                + "erişim anahtarını kimseyle paylaşma.",
                12, MUTED, false), lineParams(10));

        frameCount = text("Görüntü kareleri: 0", 12, MUTED, false);
        root.addView(frameCount, lineParams(15));
    }

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    private void refreshNetwork() {
        String ip = NetworkUtils.localWifiIpv4(this);
        wifi.setText(ip == null ? "Wi-Fi / yerel IPv4 bulunamadı"
                : "Telefon IP adresi: " + ip);
    }

    private void requestStart() {
        if (service != null && service.isStreaming()) return;
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("askedNotification", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("askedNotification", true).apply();
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        startServiceCamera();
    }

    private void startServiceCamera() {
        refreshNetwork();
        if (NetworkUtils.localWifiIpv4(this) == null) {
            setStatus("Önce telefonu Wi-Fi'ye bağla.", AMBER);
            return;
        }
        int preset = quality.getSelectedItemPosition();
        Intent startIntent = new Intent(this, IPCameraService.class);
        startIntent.setAction(IPCameraService.ACTION_START);
        startIntent.putExtra(IPCameraService.EXTRA_FRONT, front);
        startIntent.putExtra(IPCameraService.EXTRA_WIDTH, preset == 0 ? 640 : 1280);
        startIntent.putExtra(IPCameraService.EXTRA_HEIGHT, preset == 0 ? 480 : 720);
        startIntent.putExtra(IPCameraService.EXTRA_FPS, preset == 2 ? 15 : 10);
        try {
            startForegroundService(startIntent);
            setStatus("Kamera yayın servisi başlatılıyor…", GREEN);
        } catch (RuntimeException ex) {
            setStatus("Servis başlatılamadı: " + ex.getMessage(), AMBER);
        }
    }

    private void sendServiceAction(String action) {
        if (service == null || !service.isStreaming()) return;
        Intent intent = new Intent(this, IPCameraService.class);
        intent.setAction(action);
        try { startService(intent); }
        catch (RuntimeException ex) { setStatus("İşlem başarısız: " + ex.getMessage(), AMBER); }
    }

    private void switchCamera() {
        IPCameraService s = service;
        if (s != null && s.isStreaming()) {
            if (s.isSwitching()) return;
            boolean targetFront = !s.isFront();
            if (targetFront ? !s.hasFrontCamera() : !s.hasBackCamera()) {
                setStatus(targetFront ? "Ön kamera bulunamadı." :
                        "Arka kamera bulunamadı.", AMBER);
                return;
            }
            sendServiceAction(IPCameraService.ACTION_SWITCH);
            return;
        }
        if (s != null && !(front ? s.hasBackCamera() : s.hasFrontCamera())) {
            setStatus(front ? "Arka kamera bulunamadı." : "Ön kamera bulunamadı.", AMBER);
            return;
        }
        front = !front;
        facing.setText("Başlangıç kamerası: " + (front ? "Ön" : "Arka"));
    }

    private void showPairDialog() {
        IPCameraService s = service;
        if (s == null || !s.isStreaming()) {
            pairingInfo.setText("Önce Yayını Başlat düğmesine bas.");
            return;
        }
        EditText pin = new EditText(this);
        pin.setSingleLine(true);
        pin.setHint("6 haneli kod");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pin.setTextSize(24);
        pin.setPadding(dp(22), dp(15), dp(22), dp(15));
        new AlertDialog.Builder(this)
                .setTitle("PC eşleştirme kodu")
                .setMessage("Fedora'daki IPCamera Pro terminalinde gösterilen 6 rakamı gir.")
                .setView(pin)
                .setNegativeButton("İptal", null)
                .setPositiveButton("Bağlan", (dialog, which) -> {
                    String digits = pin.getText().toString().trim();
                    if (!digits.matches("[0-9]{6}")) {
                        pairingInfo.setText("Kod tam 6 rakam olmalı.");
                        return;
                    }
                    String url = s.getStreamUrl();
                    pairing = true;
                    enabled(pair, false);
                    pairingInfo.setText("Fedora aynı Wi-Fi ağında aranıyor…");
                    PairDiscovery.pairAsync(this, digits, url, (success, message) -> {
                        pairing = false;
                        if (!isFinishing() && !isDestroyed()) {
                            pairingInfo.setText(message);
                            pairingInfo.setTextColor(success ? GREEN : AMBER);
                        }
                    });
                }).show();
    }

    private void copyUrl() {
        String url = service == null ? null : service.getStreamUrl();
        if (url == null) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("IPCamera Pro", url));
        Toast.makeText(this, "Bağlantı adresi kopyalandı", Toast.LENGTH_SHORT).show();
    }

    private void updateUi() {
        IPCameraService s = service;
        boolean active = s != null && s.isStreaming();
        enabled(start, !active);
        enabled(stop, active);
        enabled(copy, active && s.getStreamUrl() != null);
        enabled(pair, active && !pairing && s.getStreamUrl() != null);
        enabled(switchCamera, !active || !s.isSwitching());
        enabled(quality, !active);
        if (s == null) {
            enabled(pair, false);
            frameCount.setText("Görüntü kareleri: 0");
            if (address != null) address.setText("Yayını başlatınca bağlantı adresi burada görünecek.");
            previewHint.setVisibility(View.VISIBLE);
            return;
        }

        if (!active) {
            setStatus(s.getStatus(), AMBER);
            address.setText("Yayın kapalı.");
            if (!pairing) pairingInfo.setText("Önce kamera yayınını başlat.");
            frameCount.setText("Görüntü kareleri: 0");
            previewHint.setVisibility(View.VISIBLE);
            preview.setImageDrawable(null);
            previewFrame = -1;
        } else {
            setStatus(s.getStatus(), GREEN);
            String url = s.getStreamUrl();
            address.setText(url == null ?
                    "Wi-Fi bağlantısı değişti veya koptu. Yayını durdurup yeniden başlat." :
                    "Yayın hazır · PC'ye Bağlan düğmesini kullan. Uzun URL gerekmiyor.");
            if (s.isSwitching()) {
                preview.setImageDrawable(null);
                previewHint.setVisibility(View.VISIBLE);
                previewFrame = -1;
            }
            front = s.isFront();
            facing.setText("Aktif kamera: " + (front ? "Ön" : "Arka")
                    + (s.isSwitching() ? " · Değiştiriliyor…" : ""));
            preview.setScaleX(s.isFront() ? -1f : 1f);
            long count = s.getFrameCount();
            frameCount.setText("Görüntü kareleri: " + count);
            if (count != previewFrame) {
                byte[] jpeg = s.getLatestJpeg();
                if (jpeg != null) {
                    BitmapFactory.Options decode = new BitmapFactory.Options();
                    decode.inSampleSize = 2;
                    Bitmap picture = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, decode);
                    if (picture != null) {
                        preview.setImageBitmap(picture);
                        previewHint.setVisibility(View.GONE);
                        previewFrame = count;
                    }
                }
            }
        }
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startServiceCamera();
        } else {
            setStatus("Kamera izni verilmedi.", AMBER);
        }
    }

    @Override protected void onStart() {
        super.onStart();
        Intent intent = new Intent(this, IPCameraService.class);
        try {
            bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (RuntimeException ex) {
            setStatus("Yayın servisine bağlanılamadı: " + ex.getMessage(), AMBER);
        }
        main.post(ticker);
    }

    @Override protected void onStop() {
        main.removeCallbacks(ticker);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        service = null;
        // Streaming remains active in the foreground service after app goes to background.
        super.onStop();
    }
}
