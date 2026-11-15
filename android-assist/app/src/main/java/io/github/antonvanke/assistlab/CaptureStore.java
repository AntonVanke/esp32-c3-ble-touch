package io.github.antonvanke.assistlab;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

final class CaptureStore {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile String latest;

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "latest-assist.json"));
    }

    static void save(Context context, JSONObject data) throws Exception {
        latest = data.toString(2);
        if (data.optBoolean("continuous")) return;
        AtomicFile file = file(context);
        FileOutputStream out = null;
        try {
            byte[] bytes = latest.getBytes(StandardCharsets.UTF_8);
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
    }

    static String read(Context context) throws IOException {
        if (latest != null) return latest;
        if (!file(context).getBaseFile().exists()) return "";
        return new String(file(context).readFully(), StandardCharsets.UTF_8);
    }

    static String summary(JSONObject data) {
        JSONArray activities = data.optJSONArray("activities");
        int nodes = 0, texts = 0, ids = 0, windows = 0;
        StringBuilder source = new StringBuilder();
        for (int i = 0; activities != null && i < activities.length(); i++) {
            JSONObject a = activities.optJSONObject(i);
            nodes += a.optInt("nodeCount");
            texts += a.optInt("textNodeCount");
            ids += a.optInt("resourceIdNodeCount");
            windows += a.optInt("windowCount");
            source.append('\n').append(a.optString("activity", "未知页面"))
                .append(" — ").append(a.optString("status"));
        }
        return "状态：" + data.optString("status")
            + "\n时间：" + data.optString("capturedAt")
            + "\n回调：" + (activities == null ? 0 : activities.length())
            + " / " + data.optInt("expectedActivities")
            + "　窗口：" + windows + "\n节点：" + nodes + "　有文本：" + texts + "　有资源 ID：" + ids
            + "\n系统禁止屏幕内容标志：" + data.optInt("userDisabledContextFlags")
            + source;
    }
}
