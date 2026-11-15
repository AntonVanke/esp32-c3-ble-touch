package io.github.antonvanke.assistlab;

import android.app.Activity;
import android.Manifest;
import android.app.NotificationManager;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import java.io.OutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int SAVE_JSON = 10, CAPTURE_SCREEN = 20, NOTIFICATIONS = 21;
    private TextView serviceStatus, summary, preview;
    private String latest = "";
    private Button capture;
    private Button startStream, stopStream;
    private TextView streamStatus, permissions;
    private CheckBox shareScreen, floating;
    private Intent pendingStart;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable updateStream = new Runnable() {
        @Override public void run() {
            streamStatus.setText(MonitorService.status() + "\n" + AssistService.streamStatus());
            startStream.setEnabled(!MonitorService.isRunning() && pendingStart == null);
            stopStream.setEnabled(MonitorService.isRunning());
            capture.setEnabled(!MonitorService.isRunning() && pendingStart == null
                && VoiceInteractionService.isActiveService(MainActivity.this, new ComponentName(MainActivity.this, AssistService.class)));
            permissions.setText("通知：" + (getSystemService(NotificationManager.class).areNotificationsEnabled() ? "已允许" : "未允许，通知栏可能不显示")
                + " · 悬浮窗：" + (Settings.canDrawOverlays(MainActivity.this) ? "已允许" : "未允许"));
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) pendingStart = state.getParcelable("pendingStart", Intent.class);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        body.setPadding(padding, padding, padding, padding);
        body.setBackgroundColor(Color.rgb(245, 247, 250));
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(scroll);
        text(body, "Assist 结构测试", 26);
        text(body, "v0.3.0 · Android 14+\n默认数字助理读取结构，系统屏幕共享采集画面；不使用无障碍、USB 调试或 Root。", 15);
        serviceStatus = text(body, "", 15);
        button(body, "1. 设为默认数字助理", this::requestAssistant);
        button(body, "打开系统助理设置", () -> {
            try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
            catch (ActivityNotFoundException e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
                catch (ActivityNotFoundException missing) { toast("请在系统设置搜索“默认助理”或“辅助应用”。"); }
            }
        });
        text(body, "后台连续采集 · 局域网广播", 21);
        text(body, "手机和电脑连接同一局域网，电脑打开 Assist LAN Monitor。开始后 App 退到后台，3 秒后自动采集；不显示本 App 的助理面板，系统仍可能显示识屏提示。", 15);
        text(body, "结构目标间隔（包含采集耗时，不叠加等待）", 14);
        Spinner interval = new Spinner(this);
        interval.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"100 毫秒 · 快速", "200 毫秒 · 默认", "500 毫秒", "1 秒"}));
        interval.setSelection(1);
        body.addView(interval);
        shareScreen = new CheckBox(this);
        shareScreen.setText("同时共享实时手机画面（每次开始需系统授权）");
        shareScreen.setChecked(true); body.addView(shareScreen);
        Spinner video = new Spinner(this);
        video.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"流畅 · 长边 720 / 目标 15 fps", "均衡 · 长边 960 / 目标 10 fps", "清晰 · 长边 1280 / 目标 10 fps"}));
        video.setSelection(1); body.addView(video);
        text(body, "结构和画面独立采集，实际速度取决于系统回调、页面和 Wi-Fi；受保护页面可能黑屏。", 14);
        text(body, "后台运行与保活", 21);
        floating = new CheckBox(this);
        floating.setText("采集时显示悬浮控制窗"); floating.setChecked(true); body.addView(floating);
        permissions = text(body, "", 14);
        button(body, "允许通知 / 打开通知设置", () -> {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 22);
            else openSettings(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
        button(body, "允许悬浮窗", () -> openSettings(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        button(body, "打开电池优化设置", () -> openSettings(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        button(body, "打开应用信息（后台 / 自启动设置）", () -> openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        text(body, "采集期间运行前台服务并显示持续通知。小米可在系统设置允许后台运行 / 自启动，并在最近任务中锁定本 App；这些措施不保证系统永不结束进程。", 14);
        text(body, "开始后向局域网共享页面文字和画面：UDP 38583 / TCP 38584，未加密；同网段设备可接收。", 14);
        startStream = button(body, "开始实时画面与结构共享", () -> {
            if (!VoiceInteractionService.isActiveService(this, new ComponentName(this, AssistService.class))) {
                toast("请先启用本 App 的默认助理服务。"); return;
            }
            if (pendingStart != null || MonitorService.isRunning()) return;
            int preset = video.getSelectedItemPosition();
            pendingStart = new Intent(this, MonitorService.class)
                .putExtra("interval", new int[]{100, 200, 500, 1000}[interval.getSelectedItemPosition()])
                .putExtra("edge", new int[]{720, 960, 1280}[preset])
                .putExtra("fps", new int[]{15, 10, 10}[preset])
                .putExtra("quality", new int[]{55, 65, 75}[preset])
                .putExtra("overlay", floating.isChecked()).putExtra("screen", shareScreen.isChecked());
            startStream.setEnabled(false);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
            else requestScreen();
        });
        stopStream = button(body, "停止连续采集与广播", () -> {
            stopService(new Intent(this, MonitorService.class));
        });
        streamStatus = text(body, "", 15);
        text(body, "单次抓取与导出", 21);
        text(body, "在系统助理设置中允许“使用屏幕上的文字／屏幕内容”（如有此项）。然后点击下面按钮，在 5 秒内切换到目标 App 并停留在要测试的页面。", 15);
        capture = button(body, "2. 5 秒后获取页面结构", () -> {
            if (!VoiceInteractionService.isActiveService(this, new ComponentName(this, AssistService.class))) {
                toast("请先设为默认数字助理，确认服务状态为“已启用”。");
                return;
            }
            if (AssistService.schedule()) {
                toast("已预约：5 秒后抓取，请立即切换到目标页面。");
                moveTaskToBack(true);
            } else {
                toast("助理服务尚未就绪，请从系统设置重新选择本 App，稍后重试。");
            }
        });
        text(body, "也可在目标页面使用系统的助理手势／快捷键唤起本 App。抓取完成后关闭助理面板，再回这里查看。", 15);
        summary = text(body, "尚无抓取结果", 16);
        button(body, "刷新结果", this::reload);
        button(body, "3. 保存完整 JSON…", () -> {
            if (latest.isEmpty()) { toast("请先抓取一次。"); return; }
            String snapshot = latest;
            String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "assist-" + time + ".json");
            CaptureStore.IO.execute(() -> {
                try {
                    Files.write(new File(getFilesDir(), "export-assist.json").toPath(), snapshot.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        try { startActivityForResult(intent, SAVE_JSON); }
                        catch (ActivityNotFoundException e) { toast("系统未提供文件保存界面。"); }
                    });
                } catch (Exception e) { runOnUiThread(() -> toast("准备导出失败：" + e.getMessage())); }
            });
        });
        button(body, "复制结果摘要", () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("Assist 结果", serviceStatus.getText() + "\n" + summary.getText()));
            toast("已复制摘要。");
        });
        text(body, "JSON 预览（最多显示 50,000 字符，导出文件保留完整结果）", 15);
        preview = text(body, "", 12);
        preview.setTypeface(Typeface.MONOSPACE);
        preview.setTextIsSelectable(true);
        text(body, "连续结构和画面只保留最新帧，不保存录像。锁屏暂停读取；若系统撤销屏幕共享，需要回 App 重新授权。通知或悬浮窗均可停止；服务结束后不会自动重启。", 14);
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
        ui.removeCallbacks(updateStream);
        ui.post(updateStream);
    }

    @Override protected void onPause() { ui.removeCallbacks(updateStream); super.onPause(); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putParcelable("pendingStart", pendingStart);
    }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException e) { toast("此系统未提供该入口，请在系统设置中手动查找。"); }
    }

    private void requestScreen() {
        if (pendingStart == null) return;
        if (!pendingStart.getBooleanExtra("screen", true)) { startMonitor(null); return; }
        try {
            Intent request = getSystemService(MediaProjectionManager.class)
                .createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
            startActivityForResult(request, CAPTURE_SCREEN);
        } catch (RuntimeException e) { pendingStart = null; toast("无法请求屏幕共享：" + e.getMessage()); }
    }

    private void startMonitor(Intent grant) {
        Intent start = pendingStart;
        pendingStart = null;
        if (start == null) { toast("启动配置已失效，请重新点击开始。"); return; }
        if (grant != null) start.putExtra("grant", grant);
        try { startForegroundService(start); moveTaskToBack(true); }
        catch (RuntimeException e) { toast("无法启动采集：" + e.getMessage()); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] names, int[] results) {
        super.onRequestPermissionsResult(requestCode, names, results);
        if (requestCode == NOTIFICATIONS) requestScreen();
    }

    private void requestAssistant() {
        RoleManager roles = getSystemService(RoleManager.class);
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            try { startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 1); }
            catch (ActivityNotFoundException e) { toast("请使用“打开系统助理设置”按钮手动选择。"); }
        } else { toast("系统未开放助理角色申请，请使用系统助理设置。"); }
    }

    private void reload() {
        boolean active = VoiceInteractionService.isActiveService(this, new ComponentName(this, AssistService.class));
        RoleManager roles = getSystemService(RoleManager.class);
        boolean held = roles != null && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT);
        serviceStatus.setText("默认助理角色：" + (held ? "已选择" : "未选择")
            + "\n结构读取服务：" + (active ? "已启用" : "未启用"));
        capture.setEnabled(active);
        CaptureStore.IO.execute(() -> {
            try {
                String data = CaptureStore.read(this);
                String result = data.isEmpty() ? "尚无抓取结果" : CaptureStore.summary(new JSONObject(data));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    latest = data;
                    summary.setText(result);
                    preview.setText(data.substring(0, Math.min(data.length(), 50000)));
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (!isDestroyed()) summary.setText("读取失败：" + e); });
            }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CAPTURE_SCREEN) {
            if (resultCode == RESULT_OK && data != null) startMonitor(data);
            else { pendingStart = null; toast("未授权屏幕共享，未开始采集。"); }
            return;
        }
        if (requestCode != SAVE_JSON || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        CaptureStore.IO.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (out == null) throw new java.io.IOException("无法打开文件");
                Files.copy(new File(getFilesDir(), "export-assist.json").toPath(), out);
                runOnUiThread(() -> toast("JSON 已保存。"));
            } catch (Exception e) { runOnUiThread(() -> toast("保存失败：" + e.getMessage())); }
        });
    }

    private TextView text(LinearLayout body, String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(Color.rgb(30, 41, 59));
        view.setTextSize(size);
        view.setPadding(0, 10, 0, 10);
        body.addView(view);
        return view;
    }

    private Button button(LinearLayout body, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        body.addView(button);
        return button;
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
