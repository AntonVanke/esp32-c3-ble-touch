package io.github.antonvanke.assistlab;

import android.app.assist.AssistStructure;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.service.voice.VoiceInteractionSession.AssistState;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class StructureDump {
    private int nodes, texts, ids;
    private boolean truncated;

    JSONObject read(AssistState state) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("index", state.getIndex());
        result.put("focused", state.isFocused());
        try {
            AssistStructure structure = state.getAssistStructure();
            if (structure == null) {
                result.put("status", "null_structure");
                return result;
            }
            if (structure.getActivityComponent() != null) {
                result.put("activity", structure.getActivityComponent().flattenToShortString());
            }
            result.put("acquisitionStartUptimeMs", structure.getAcquisitionStartTime());
            result.put("acquisitionEndUptimeMs", structure.getAcquisitionEndTime());
            int count = structure.getWindowNodeCount();
            result.put("windowCount", count);
            JSONArray windows = new JSONArray();
            result.put("windows", windows);
            for (int i = 0; i < count; i++) {
                AssistStructure.WindowNode window = structure.getWindowNodeAt(i);
                JSONObject w = new JSONObject();
                w.put("title", value(window.getTitle()));
                w.put("displayId", window.getDisplayId());
                w.put("bounds", rect(window.getLeft(), window.getTop(), window.getWidth(), window.getHeight()));
                Matrix origin = new Matrix();
                origin.setTranslate(window.getLeft(), window.getTop());
                w.put("root", node(window.getRootViewNode(), origin, 0));
                windows.put(w);
            }
            result.put("status", count == 0 ? "empty_structure" : "structure_received");
        } catch (RuntimeException e) {
            result.put("status", "read_error");
            result.put("error", e.toString());
        }
        result.put("nodeCount", nodes);
        result.put("textNodeCount", texts);
        result.put("resourceIdNodeCount", ids);
        result.put("truncated", truncated);
        return result;
    }

    private JSONObject node(AssistStructure.ViewNode view, Matrix parent, int depth) throws JSONException {
        JSONObject out = new JSONObject();
        if (nodes >= 20000 || depth >= 128) {
            truncated = true;
            return out.put("truncated", true);
        }
        nodes++;
        out.put("className", value(view.getClassName()));
        out.put("text", value(view.getText()));
        out.put("contentDescription", value(view.getContentDescription()));
        out.put("hint", value(view.getHint()));
        out.put("id", view.getId());
        String entry = view.getIdEntry();
        out.put("resourceId", entry == null ? JSONObject.NULL
            : view.getIdPackage() + ":" + view.getIdType() + "/" + entry);
        if (entry != null) ids++;
        if (view.getText() != null && view.getText().length() > 0) texts++;
        out.put("boundsInParent", rect(view.getLeft(), view.getTop(), view.getWidth(), view.getHeight()));
        out.put("scrollX", view.getScrollX());
        out.put("scrollY", view.getScrollY());

        Matrix translation = new Matrix();
        translation.setTranslate(view.getLeft(), view.getTop());
        Matrix screen = new Matrix();
        screen.setConcat(parent, translation);
        Matrix transform = view.getTransformation();
        if (transform != null) {
            screen.preConcat(transform);
            float[] values = new float[9];
            transform.getValues(values);
            JSONArray matrix = new JSONArray();
            for (float v : values) matrix.put((double) v);
            out.put("transform", matrix);
        }
        RectF box = new RectF(0, 0, view.getWidth(), view.getHeight());
        screen.mapRect(box);
        out.put("boundsOnScreenEstimate", new JSONArray(new double[]{box.left, box.top, box.right, box.bottom}));
        out.put("clickable", view.isClickable());
        out.put("enabled", view.isEnabled());
        out.put("focusable", view.isFocusable());
        out.put("focused", view.isFocused());
        out.put("checkable", view.isCheckable());
        out.put("checked", view.isChecked());
        out.put("selected", view.isSelected());
        out.put("visibility", view.getVisibility());
        out.put("assistBlocked", view.isAssistBlocked());
        out.put("childCount", view.getChildCount());

        Matrix childOrigin = new Matrix(screen);
        childOrigin.preTranslate(-view.getScrollX(), -view.getScrollY());
        JSONArray children = new JSONArray();
        for (int i = 0; i < view.getChildCount(); i++) {
            if (nodes >= 20000) {
                truncated = true;
                out.put("childrenTruncated", true);
                break;
            }
            children.put(node(view.getChildAt(i), childOrigin, depth + 1));
        }
        out.put("children", children);
        return out;
    }

    private static JSONArray rect(int x, int y, int width, int height) throws JSONException {
        return new JSONArray(new int[]{x, y, x + width, y + height});
    }

    private static Object value(CharSequence text) {
        return text == null ? JSONObject.NULL : text.toString();
    }
}
