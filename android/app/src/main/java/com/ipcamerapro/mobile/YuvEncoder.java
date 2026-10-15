package com.ipcamerapro.mobile;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Real-time Camera2 YUV_420_888 -> tightly packed NV21 -> JPEG.
 *
 * A repeating Camera2 JPEG request can stall the sensor on many phones.
 * Processing YUV frames in software allows the camera to run at preview rates.
 * Converts plane data using rowStride and pixelStride, so it works on devices
 * whose U/V planes are interleaved or have padded rows.
 */
public final class YuvEncoder {
    private YuvEncoder() { }

    private static byte[] packNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("YUV frame must have even width and height");
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes.length != 3) throw new IllegalArgumentException("Three YUV planes required");

        int yBytes = width * height;
        byte[] data = new byte[yBytes + yBytes / 2];
        ByteBuffer y = planes[0].getBuffer();
        int yStride = planes[0].getRowStride();
        int yPixel = planes[0].getPixelStride();
        for (int row = 0; row < height; row++) {
            int src = row * yStride;
            int out = row * width;
            for (int col = 0; col < width; col++) {
                data[out + col] = y.get(src + col * yPixel);
            }
        }

        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int uStride = planes[1].getRowStride();
        int vStride = planes[2].getRowStride();
        int uPixel = planes[1].getPixelStride();
        int vPixel = planes[2].getPixelStride();

        for (int row = 0; row < height / 2; row++) {
            int out = yBytes + row * width;
            for (int col = 0; col < width / 2; col++) {
                data[out + 2 * col] = v.get(row * vStride + col * vPixel);
                data[out + 2 * col + 1] = u.get(row * uStride + col * uPixel);
            }
        }
        return data;
    }

    private static byte[] rotateNv21(byte[] src, int w, int h, int angle) {
        if (angle == 0) return src;
        if (angle != 90 && angle != 180 && angle != 270)
            throw new IllegalArgumentException("Rotation must be 0/90/180/270");

        byte[] dst = new byte[src.length];
        int outW = angle == 180 ? w : h;
        int outH = angle == 180 ? h : w;
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int dx;
                int dy;
                if (angle == 90) { dx = h - row - 1; dy = col; }
                else if (angle == 180) { dx = w - col - 1; dy = h - row - 1; }
                else { dx = row; dy = w - col - 1; }
                dst[dy * outW + dx] = src[row * w + col];
            }
        }

        int chromaOffset = w * h;
        int uvW = w / 2, uvH = h / 2, outUvW = outW / 2;
        for (int row = 0; row < uvH; row++) {
            for (int col = 0; col < uvW; col++) {
                int dx;
                int dy;
                if (angle == 90) { dx = uvH - row - 1; dy = col; }
                else if (angle == 180) { dx = uvW - col - 1; dy = uvH - row - 1; }
                else { dx = row; dy = uvW - col - 1; }
                int source = chromaOffset + (row * uvW + col) * 2;
                int target = chromaOffset + (dy * outUvW + dx) * 2;
                dst[target] = src[source];
                dst[target + 1] = src[source + 1];
            }
        }
        return dst;
    }

    /**
     * Direct legacy Camera1 NV21 preview -> JPEG. Do not route through the
     * Camera2 ImageReader shim, which some old custom-ROM HALs cannot support.
     */
    public static byte[] encodeNv21(byte[] nv21, int width, int height,
                                    int rotation, int quality) throws IOException {
        if (width < 2 || height < 2 || (width & 1) != 0 || (height & 1) != 0
                || nv21 == null || nv21.length < (long)width * height * 3 / 2)
            throw new IOException("Invalid Camera1 NV21 preview buffer");
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270)
            rotation = 0;
        byte[] pixels = rotateNv21(nv21, width, height, rotation);
        int outW = (rotation == 90 || rotation == 270) ? height : width;
        int outH = (rotation == 90 || rotation == 270) ? width : height;
        YuvImage frame = new YuvImage(pixels, ImageFormat.NV21, outW, outH, null);
        ByteArrayOutputStream stream = new ByteArrayOutputStream(outW * outH / 5);
        if (!frame.compressToJpeg(new Rect(0, 0, outW, outH), quality, stream))
            throw new IOException("Camera1 NV21 compression failed");
        return stream.toByteArray();
    }

    public static byte[] encode(Image image, int rotation, int quality) throws IOException {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IOException("Expected YUV_420_888 frame");
        }
        int originalWidth = image.getWidth(), originalHeight = image.getHeight();
        byte[] raw = packNv21(image);
        byte[] pixels = rotateNv21(raw, originalWidth, originalHeight, rotation);
        int width = (rotation == 90 || rotation == 270) ? originalHeight : originalWidth;
        int height = (rotation == 90 || rotation == 270) ? originalWidth : originalHeight;
        YuvImage output = new YuvImage(pixels, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(width * height / 5);
        if (!output.compressToJpeg(new Rect(0, 0, width, height), quality, bytes)) {
            throw new IOException("NV21 JPEG compression failed");
        }
        return bytes.toByteArray();
    }
}
