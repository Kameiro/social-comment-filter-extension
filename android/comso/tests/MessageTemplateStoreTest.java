package dev.local.commentcollector;

import org.json.JSONArray;
import org.json.JSONObject;

public final class MessageTemplateStoreTest {
    private static int assertions;
    private static void check(boolean value, String name) {
        assertions++;
        if (!value) throw new AssertionError(name);
    }

    public static void main(String[] args) throws Exception {
        check(MessageTemplateStore.items(new JSONObject()).length() == 0, "Missing items is an empty template list");
        check(MessageTemplateStore.items(new JSONObject().put("items", "wrong")).length() == 0,
            "Wrong items type is an empty template list");

        JSONObject first = new JSONObject().put("id", "m0").put("name", "欢迎").put("content", "你好");
        JSONArray saved = new JSONArray().put(first).put(JSONObject.NULL).put("ignored");
        JSONArray normalized = MessageTemplateStore.items(new JSONObject().put("items", saved));
        check(normalized.length() == 1, "Null and non-object entries are skipped");
        check("欢迎".equals(normalized.optJSONObject(0).optString("name")), "Existing template is retained");
        check("你好".equals(normalized.optJSONObject(0).optString("content")), "Existing template content is retained");
        System.out.println("MessageTemplateStoreTest ok: " + assertions + " assertions");
    }
}
