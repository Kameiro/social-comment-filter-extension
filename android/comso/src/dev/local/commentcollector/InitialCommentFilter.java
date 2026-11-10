package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Deterministic first-stage comment filter. Time is evaluated before any text
 * or region condition; keyword and comment-IP are one independent group whose
 * relation is applied only when both conditions are present.
 */
final class InitialCommentFilter {
    private InitialCommentFilter() {}

    static boolean matches(JSONObject params, JSONObject comment) {
        return matches(params, comment, System.currentTimeMillis());
    }

    static boolean matches(JSONObject params, JSONObject comment, long now) {
        if (params == null || comment == null) return true;

        // 1. Time is the first gate. A missing timestamp is unknown and cannot
        // satisfy a bounded time filter.
        int recentDays = params.optInt("recentDays");
        if (recentDays > 0) {
            long created = comment.optLong("created", 0L);
            if (created <= 0L) return false;
            if (created < 100000000000L) created *= 1000L;
            if (created < now - recentDays * 86400000L) return false;
        }

        String text = comment.optString("text", "");

        // 2. Exclusion remains an unconditional veto after the time gate.
        for (String term : terms(params.optString("excludeKeyword"))) {
            if (text.contains(term)) return false;
        }

        List<String> kwTerms = terms(params.optString("keyword"));
        boolean hasKeyword = !kwTerms.isEmpty();
        boolean keywordMatch = !hasKeyword || containsAny(text, kwTerms);

        String regionValue = params.optString("commentIpRegion").trim();
        if (regionValue.isEmpty() && "comment".equals(params.optString("ipSource"))) {
            regionValue = params.optString("ipRegion").trim();
        }
        List<String> regionTerms = terms(regionValue);
        boolean hasRegion = !regionTerms.isEmpty();
        boolean regionMatch = !hasRegion || containsAny(comment.optString("ipRegion", ""), regionTerms);

        // 3. The relation belongs only to keyword + comment-IP. A stale OR
        // value must not make a single enabled condition pass unexpectedly.
        if (hasKeyword && hasRegion && "or".equalsIgnoreCase(params.optString("relation"))) {
            return keywordMatch || regionMatch;
        }
        return keywordMatch && regionMatch;
    }

    private static boolean containsAny(String value, List<String> terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    static List<String> terms(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String term : value.split("[,，、;；|]+")) {
            term = term.trim();
            if (!term.isEmpty() && out.size() < 20) out.add(term.length() > 50 ? term.substring(0, 50) : term);
        }
        return out;
    }
}
