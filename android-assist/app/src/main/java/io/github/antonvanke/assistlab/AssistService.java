package io.github.antonvanke.assistlab;

import android.app.KeyguardManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.widget.Toast;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class AssistService extends VoiceInteractionService {
    private static volatile AssistService active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UUID device, run;
    private volatile LanBroadcaster broadcaster;
    private volatile boolean running;
    private volatile String report = "连续采集未启动";
    private volatile long sent, inFlight;
    private long sequence;
    private int interval = 1000;
    private final Runnable next = this::requestCapture;
    private final Runnable watchdog = () -> stop("抓取超过 12 秒，已暂停；可重新开始");
    private final Runnable single = () -> {
        try { showSession(new Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST); }
        catch (RuntimeException e) { Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show(); }
    };

    @Override public void onReady() {
        super.onReady();
        SharedPreferences prefs = getSharedPreferences("lan", MODE_PRIVATE);
        String id = prefs.getString("deviceId", null);
        if (id == null) { id = UUID.randomUUID().toString(); prefs.edit().putString("deviceId", id).apply(); }
        device = UUID.fromString(id);
        active = this;
    }

    static boolean schedule() {
        AssistService service = active;
        if (service == null) return false;
        service.stop("连续采集已停止");
        service.handler.removeCallbacks(service.single);
        service.handler.postDelayed(service.single, 5000);
        return true;
    }

    static boolean startContinuous(int intervalMs) {
        AssistService service = active;
        if (service == null) return false;
        if (service.running) return true;
        service.handler.removeCallbacks(service.single);
        service.run = UUID.randomUUID();
        service.broadcaster = new LanBroadcaster(service.device, service.run);
        service.sequence = service.sent = 0;
        service.interval = Math.max(500, intervalMs);
        service.running = true;
        service.report = "已启动，3 秒后开始广播";
        service.handler.postDelayed(service.next, 3000);
        return true;
    }

    static void stopContinuous() { if (active != null) active.stop("已停止连续采集"); }
    static boolean isRunning() { return active != null && active.running; }
    static String streamStatus() {
        AssistService service = active;
        if (service == null) return "助理服务尚未就绪";
        LanBroadcaster sender = service.broadcaster;
        return service.report + "\n已发出 " + service.sent + " 帧（不代表电脑已接收）"
            + (sender == null ? "" : "\nUDP " + sender.destination);
    }

    private void requestCapture() {
        if (!running || inFlight != 0) return;
        long id = ++sequence;
        inFlight = id;
        handler.postDelayed(watchdog, 12000);
        if (!getSystemService(PowerManager.class).isInteractive()
            || getSystemService(KeyguardManager.class).isKeyguardLocked()) {
            publishStatus(id, "screen_locked", "屏幕关闭或锁定，等待解锁");
            return;
        }
        Bundle args = new Bundle();
        args.putBoolean("continuous", true);
        args.putLong("captureId", id);
        args.putString("runId", run.toString());
        try { showSession(args, VoiceInteractionSession.SHOW_WITH_ASSIST); }
        catch (RuntimeException e) { publishStatus(id, "session_error", e.toString()); }
    }

    static boolean accepts(String runId, long id) {
        AssistService service = active;
        return service != null && service.running && service.run.toString().equals(runId) && service.inFlight == id;
    }

    // Runs on the serialized capture worker, never the UI thread.
    static void publish(String runId, long id, JSONObject data) {
        AssistService service = active;
        if (service == null || !accepts(runId, id)) return;
        LanBroadcaster sender = service.broadcaster;
        String result;
        try {
            data.put("deviceId", service.device.toString()).put("runId", runId).put("sequence", id)
                .put("intervalMs", service.interval).put("continuous", true);
            CaptureStore.save(service, data);
            sender.send(id, data.toString().getBytes(StandardCharsets.UTF_8));
            service.sent++;
            result = "采集中：" + data.optString("status") + " · #" + id;
        } catch (Exception e) { result = "广播失败：" + e.getMessage(); }
        String message = result;
        service.handler.post(() -> {
            if (!accepts(runId, id)) return;
            service.handler.removeCallbacks(service.watchdog);
            service.inFlight = 0;
            service.report = message;
            service.handler.postDelayed(service.next, service.interval);
        });
    }

    private void publishStatus(long id, String status, String message) {
        String runId = run.toString();
        CaptureStore.IO.execute(() -> {
            try { publish(runId, id, baseStatus(status).put("message", message)); }
            catch (Exception e) { handler.post(() -> stop("状态生成失败：" + e.getMessage())); }
        });
    }

    private JSONObject baseStatus(String status) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("appVersion", "0.2.0")
            .put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER)
            .put("capturedAt", Instant.now().toString()).put("status", status).put("activities", new JSONArray());
    }

    private void stop(String reason) {
        handler.removeCallbacks(next);
        handler.removeCallbacks(watchdog);
        handler.removeCallbacks(single);
        boolean wasRunning = running;
        running = false;
        inFlight = 0;
        report = reason;
        CaptureSession.cancelContinuous();
        LanBroadcaster sender = broadcaster;
        broadcaster = null;
        if (sender != null) sender.close();
        if (wasRunning && sender != null) {
            long id = ++sequence;
            String stoppedRun = run.toString();
            CaptureStore.IO.execute(() -> {
                try (LanBroadcaster stoppedSender = new LanBroadcaster(device, UUID.fromString(stoppedRun))) {
                    JSONObject data = baseStatus("stream_stopped").put("message", reason)
                        .put("deviceId", device.toString()).put("runId", stoppedRun).put("sequence", id);
                    stoppedSender.send(id, data.toString().getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
            });
        }
    }

    @Override public void onShowSessionFailed(Bundle args) {
        if (running && inFlight != 0) publishStatus(inFlight, "session_error", "系统未能启动助理会话");
        else Toast.makeText(this, "系统未能启动助理会话，请检查默认助理设置。", Toast.LENGTH_LONG).show();
    }
    @Override public void onShutdown() { stop("默认助理已切换，采集停止"); if (active == this) active = null; super.onShutdown(); }
    @Override public void onDestroy() { stop("服务已停止"); if (active == this) active = null; super.onDestroy(); }
}
