package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class JsonContracts {
    private JsonContracts() {}
    public static String canonical(JSONObject object) throws Exception {
        List<String> keys = new ArrayList<>(); object.keys().forEachRemaining(keys::add); Collections.sort(keys);
        StringBuilder out = new StringBuilder("{");
        for (String key : keys) {
            if (out.length() > 1) out.append(',');
            out.append(JSONObject.quote(key)).append(':').append(value(object.get(key)));
        }
        return out.append('}').toString();
    }
    private static String value(Object value) throws Exception {
        if (value instanceof JSONObject) return canonical((JSONObject) value);
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) { if (i > 0) out.append(','); out.append(value(array.get(i))); }
            return out.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }
}
