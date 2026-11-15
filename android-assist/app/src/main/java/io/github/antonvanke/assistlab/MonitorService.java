package io.github.antonvanke.assistlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

public final class MonitorService extends Service {
    private static volatile MonitorService active;
    private static volatile String lastStatus = "采集未启动";
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile ScreenCapture screen;
    private LinearLayout overlay;
    private TextView floatingStatus;
    private MediaProjection projection;

    static boolean isRunning() { return active != null; }
    static String status() {
        MonitorService service = active;
        ScreenCapture capture = service == null ? null : service.screen;
        return lastStatus + (capture == null ? "" : "\n" + capture.status());
    }
    static void metadata(JSONObject data) throws Exception {
        MonitorService service = active;
        ScreenCapture capture = service == null ? null : service.screen;
        if (capture != null) data.put("screen", capture.metadata());
    }
    static void finish(String reason) {
        MonitorService service = active;
        if (service != null) service.main.post(() -> {
            if (active != service) return;
            lastStatus = reason;
            Toast.makeText(service, reason, Toast.LENGTH_LONG).show();
            service.stopSelf();
        });
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "stop".equals(intent.getAction())) {
            lastStatus = "已停止画面、结构与局域网传输";
            stopSelf(); return START_NOT_STICKY;
        }
        if (active == this) return START_NOT_STICKY;
        try {
            Intent grant = intent.getParcelableExtra("grant", Intent.class);
            NotificationManager notifications = getSystemService(NotificationManager.class);
            notifications.createNotificationChannel(new NotificationChannel("capture", "局域网实时监看", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(this, "capture")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Assist · 正在局域网共享")
                .setContentText(grant == null ? "正在共享页面结构 · 点击打开控制" : "正在共享手机画面和页面结构 · 点击打开控制")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(new Notification.Action.Builder(null, "停止采集", stop).build()).build();
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (grant != null) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            startForeground(1, notification, types);
            active = this;
            if (grant != null) {
                projection = getSystemService(MediaProjectionManager.class).getMediaProjection(android.app.Activity.RESULT_OK, grant);
                screen = new ScreenCapture(this, projection, AssistService.deviceId(this),
                    intent.getIntExtra("edge", 960), intent.getIntExtra("fps", 10), intent.getIntExtra("quality", 65), MonitorService::finish);
            }
            if (!AssistService.startContinuous(intent.getIntExtra("interval", 200)))
                throw new IllegalStateException("默认助理服务尚未就绪，请重新选择默认助理");
            lastStatus = "前台服务运行中 · 通知可停止采集";
            if (intent.getBooleanExtra("overlay", true) && Settings.canDrawOverlays(this)) showOverlay();
        } catch (Exception e) {
            lastStatus = "启动失败：" + e.getMessage();
            Toast.makeText(this, lastStatus, Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void showOverlay() {
        WindowManager windows = getSystemService(WindowManager.class);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(pad, pad, pad, pad);
        overlay.setBackgroundColor(Color.argb(235, 30, 41, 59));
        floatingStatus = new TextView(this);
        floatingStatus.setTextColor(Color.WHITE);
        floatingStatus.setTextSize(12);
        floatingStatus.setText("Assist · 采集中\n拖动移动 / 点击打开");
        floatingStatus.setPadding(pad, pad, pad, pad);
        overlay.addView(floatingStatus);
        Button stop = new Button(this);
        stop.setText("停止采集");
        stop.setTextSize(12);
        stop.setOnClickListener(v -> { lastStatus = "已通过悬浮窗停止采集"; stopSelf(); });
        overlay.addView(stop);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 12; params.y = 180;
        floatingStatus.setOnTouchListener(new android.view.View.OnTouchListener() {
            float x, y; int left, top; boolean dragged;
            @Override public boolean onTouch(android.view.View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    x = event.getRawX(); y = event.getRawY(); left = params.x; top = params.y; dragged = false;
                } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - x, dy = event.getRawY() - y;
                    dragged |= Math.abs(dx) + Math.abs(dy) > 12;
                    params.x = Math.max(0, left + (int) dx); params.y = Math.max(0, top + (int) dy);
                    windows.updateViewLayout(overlay, params);
                } else if (event.getAction() == MotionEvent.ACTION_UP && !dragged) {
                    startActivity(new Intent(MonitorService.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
                return true;
            }
        });
        windows.addView(overlay, params);
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        main.removeCallbacksAndMessages(null);
        AssistService.stopContinuous();
        if (screen != null) { screen.close(); screen = null; }
        else if (projection != null) projection.stop();
        if (overlay != null) {
            try { getSystemService(WindowManager.class).removeView(overlay); } catch (IllegalArgumentException ignored) { }
            overlay = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
