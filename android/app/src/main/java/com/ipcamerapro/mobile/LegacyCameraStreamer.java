package com.ipcamerapro.mobile;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import java.io.IOException;
import java.util.List;

/**
 * Legacy HAL1 fallback for custom Android ROMs exposing only Camera1.
 * Pulls NV21 preview callbacks instead of relying on Camera2's shim.
 */
@SuppressWarnings("deprecation")
public final class LegacyCameraStreamer implements AutoCloseable {
    private static final String TAG = "IPCamLegacy";
    private final HandlerThread thread;
    private final Handler handler;
    private final CameraStreamer.Listener listener;
    private Camera camera;
    private SurfaceTexture surface;
    private volatile long generation;
    private volatile boolean active;
    private int failures;

    public LegacyCameraStreamer(Context context, CameraStreamer.Listener listener) {
        this.listener = listener;
        thread = new HandlerThread("ipcam-camera1");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public boolean hasCamera(boolean front) {
        try {
            for (int i=0; i<Camera.getNumberOfCameras(); i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                if (front == (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT))
                    return true;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot enumerate Camera1", e);
        }
        return false;
    }

    private boolean current(long rev) { return active && generation == rev; }

    public void start(boolean front, int w, int h, int fps) {
        long rev = ++generation;
        active = true;
        handler.post(() -> {
            boolean replacing = camera != null;
            closeInternal();
            if (replacing) {
                handler.postDelayed(() -> open(rev, front, w, h, fps, 0), 650);
            } else {
                open(rev, front, w, h, fps, 0);
            }
        });
    }

    private int choose(boolean requestedFront) {
        int count = Camera.getNumberOfCameras();
        if (count == 0) throw new IllegalStateException("ROM exposes no camera");
        for (int i=0; i<count; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(i, info);
            if (requestedFront == (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT))
                return i;
        }
        // On a custom ROM with one enumerated camera, prefer a working
        // capture over refusing to start when the default lens is missing.
        if (count == 1) return 0;
        throw new IllegalStateException("Requested lens is not exposed by ROM");
    }

    private static Camera.Size bestSize(List<Camera.Size> sizes, int w, int h) {
        if (sizes == null || sizes.isEmpty())
            throw new IllegalStateException("No preview sizes");
        Camera.Size best=sizes.get(0);
        double bestScore=Double.MAX_VALUE;
        for (Camera.Size s:sizes) {
            double score = Math.abs((double)s.width/s.height-(double)w/h)*10000
                +Math.abs(s.width-w)+Math.abs(s.height-h);
            if (score < bestScore) { best=s; bestScore=score; }
        }
        return best;
    }

    private static int[] bestFps(List<int[]> ranges, int fps) {
        if (ranges == null || ranges.isEmpty()) return null;
        int[] best=null;
        long score=Long.MAX_VALUE;
        int wanted=fps*1000;
        for (int[] r:ranges) {
            if (r==null || r.length<2) continue;
            long penalty = wanted<r[0] ? r[0]-wanted : wanted>r[1] ? wanted-r[1] : 0;
            long delta=100L*penalty + 3L*Math.abs(r[1]-wanted)+Math.abs(r[0]-wanted);
            if (delta<score) { score=delta; best=r; }
        }
        return best;
    }

    private void open(long rev, boolean requestedFront, int w, int h, int fps, int attempt) {
        if (!current(rev)) return;
        try {
            int id=choose(requestedFront);
            Camera.CameraInfo info=new Camera.CameraInfo();
            Camera.getCameraInfo(id,info);
            boolean realFront=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT;
            listener.onStatus("Eski ROM kamera HAL1 açılıyor (ID " + id + ")");
            Camera c=Camera.open(id);
            if (c==null) throw new IllegalStateException("Camera.open returned null");
            camera=c;
            Camera.Parameters settings=c.getParameters();
            Camera.Size size=bestSize(settings.getSupportedPreviewSizes(),w,h);
            List<Integer> formats=settings.getSupportedPreviewFormats();
            if (formats==null || !formats.contains(ImageFormat.NV21))
                throw new IllegalStateException("NV21 preview unsupported");
            settings.setPreviewSize(size.width,size.height);
            settings.setPreviewFormat(ImageFormat.NV21);
            int[] rate=bestFps(settings.getSupportedPreviewFpsRange(),fps);
            if (rate!=null) settings.setPreviewFpsRange(rate[0],rate[1]);
            List<String> focuses=settings.getSupportedFocusModes();
            if (focuses!=null && focuses.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
                settings.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            c.setParameters(settings);

            // Camera1 preview target for capture in a foreground service.
            surface=new SurfaceTexture(0);
            surface.setDefaultBufferSize(size.width,size.height);
            c.setPreviewTexture(surface);
            int len=size.width*size.height*3/2;
            for (int i=0;i<4;i++) c.addCallbackBuffer(new byte[len]);
            int rot=info.orientation;
            if (rot!=0 && rot!=90 && rot!=180 && rot!=270) rot=0;
            final int orientation=rot;
            failures=0;
            c.setPreviewCallbackWithBuffer((nv21,source)->{
                if (nv21==null) return;
                try {
                    if (current(rev)) listener.onJpeg(
                        YuvEncoder.encodeNv21(nv21,size.width,size.height,orientation,78));
                } catch (IOException | RuntimeException error) {
                    failures++;
                    if (failures==1 || failures==10)
                        Log.e(TAG,"Frame encode error",error);
                    if (failures==10 && current(rev))
                        listener.onError("Legacy JPEG frame error: "+error.getMessage());
                } finally {
                    try { source.addCallbackBuffer(nv21); }
                    catch (RuntimeException ignored) { }
                }
            });
            c.startPreview();
            listener.onLensActivated(realFront,String.valueOf(id));
            listener.onStatus((realFront?"Ön":"Arka")+" kamera açıldı (HAL1 "+size.width+"x"+size.height+")");
            Log.i(TAG,"Camera1 started id="+id+" front="+realFront+" size="+size.width+"x"+size.height);
        } catch (IOException | RuntimeException ex) {
            Log.e(TAG,"HAL1 startup error attempt="+attempt,ex);
            closeInternal();
            if (!current(rev)) return;
            if (attempt<2) {
                listener.onStatus("Eski kamera sürücüsü yeniden deneniyor...");
                handler.postDelayed(() -> open(rev,requestedFront,w,h,fps,attempt+1),950);
            } else listener.onError("Camera HAL1 error: "+ex.getMessage());
        }
    }

    public void stop() {
        ++generation;
        active=false;
        handler.post(this::closeInternal);
    }

    private void closeInternal() {
        if (camera!=null) {
            try { camera.setPreviewCallbackWithBuffer(null); } catch (RuntimeException ignored) {}
            try { camera.stopPreview(); } catch (RuntimeException ignored) {}
            try { camera.release(); } catch (RuntimeException ignored) {}
            camera=null;
        }
        if (surface!=null) {
            try { surface.release(); } catch (RuntimeException ignored) {}
            surface=null;
        }
    }

    @Override public void close() {
        ++generation;
        active=false;
        handler.post(()->{closeInternal();thread.quitSafely();});
    }
}
