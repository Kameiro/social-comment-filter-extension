package dev.local.commentcollector;

import java.lang.reflect.Method;
import java.util.Calendar;
import org.json.JSONObject;

final class XhsProfileMapper {
    private XhsProfileMapper() {}

    static String parseGender(Object gObj) {
        if (gObj instanceof Number) {
            int g = ((Number) gObj).intValue();
            if (g == 0) return "male";
            if (g == 1) return "female";
            return "unknown";
        }
        if (gObj != null) {
            String gs = String.valueOf(gObj).trim();
            if ("0".equals(gs) || "male".equalsIgnoreCase(gs) || "男".equals(gs)) return "male";
            if ("1".equals(gs) || "female".equalsIgnoreCase(gs) || "女".equals(gs)) return "female";
        }
        return "unknown";
    }

    static String cleanBio(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return "";
        if (s.contains("UserDescInfo(") || s.contains("UserDescInfo")) {
            int idx = s.indexOf("desc=");
            if (idx >= 0) {
                int start = idx + 5;
                int end = s.indexOf(", descHidden=", start);
                if (end >= 0) {
                    s = s.substring(start, end);
                } else {
                    int end2 = s.indexOf(", descWithPlaceholder=", start);
                    if (end2 >= 0) {
                        s = s.substring(start, end2);
                    } else {
                        int end3 = s.indexOf(", desc", start);
                        if (end3 >= 0) s = s.substring(start, end3);
                        else if (s.endsWith(")")) s = s.substring(start, s.length() - 1);
                    }
                }
            }
        }
        s = s.trim();
        if (s.startsWith("UserDescInfo(") || s.startsWith("UserDescInfo")) return "";
        return s;
    }

    static String extractBio(Object userInfo) {
        if (userInfo == null) return "";
        try {
            Object descInfo = ModelReader.get(userInfo, "userDescInfo", "user_desc_info");
            if (descInfo == null) {
                try {
                    Method m = userInfo.getClass().getMethod("getUserDescInfo");
                    descInfo = m.invoke(userInfo);
                } catch (Throwable ignored) {}
            }
            if (descInfo != null) {
                Object d = ModelReader.get(descInfo, "desc");
                if (d == null) {
                    try {
                        Method dm = descInfo.getClass().getMethod("getDesc");
                        d = dm.invoke(descInfo);
                    } catch (Throwable ignored) {}
                }
                if (d == null) {
                    try {
                        Method dm = descInfo.getClass().getMethod("getDescWithPlaceholder");
                        d = dm.invoke(descInfo);
                    } catch (Throwable ignored) {}
                }
                if (d != null) {
                    String clean = cleanBio(String.valueOf(d));
                    if (!clean.isEmpty()) return clean;
                }
                String fromStr = cleanBio(String.valueOf(descInfo));
                if (!fromStr.isEmpty()) return fromStr;
            }
        } catch (Throwable ignored) {}

        Object d = ModelReader.get(userInfo, "desc");
        if (d != null) {
            String clean = cleanBio(String.valueOf(d));
            if (!clean.isEmpty()) return clean;
        }

        return cleanBio(text(userInfo, "desc", "user_desc"));
    }

    static JSONObject buildProfile(Object userInfo, String uid) throws Exception {
        if (userInfo == null) return null;
        Object gObj = ModelReader.get(userInfo, "gender");
        if (gObj == null) {
            try {
                Method gm = userInfo.getClass().getMethod("getGender");
                gObj = gm.invoke(userInfo);
            } catch (Throwable ignored) {}
        }
        String gender = parseGender(gObj);

        String rawIp = text(userInfo, "ipLocation", "ip_location");
        if (rawIp.isEmpty()) {
            try {
                Method ipm = userInfo.getClass().getMethod("getIpLocation");
                Object ipo = ipm.invoke(userInfo);
                if (ipo != null) rawIp = String.valueOf(ipo).trim();
            } catch (Throwable ignored) {}
        }
        String ip = rawIp.replaceFirst("^\\s*IP\\s*属地\\s*[:：]?\\s*", "").trim();

        JSONObject out = new JSONObject().put("platform", "xhs").put("userId", uid).put("gender", gender)
            .put("profileCapturedAt", System.currentTimeMillis()).put("profileSource", "xhs_android.UserInfo").put("profileIpRegion", ip);

        put(out, "nickname", text(userInfo, "nickname", "name"));
        String avatar = firstNonEmpty(text(userInfo, "avatar", "imageb", "images"), ModelReader.url(ModelReader.get(userInfo, "avatar", "imageb", "images")));
        put(out, "avatarUrl", avatar);
        put(out, "bio", extractBio(userInfo));
        put(out, "redId", text(userInfo, "redId", "red_id"));

        String city = text(userInfo, "location", "city");
        put(out, "location", city);
        if (!city.isEmpty()) {
            if (!ip.isEmpty() && !city.contains(ip)) put(out, "latestPostLocation", ip + " " + city);
            else put(out, "latestPostLocation", city);
        } else if (!ip.isEmpty()) {
            put(out, "latestPostLocation", ip);
        }

        Object age = JSONObject.NULL;
        Object ageObj = ModelReader.get(userInfo, "age");
        if (ageObj instanceof Number) {
            int a = ((Number) ageObj).intValue();
            if (a > 0 && a <= 120) age = a;
        }
        if (age == JSONObject.NULL) {
            String birthday = text(userInfo, "birthday");
            if (birthday.isEmpty()) {
                try {
                    Method bm = userInfo.getClass().getMethod("getBirthday");
                    Object bo = bm.invoke(userInfo);
                    if (bo != null) birthday = String.valueOf(bo).trim();
                } catch (Throwable ignored) {}
            }
            if (birthday.matches("(19|20)\\d\\d-\\d\\d-\\d\\d")) {
                int years = Calendar.getInstance().get(Calendar.YEAR) - Integer.parseInt(birthday.substring(0, 4));
                if (years > 0 && years <= 120) age = years;
            }
        }
        out.put("age", age).put("ageVisibility", age == JSONObject.NULL ? "privacy_unspecified" : "public_or_unset");
        out.put("fieldSources", new JSONObject().put("gender", "user.gender").put("age", ageObj != null ? "user.age" : "user.birthday").put("location", "user.location")
            .put("profileIpRegion", "user.ip_location"));
        return out;
    }

    private static String text(Object object, String... keys) {
        String t = ModelReader.text(object, keys);
        if (!t.isEmpty()) return t;
        if (object == null) return "";
        for (String key : keys) {
            String getter = "get" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            try {
                Method m = object.getClass().getMethod(getter);
                Object v = m.invoke(object);
                if (v != null) {
                    String s = String.valueOf(v).trim();
                    if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
                }
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static void put(JSONObject out, String key, String value) throws Exception {
        if (value != null && !value.isEmpty()) out.put(key, value);
    }
}
