package io.github.antonvanke.assistlab;

import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.WindowManager;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONObject;

final class ScreenCapture implements AutoCloseable {
    private final Context context;
    private final MediaProjection projection;
    private final ScreenServer server;
    private final UUID device, session = UUID.randomUUID();
    private final HandlerThread thread = new HandlerThread("screen-capture");
    private final Handler worker;
    private final Consumer<String> stopped;
    private final int longEdge, fps, quality, density;
    private final ByteArrayOutputStream jpeg = new ByteArrayOutputStream(128 * 1024);
    private volatile boolean closed;
    private volatile int sourceWidth, sourceHeight;
    private volatile long sequence;
    private ImageReader reader;
    private VirtualDisplay display;
    private Bitmap bitmap;
    private byte[] pixels;
    private final MediaProjection.Callback callback = new MediaProjection.Callback() {
        @Override public void onStop() { fail("系统已停止屏幕共享，请回 App 重新授权"); }
        @Override public void onCapturedContentResize(int width, int height) {
            if (closed || (width == sourceWidth && height == sourceHeight)) return;
            try { resize(width, height); } catch (Exception e) { fail("调整画面失败：" + e.getMessage()); }
        }
    };

    ScreenCapture(Context context, MediaProjection projection, UUID device, int longEdge,
                  int fps, int quality, Consumer<String> stopped) throws Exception {
        this.context = context;
        this.projection = projection;
        this.device = device;
        this.longEdge = longEdge;
        this.fps = fps;
        this.quality = quality;
        this.stopped = stopped;
        density = context.getResources().getDisplayMetrics().densityDpi;
        server = new ScreenServer();
        thread.start();
        worker = new Handler(thread.getLooper());
        projection.registerCallback(callback, worker);
        worker.post(() -> {
            try {
                Rect size = context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
                resize(size.width(), size.height());
                display = projection.createVirtualDisplay("Assist LAN screen", reader.getWidth(), reader.getHeight(),
                    density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, worker);
                worker.post(tick);
            } catch (Exception e) { fail("屏幕捕获启动失败：" + e.getMessage()); }
        });
    }

    JSONObject metadata() throws Exception {
        return new JSONObject().put("protocol", "ASV1").put("port", ScreenWire.PORT)
            .put("sessionId", session.toString()).put("width", sourceWidth).put("height", sourceHeight)
            .put("targetFps", fps).put("longEdge", longEdge);
    }

    String status() { return "画面：目标 " + fps + " fps · 已编码 " + sequence + " 帧 · 电脑连接 " + server.clients(); }

    private void resize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        sourceWidth = width; sourceHeight = height;
        float ratio = Math.min(1f, longEdge / (float) Math.max(width, height));
        int w = Math.max(2, Math.round(width * ratio)), h = Math.max(2, Math.round(height * ratio));
        ImageReader old = reader;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        if (display != null) { display.resize(w, h, density); display.setSurface(reader.getSurface()); }
        if (old != null) old.close();
        if (bitmap != null) bitmap.recycle();
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        pixels = new byte[w * h * 4];
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            long started = SystemClock.elapsedRealtime();
            try (Image image = reader.acquireLatestImage()) {
                if (image != null && context.getSystemService(PowerManager.class).isInteractive()
                    && !context.getSystemService(KeyguardManager.class).isKeyguardLocked()) {
                    Image.Plane plane = image.getPlanes()[0];
                    if (plane.getPixelStride() != 4) throw new IllegalStateException("不支持的屏幕像素格式");
                    ByteBuffer buffer = plane.getBuffer();
                    int rowBytes = image.getWidth() * 4;
                    for (int y = 0; y < image.getHeight(); y++) {
                        buffer.position(y * plane.getRowStride());
                        buffer.get(pixels, y * rowBytes, rowBytes);
                    }
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
                    jpeg.reset();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpeg);
                    server.publish(ScreenWire.encode(device, session, ++sequence, System.currentTimeMillis(),
                        sourceWidth, sourceHeight, image.getWidth(), image.getHeight(), jpeg.toByteArray()));
                }
            } catch (Exception e) { fail("屏幕捕获失败：" + e.getMessage()); return; }
            worker.postDelayed(this, Math.max(1, 1000 / fps - (SystemClock.elapsedRealtime() - started)));
        }
    };

    private void fail(String reason) { if (!closed) { close(); stopped.accept(reason); } }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        server.close();
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            if (display != null) display.release();
            if (reader != null) reader.close();
            if (bitmap != null) bitmap.recycle();
            projection.unregisterCallback(callback);
            projection.stop();
            thread.quitSafely();
        });
    }
}
