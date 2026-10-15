package com.ipcamerapro.mobile;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.graphics.ImageFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;


/** Camera2 preview + JPEG producer. Runs camera callbacks on a dedicated thread. */
public final class CameraStreamer implements AutoCloseable {
    public interface Listener {
        void onStatus(String status);
        void onError(String error);
        void onJpeg(byte[] jpeg);
        // Sent after the new Camera2 repeating capture has actually started.
        void onLensActivated(boolean front, String cameraId);
    }

    private final Context context;
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler handler;
    private final CameraManager manager;
    private final LegacyCameraStreamer legacy;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Surface previewSurface;
    private volatile long generation = 0;
    private volatile boolean active = false;

    public CameraStreamer(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        this.manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        boolean useLegacy = false;
        try {
            String[] ids = manager.getCameraIdList();
            for (String id : ids) {
                Integer level = manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                if (level != null &&
                        level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
                    useLegacy = true;
                    break;
                }
            }
        } catch (CameraAccessException | RuntimeException ignored) { }
        legacy = useLegacy ? new LegacyCameraStreamer(context, listener) : null;
        android.util.Log.i("IPCamCamera", "Camera API: " + (useLegacy ? "Camera1 HAL1" : "Camera2"));
        thread = new HandlerThread("ipcam-camera2");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void start(TextureView preview, boolean front, int width, int height, int fps) {
        if (legacy != null) {
            legacy.start(front, width, height, fps);
            return;
        }
        if (preview != null && !preview.isAvailable()) {
            listener.onError("Camera preview surface is not ready");
            return;
        }
        SurfaceTexture texture = preview == null ? null : preview.getSurfaceTexture();
        if (preview != null && texture == null) {
            listener.onError("Missing preview texture");
            return;
        }
        final long revision = ++generation;
        active = true;
        handler.post(() -> {
            // Android Camera2 release is asynchronous even after close().
            // Opening another lens immediately is unreliable on many Samsung
            // and other phones (MAX_CAMERAS_IN_USE / CAMERA_IN_USE).
            boolean changingLens = device != null || session != null;
            closeInternal();
            Runnable reopen = () -> {
                if (current(revision)) {
                    openInternal(revision, texture, front, width, height, fps, 0);
                }
            };
            if (changingLens) handler.postDelayed(reopen, 650);
            else reopen.run();
        });
    }

    public void stop() {
        if (legacy != null) { legacy.stop(); return; }
        ++generation;
        active = false;
        handler.post(this::closeInternal);
    }

    private boolean current(long revision) {
        return active && revision == generation;
    }

    private void openInternal(long revision, SurfaceTexture texture,
                              boolean front, int wantedWidth, int wantedHeight,
                              int fps, int retry) {
        if (!current(revision)) return;
        try {
            String id = chooseCamera(front);
            CameraCharacteristics info = manager.getCameraCharacteristics(id);
            StreamConfigurationMap configs =
                    info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (configs == null) throw new IllegalStateException("Camera has no YUV formats");
            Size[] sizes = configs.getOutputSizes(ImageFormat.YUV_420_888);
            if (sizes == null || sizes.length == 0) throw new IllegalStateException("YUV output unsupported");
            Size selected = selectSize(sizes, wantedWidth, wantedHeight);
            if (texture != null) {
                texture.setDefaultBufferSize(selected.getWidth(), selected.getHeight());
                previewSurface = new Surface(texture);
            }
            imageReader = ImageReader.newInstance(
                    selected.getWidth(), selected.getHeight(), ImageFormat.YUV_420_888, 3);
            Integer sensorOrientation = info.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int degrees = getDisplayDegrees();
            int rotation = ((sensorOrientation == null ? 0 : sensorOrientation)
                    + (front ? degrees : -degrees) + 360) % 360;
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null || !current(revision)) return;
                    byte[] frame = YuvEncoder.encode(image, rotation, 80);
                    if (current(revision)) listener.onJpeg(frame);
                } catch (Exception ex) {
                    if (current(revision)) listener.onError("Camera frame encoding failed: " + ex.getMessage());
                } finally {
                    if (image != null) image.close();
                }
            }, handler);

            listener.onStatus("Opening camera (" + selected.getWidth()
                    + " × " + selected.getHeight() + ")…");
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice camera) {
                    if (!current(revision)) { camera.close(); return; }
                    device = camera;
                    configure(camera, info, front, id, fps, revision);
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    if (current(revision)) {
                        listener.onError("Camera disconnected");
                        stop();
                    }
                }
                @Override public void onError(CameraDevice camera, int error) {
                    camera.close();
                    if (!current(revision)) return;
                    if (retry < 2 &&
                            (error == CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ||
                             error == CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE)) {
                        closeInternal();
                        listener.onStatus("Camera busy, retrying…");
                        handler.postDelayed(() -> {
                            if (current(revision)) openInternal(revision, texture, front,
                                    wantedWidth, wantedHeight, fps, retry + 1);
                        }, 850);
                    } else {
                        listener.onError("Camera2 error " + error);
                        stop();
                    }
                }
            }, handler);
        } catch (CameraAccessException ex) {
            if (current(revision) && retry < 2 &&
                    (ex.getReason() == CameraAccessException.CAMERA_IN_USE ||
                     ex.getReason() == CameraAccessException.MAX_CAMERAS_IN_USE)) {
                closeInternal();
                handler.postDelayed(() -> openInternal(revision, texture, front,
                        wantedWidth, wantedHeight, fps, retry + 1), 850);
            } else {
                if (current(revision)) listener.onError(
                        "Camera access failed: " + ex.getMessage());
                closeInternal();
            }
        } catch (IllegalArgumentException | SecurityException ex) {
            if (current(revision)) listener.onError("Camera access failed: " + ex.getMessage());
            closeInternal();
        } catch (RuntimeException ex) {
            if (current(revision)) listener.onError("Camera setup failed: " + ex.getMessage());
            closeInternal();
        }
    }

    private void configure(CameraDevice camera, CameraCharacteristics info,
                           boolean front, String cameraId, int desiredFps, long revision) {
        try {
            if (imageReader == null) return;
            Surface frames = imageReader.getSurface();
            java.util.List<Surface> surfaces = new java.util.ArrayList<>();
            if (previewSurface != null) surfaces.add(previewSurface);
            surfaces.add(frames);
            camera.createCaptureSession(surfaces,
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession configured) {
                            if (!current(revision)) { configured.close(); return; }
                            session = configured;
                            try {
                                CaptureRequest.Builder request =
                                        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                if (previewSurface != null) request.addTarget(previewSurface);
                                request.addTarget(frames);
                                request.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                                request.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                Range<Integer> rate = pickRate(info, desiredFps);
                                if (rate != null) {
                                    request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, rate);
                                }
                                configured.setRepeatingRequest(request.build(), null, handler);
                                listener.onLensActivated(front, cameraId);
                                listener.onStatus("Streaming: " +
                                        (front ? "front" : "back") + " camera ready");
                            } catch (CameraAccessException | RuntimeException ex) {
                                if (current(revision)) listener.onError(
                                        "Cannot start capture: " + ex.getMessage());
                                stop();
                            }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession failed) {
                            if (current(revision)) {
                                listener.onError("Camera refused preview + YUV configuration");
                                stop();
                            }
                        }
                    }, handler);
        } catch (CameraAccessException | RuntimeException ex) {
            if (current(revision)) listener.onError("Camera session failed: " + ex.getMessage());
            stop();
        }
    }

    private static Range<Integer> pickRate(CameraCharacteristics info, int fps) {
        Range<Integer>[] ranges = info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) return null;
        Range<Integer> best = null;
        int score = Integer.MAX_VALUE;
        for (Range<Integer> r : ranges) {
            int penalty = fps < r.getLower() ? r.getLower() - fps
                    : fps > r.getUpper() ? fps - r.getUpper() : 0;
            int delta = penalty * 100 + Math.abs(r.getUpper() - fps) * 3
                    + Math.abs(r.getLower() - fps);
            if (delta < score) { score = delta; best = r; }
        }
        return best;
    }

    private static Size selectSize(Size[] sizes, int w, int h) {
        Size best = sizes[0];
        long bestScore = Long.MAX_VALUE;
        for (Size s : sizes) {
            int sw = s.getWidth();
            int sh = s.getHeight();
            if (sw <= 0 || sh <= 0) continue;
            double aspect = (double) sw / sh;
            double target = (double) w / h;
            long score = (long) (Math.abs(aspect - target) * 100000)
                    + Math.abs(sw - w) + Math.abs(sh - h);
            if ((long) sw * sh > (long) w * h * 2) score += 100000;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    private String chooseCamera(boolean front) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        if (ids.length == 0) throw new IllegalStateException("No camera on phone");
        int direction = front ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK;
        for (String id : ids) {
            Integer facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == direction) return id;
        }
        // Never silently choose the first camera and display the wrong lens.
        throw new IllegalStateException(front ? "Front camera not available" :
                "Back camera not available");
    }

    public boolean hasCamera(boolean front) {
        if (legacy != null) return legacy.hasCamera(front);
        try {
            chooseCamera(front);
            return true;
        } catch (CameraAccessException | RuntimeException ex) {
            return false;
        }
    }

    private int getDisplayDegrees() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        int rotation = wm == null ? Surface.ROTATION_0 : wm.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_90: return 90;
            case Surface.ROTATION_180: return 180;
            case Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void closeInternal() {
        if (session != null) {
            try { session.stopRepeating(); } catch (Exception ignored) { }
            try { session.close(); } catch (Exception ignored) { }
            session = null;
        }
        if (device != null) {
            try { device.close(); } catch (Exception ignored) { }
            device = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) { }
            imageReader = null;
        }
        if (previewSurface != null) {
            try { previewSurface.release(); } catch (Exception ignored) { }
            previewSurface = null;
        }
    }

    @Override public void close() {
        if (legacy != null) legacy.close();
        ++generation;
        active = false;
        handler.post(() -> {
            closeInternal();
            thread.quitSafely();
        });
    }
}
