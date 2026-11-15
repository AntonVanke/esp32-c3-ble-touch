package io.github.antonvanke.assistlab;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int SAVE_JSON = 10;
    private TextView serviceStatus, summary, preview;
    private String latest = "";
    private Button capture;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
        text(body, "v0.1.0 · Android 14+\n只读取系统助理提供的结构；不使用无障碍、ADB、Root、录屏或麦克风。", 15);
        serviceStatus = text(body, "", 15);
        button(body, "1. 设为默认数字助理", this::requestAssistant);
        button(body, "打开系统助理设置", () -> {
            try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
            catch (ActivityNotFoundException e) {
                try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
                catch (ActivityNotFoundException missing) { toast("请在系统设置搜索“默认助理”或“辅助应用”。"); }
            }
        });
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
        text(body, "仅保留最近一次会话，下一次抓取会覆盖。数据保存在本机，不联网；导出的文件可能含页面文字。测试后可在系统设置中恢复原默认助理。", 14);
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
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
