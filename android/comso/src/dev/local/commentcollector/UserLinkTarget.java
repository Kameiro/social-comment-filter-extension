package dev.local.commentcollector;

import org.json.JSONObject;

final class UserLinkTarget {
    private UserLinkTarget() {}

    static JSONObject fromUser(String id, String name, JSONObject record, String fallbackPlatform) throws org.json.JSONException {
        JSONObject user = record.optJSONObject("user");
        String platform = Platforms.normalize(record.optString("platform"));
        if (platform.isEmpty() && user != null) platform = Platforms.normalize(user.optString("platform"));
        if (platform.isEmpty()) {
            String photoId = record.optString("photoId");
            if (photoId.matches("[0-9a-fA-F]{24}") || id.matches("[0-9a-fA-F]{24}")) platform = "xhs";
            else if (photoId.matches("\\d{19}") || !record.optString("secUid").isEmpty()) platform = "douyin";
            else platform = Platforms.normalize(fallbackPlatform);
        }

        String secUid = record.optString("secUid");
        if (secUid.isEmpty() && user != null && platform.equals(Platforms.normalize(user.optString("platform")))) secUid = user.optString("secUid");
        return new JSONObject().put("authorId", id).put("authorName", name).put("platform", platform).put("authorSecUid", secUid);
    }
}
