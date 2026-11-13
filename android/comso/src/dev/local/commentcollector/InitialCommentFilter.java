package dev.local.commentcollector;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

        // 1. Time is the first gate. Supports numeric timestamps and Chinese relative time strings.
        int recentDays = params.optInt("recentDays");
        if (recentDays > 0) {
            long created = comment.optLong("created", 0L);
            if (created <= 0L) {
                String timeStr = comment.optString("time", "");
                if (timeStr.isEmpty()) timeStr = comment.optString("createdText", "");
                created = parseRelativeTime(timeStr, now);
            }
            if (created <= 0L) return false;
            if (created < 100000000000L) created *= 1000L;
            if (created < now - recentDays * 86400000L) return false;
        }

        String text = comment.optString("text", "");
        String textLower = text.toLowerCase(Locale.ROOT);

        // 2. Exclusion remains an unconditional veto after the time gate.
        for (String term : terms(params.optString("excludeKeyword"))) {
            if (textLower.contains(term.toLowerCase(Locale.ROOT))) return false;
        }

        List<String> kwTerms = terms(params.optString("keyword"));
        boolean hasKeyword = !kwTerms.isEmpty();
        boolean keywordMatch = !hasKeyword || containsAny(textLower, kwTerms);

        String regionValue = params.optString("commentIpRegion").trim();
        if (regionValue.isEmpty() && "comment".equals(params.optString("ipSource"))) {
            regionValue = params.optString("ipRegion").trim();
        }
        List<String> regionTerms = terms(regionValue);
        boolean hasRegion = !regionTerms.isEmpty();
        String commentIp = comment.optString("ipRegion", "").toLowerCase(Locale.ROOT);
        boolean regionMatch = !hasRegion || containsAny(commentIp, regionTerms);

        // 3. The relation belongs only to keyword + comment-IP.
        if (hasKeyword && hasRegion && "or".equalsIgnoreCase(params.optString("relation"))) {
            return keywordMatch || regionMatch;
        }
        return keywordMatch && regionMatch;
    }

    private static boolean containsAny(String value, List<String> terms) {
        for (String term : terms) {
            if (value.contains(term.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static long parseRelativeTime(String time, long now) {
        if (time == null) return 0L;
        String s = time.trim();
        if (s.isEmpty()) return 0L;
        if (s.matches("\\d+")) {
            try {
                long val = Long.parseLong(s);
                if (val > 0) {
                    if (val < 100000000000L) val *= 1000L;
                    return val;
                }
            } catch (Throwable ignored) {}
        }
        if ("刚刚".equals(s)) return now;
        Matcher m = Pattern.compile("^(\\d+)\\s*秒前").matcher(s);
        if (m.find()) return now - Long.parseLong(m.group(1)) * 1000L;
        m = Pattern.compile("^(\\d+)\\s*分钟前").matcher(s);
        if (m.find()) return now - Long.parseLong(m.group(1)) * 60000L;
        m = Pattern.compile("^(\\d+)\\s*小时前").matcher(s);
        if (m.find()) return now - Long.parseLong(m.group(1)) * 3600000L;
        if (s.startsWith("昨天")) {
            return now - 86400000L;
        }
        if (s.startsWith("前天")) {
            return now - 2 * 86400000L;
        }
        m = Pattern.compile("^(\\d+)\\s*天前").matcher(s);
        if (m.find()) return now - Long.parseLong(m.group(1)) * 86400000L;
        m = Pattern.compile("^(\\d+)\\s*周前").matcher(s);
        if (m.find()) return now - Long.parseLong(m.group(1)) * 7 * 86400000L;
        m = Pattern.compile("^(\\d+)\\s*月前").matcher(s);
        if (m.find()) return now - Long.parseLong(m.group(1)) * 30 * 86400000L;

        try {
            Matcher dm = Pattern.compile("^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})").matcher(s);
            if (dm.find()) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(now);
                cal.set(Calendar.YEAR, Integer.parseInt(dm.group(1)));
                cal.set(Calendar.MONTH, Integer.parseInt(dm.group(2)) - 1);
                cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dm.group(3)));
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                return cal.getTimeInMillis();
            }
            dm = Pattern.compile("^(\\d{1,2})[-/.月](\\d{1,2})").matcher(s);
            if (dm.find()) {
                Calendar cal = Calendar.getInstance();
                cal.setTimeInMillis(now);
                int year = cal.get(Calendar.YEAR);
                cal.set(Calendar.MONTH, Integer.parseInt(dm.group(1)) - 1);
                cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(dm.group(2)));
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() > now + 86400000L) {
                    cal.set(Calendar.YEAR, year - 1);
                }
                return cal.getTimeInMillis();
            }
        } catch (Throwable ignored) {}
        return 0L;
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
