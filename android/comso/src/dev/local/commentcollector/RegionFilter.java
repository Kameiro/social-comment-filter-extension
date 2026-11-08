package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class RegionFilter {
    static List<String> values(JSONObject query, String singular, String plural) throws Exception {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Object input = query.has(plural) ? query.opt(plural) : query.opt(singular);
        JSONArray values = input instanceof JSONArray ? (JSONArray) input : new JSONArray().put(input == null || input == JSONObject.NULL ? "" : input);
        for (int i = 0; i < values.length(); i++) for (String term : values.getString(i).split("[,，、;；|\\s]+")) {
            if (term.isEmpty()) continue;
            if (term.length() > 32 || result.size() >= 20 && !result.contains(term)) throw new IllegalArgumentException("地区条件最多 20 项，每项最多 32 字");
            result.add(term);
        }
        return new ArrayList<>(result);
    }
    static void append(StringBuilder where, List<String> args, JSONObject query, String singular, String plural, String expression) throws Exception {
        List<String> regions = values(query, singular, plural); if (regions.isEmpty()) return;
        where.append(" AND (");
        for (int i = 0; i < regions.size(); i++) {
            if (i > 0) where.append(" OR ");
            where.append(expression).append(" LIKE ? ESCAPE '\\'");
            args.add("%" + regions.get(i).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        where.append(')');
    }
}
