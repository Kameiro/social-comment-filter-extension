package dev.local.commentcollector;

import org.json.JSONArray;
import org.json.JSONObject;

/** Normalizes persisted message templates before any UI code iterates them. */
final class MessageTemplateStore {
    private MessageTemplateStore() {}

    static JSONArray items(JSONObject response) {
        JSONArray raw = response == null ? null : response.optJSONArray("items");
        JSONArray normalized = new JSONArray();
        if (raw == null) return normalized;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (item != null) normalized.put(item);
        }
        return normalized;
    }
}
