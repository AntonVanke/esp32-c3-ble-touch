package io.github.antonvanke.assistlab;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.voice.VoiceInteractionSession;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.Instant;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CaptureSession extends VoiceInteractionSession {
    private static CaptureSession continuousSession;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JSONObject document;
    private TextView status;
    private int callbacks, expected;
    private boolean background, completed;
    private long captureId, started;
    private String runId;
    private String message = "等待系统返回当前页面结构…";
    private final Runnable timeout = () -> complete(callbacks == 0 ? "no_callback_after_8_seconds" : "partial_callbacks");

    CaptureSession(Context context) { super(context); }

    static void cancelContinuous() {
        CaptureSession session = continuousSession;
        if (session == null) return;
        session.completed = true;
        session.main.removeCallbacks(session.timeout);
        continuousSession = null;
        session.finish();
    }

    @Override public void onPrepareShow(Bundle args, int flags) {
        super.onPrepareShow(args, flags);
        main.removeCallbacks(timeout);
        background = args != null && args.getBoolean("continuous");
        captureId = args == null ? 0 : args.getLong("captureId");
        runId = args == null ? "" : args.getString("runId", "");
        setUiEnabled(!background);
        if (background) continuousSession = this;
        callbacks = expected = 0;
        completed = false;
        started = SystemClock.elapsedRealtime();
        message = "等待系统返回当前页面结构…";
        try {
            document = new JSONObject()
                .put("schemaVersion", 1).put("appVersion", "0.2.0")
                .put("capturedAt", Instant.now().toString())
                .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT)
                .put("buildDisplay", Build.DISPLAY).put("showFlags", flags)
                .put("requestedAssist", (flags & SHOW_WITH_ASSIST) != 0)
                .put("userDisabledContextFlags", getUserDisabledShowContext())
                .put("serviceDisabledContextFlags", getDisabledShowContext())
                .put("continuous", background).put("status", "waiting_for_callback")
                .put("activities", new JSONArray()).put("errors", new JSONArray());
        } catch (Exception e) {
            document = null;
            message = "初始化失败：" + e;
        }
    }

    @Override public void onShow(Bundle args, int flags) {
        super.onShow(args, flags);
        if (status != null) status.setText(message);
        if (!completed) main.postDelayed(timeout, 8000);
    }

    @Override public View onCreateContentView() {
        Context context = new ContextThemeWrapper(getContext(), android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(28, 24, 28, 32);
        panel.setBackgroundColor(Color.WHITE);
        TextView title = new TextView(context);
        title.setText("Assist 结构测试");
        title.setTextSize(22);
        panel.addView(title);
        status = new TextView(context);
        status.setTextSize(15);
        status.setText(message);
        panel.addView(status);
        Button close = new Button(context);
        close.setText("关闭，返回目标页面");
        close.setOnClickListener(v -> hide());
        panel.addView(close);
        TextView hint = new TextView(context);
        hint.setText("结果自动保存在本机。关闭后打开测试 App，可查看和导出 JSON。");
        panel.addView(hint);
        getWindow().getWindow().setGravity(Gravity.BOTTOM);
        return panel;
    }

    @Override public void onHandleAssist(AssistState state) {
        super.onHandleAssist(state);
        if (completed || document == null) return;
        callbacks++;
        expected = Math.max(expected, Math.max(1, state.getCount()));
        JSONObject current = document;
        CaptureStore.IO.execute(() -> {
            try { current.getJSONArray("activities").put(new StructureDump().read(state)); }
            catch (Exception e) {
                try { current.getJSONArray("errors").put(e.toString()); } catch (Exception ignored) { }
            }
        });
        if (callbacks >= expected) complete("received_callbacks");
    }

    @Override public void onAssistStructureFailure(Throwable failure) {
        if (completed || document == null) return;
        JSONObject current = document;
        CaptureStore.IO.execute(() -> {
            try { current.getJSONArray("errors").put(failure.toString()); }
            catch (Exception ignored) { }
        });
    }

    private void complete(String result) {
        if (completed || document == null) return;
        completed = true;
        main.removeCallbacks(timeout);
        JSONObject current = document;
        boolean continuous = background;
        long id = captureId;
        String run = runId;
        int count = expected;
        long begin = started;
        CaptureStore.IO.execute(() -> {
            try {
                current.put("status", result).put("expectedActivities", count)
                    .put("captureDurationMs", SystemClock.elapsedRealtime() - begin);
                if (continuous) {
                    if (!AssistService.accepts(run, id)) return;
                    main.post(() -> { if (continuousSession == this) { continuousSession = null; finish(); } });
                    AssistService.publish(run, id, current);
                } else {
                    CaptureStore.save(getContext(), current);
                    showMessage(current, CaptureStore.summary(current));
                }
            } catch (Exception e) { showMessage(current, "处理结构失败：" + e); }
        });
    }

    @Override public void onDestroy() {
        main.removeCallbacks(timeout);
        if (continuousSession == this) continuousSession = null;
        super.onDestroy();
    }

    private void showMessage(JSONObject current, String text) {
        main.post(() -> {
            if (document != current) return;
            message = text;
            if (status != null) status.setText(text);
        });
    }
}
