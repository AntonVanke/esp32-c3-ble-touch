package io.github.antonvanke.assistlab;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.widget.Toast;

public final class AssistService extends VoiceInteractionService {
    private static AssistService active;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable capture = () -> {
        try {
            showSession(new Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST);
        } catch (RuntimeException e) {
            Toast.makeText(this, "无法启动助理会话：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    };

    @Override public void onReady() {
        super.onReady();
        active = this;
    }

    static boolean schedule() {
        if (active == null) return false;
        active.handler.removeCallbacks(active.capture);
        active.handler.postDelayed(active.capture, 5000);
        return true;
    }

    @Override public void onShowSessionFailed(Bundle args) {
        Toast.makeText(this, "系统未能启动助理会话，请检查默认助理设置。", Toast.LENGTH_LONG).show();
    }

    @Override public void onShutdown() {
        handler.removeCallbacks(capture);
        if (active == this) active = null;
        super.onShutdown();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(capture);
        if (active == this) active = null;
        super.onDestroy();
    }
}
