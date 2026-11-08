package dev.local.commentcollector;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.security.SecureRandom;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

public final class CaptureProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://dev.local.commentcollector.capture");
    private static volatile CaptureProvider instance;
    private SQLiteDatabase db;

    @Override public boolean onCreate() {
        instance = this;
        db = getContext().openOrCreateDatabase("capture.db", 0, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, photo TEXT, status TEXT, pages INTEGER DEFAULT 0, total INTEGER, root_eof INTEGER DEFAULT 0, auto INTEGER, updated INTEGER, issue TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS comments (task INTEGER, id TEXT, root TEXT, replies INTEGER, data TEXT, PRIMARY KEY(task,id))");
        db.execSQL("CREATE INDEX IF NOT EXISTS comments_by_parent ON comments(task,root)");
        db.execSQL("CREATE TABLE IF NOT EXISTS pages (task INTEGER, fingerprint TEXT, PRIMARY KEY(task,fingerprint))");
        db.execSQL("CREATE TABLE IF NOT EXISTS groups_eof (task INTEGER, root TEXT, PRIMARY KEY(task,root))");
        db.execSQL("CREATE TABLE IF NOT EXISTS events (task INTEGER, time INTEGER, code TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS comments_by_id ON comments(id,task)");
        db.execSQL("CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, data TEXT, updated INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS posts (id TEXT PRIMARY KEY, data TEXT, updated INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, data TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS profile_targets (task INTEGER, user TEXT, status TEXT, issue TEXT, PRIMARY KEY(task,user))");
        db.execSQL("CREATE TABLE IF NOT EXISTS discovered (task INTEGER, post TEXT, PRIMARY KEY(task,post))");
        column("kind", "TEXT DEFAULT 'comment'");
        column("params", "TEXT DEFAULT '{}'");
        column("created", "INTEGER DEFAULT 0");
        column("retry_of", "INTEGER");
        column("generation", "INTEGER DEFAULT 1");
        column("root_evidence", "TEXT");
        column("paging_driver", "TEXT DEFAULT 'ui'");
        column("native_loads", "INTEGER DEFAULT 0");
        column("ui_scrolls", "INTEGER DEFAULT 0");
        column("profile_targets", "sec_uid", "TEXT");
        column("profile_targets", "attempts", "INTEGER DEFAULT 0");
        column("profile_targets", "updated", "INTEGER DEFAULT 0");
        backfillRecords();
        if ("true".equals(scalar("SELECT data FROM settings WHERE name='api_enabled'")))
            new Thread(() -> { try { LocalApi.enable(getContext()); } catch (Exception ignored) {} }, "collector-api-start").start();
        return true;
    }
    private void backfillRecords() {
        if (!scalar("SELECT data FROM settings WHERE name='schema_v2'").isEmpty()) return;
        db.beginTransaction();
        try (Cursor cursor = db.rawQuery("SELECT c.data,t.total FROM comments c LEFT JOIN tasks t ON t.id=c.task ORDER BY c.task", null)) {
            while (cursor.moveToNext()) {
                try {
                    JSONObject row = new JSONObject(cursor.getString(0));
                    String uid = row.optString("userId"), photo = row.optString("photoId");
                    if (!uid.isEmpty() && !"null".equals(uid)) db.execSQL("INSERT OR IGNORE INTO users VALUES (?,?,?)", new Object[]{uid,
                        new JSONObject().put("userId", uid).put("platform", "kuaishou").put("nickname", row.opt("nickname")).put("gender", "unknown")
                            .put("lastCommentIpRegion", row.opt("ipRegion")).toString(), row.optLong("capturedAt")});
                    if (!photo.isEmpty()) db.execSQL("INSERT OR IGNORE INTO posts VALUES (?,?,?)", new Object[]{photo,
                        new JSONObject().put("postId", photo).put("platform", "kuaishou").put("commentTotal", cursor.isNull(1) ? JSONObject.NULL : cursor.getLong(1))
                            .put("snapshotAt", row.optLong("capturedAt")).toString(), row.optLong("capturedAt")});
                } catch (Exception error) { throw new IllegalStateException("Existing comment migration failed", error); }
            }
            db.execSQL("INSERT INTO settings VALUES ('schema_v2','done')"); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    private void column(String name, String declaration) { column("tasks", name, declaration); }
    private void column(String table, String name, String declaration) {
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (cursor.moveToNext()) if (name.equals(cursor.getString(1))) return;
        }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + name + " " + declaration);
    }

    private boolean owner(int uid) { return uid == Process.myUid(); }
    private void authorize(int uid) {
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (!owner(uid) && (packages == null || java.util.Collections.disjoint(Arrays.asList(packages), Platforms.HOSTS)))
            throw new SecurityException("Caller is not in the collector scope");
    }
    private void authorize() { authorize(Binder.getCallingUid()); }

    static Bundle callFromService(String method, Bundle extras, int callingUid) {
        CaptureProvider value = instance;
        if (value == null || value.db == null) throw new IllegalStateException("采集服务尚未启动");
        value.authorize(callingUid);
        return value.callInternal(method, extras);
    }
    private long activeId() {
        try (Cursor cursor = db.rawQuery("SELECT id FROM tasks WHERE status IN ('running','running-replies','running-profiles','paused') ORDER BY id DESC LIMIT 1", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }
    private String scalar(String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) { return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : ""; }
    }
    private int count(String sql, long task) {
        return Integer.parseInt(scalar(sql, String.valueOf(task)));
    }
    private JSONObject status(long id) throws Exception {
        JSONObject out = new JSONObject().put("taskId", id);
        if (id == 0) return out.put("status", "idle");
        try (Cursor cursor = db.rawQuery("SELECT photo,status,pages,total,root_eof,auto,issue FROM tasks WHERE id=?", new String[]{String.valueOf(id)})) {
            if (!cursor.moveToFirst()) return out.put("status", "idle");
            out.put("photoId", cursor.getString(0)).put("status", cursor.getString(1)).put("pages", cursor.getInt(2))
                .put("total", cursor.isNull(3) ? JSONObject.NULL : cursor.getLong(3)).put("rootComplete", cursor.getInt(4) == 1)
                .put("auto", cursor.getInt(5) == 1).put("issue", cursor.getString(6));
        }
        out.put("comments", count("SELECT count(*) FROM comments WHERE task=?", id));
        out.put("rootPages", count("SELECT count(*) FROM pages WHERE task=? AND substr(fingerprint,1,1)=':'", id));
        out.put("platformCountGap", out.isNull("total") ? JSONObject.NULL : Math.max(0, out.optLong("total") - out.optLong("comments")));
        out.put("pendingReplies", count("SELECT count(*) FROM comments c WHERE c.task=? AND c.root='' AND c.replies>0 AND NOT EXISTS (SELECT 1 FROM groups_eof g WHERE g.task=c.task AND g.root=c.id)", id));
        out.put("kind", scalar("SELECT kind FROM tasks WHERE id=?", String.valueOf(id)));
        out.put("params", new JSONObject(scalar("SELECT params FROM tasks WHERE id=?", String.valueOf(id))));
        out.put("createdAt", Long.parseLong(scalar("SELECT created FROM tasks WHERE id=?", String.valueOf(id))));
        out.put("rootComments", count("SELECT count(*) FROM comments WHERE task=? AND root=''", id));
        out.put("replyComments", count("SELECT count(*) FROM comments WHERE task=? AND root<>''", id));
        out.put("replyGroups", count("SELECT count(*) FROM comments WHERE task=? AND root='' AND replies>0", id));
        out.put("replyGroupsComplete", count("SELECT count(*) FROM groups_eof WHERE task=?", id));
        out.put("generation", Long.parseLong(scalar("SELECT generation FROM tasks WHERE id=?", String.valueOf(id))));
        out.put("platformBlocked", !scalar("SELECT data FROM settings WHERE name='platform_block'").isEmpty());
        out.put("profilesTotal", count("SELECT count(*) FROM profile_targets WHERE task=?", id));
        out.put("profilesDone", count("SELECT count(*) FROM profile_targets WHERE task=? AND status='complete'", id));
        out.put("profilesFailed", count("SELECT count(*) FROM profile_targets WHERE task=? AND status='failed'", id));
        out.put("profilesPending", count("SELECT count(*) FROM profile_targets WHERE task=? AND status IN ('pending','running')", id));
        out.put("discoveredPosts", count("SELECT count(*) FROM discovered WHERE task=?", id));
        out.put("rootEvidence", scalar("SELECT root_evidence FROM tasks WHERE id=?", String.valueOf(id)));
        out.put("pagingDriver", scalar("SELECT paging_driver FROM tasks WHERE id=?", String.valueOf(id)));
        out.put("nativeLoads", Integer.parseInt(scalar("SELECT native_loads FROM tasks WHERE id=?", String.valueOf(id))));
        out.put("uiScrolls", Integer.parseInt(scalar("SELECT ui_scrolls FROM tasks WHERE id=?", String.valueOf(id))));
        JSONObject paramsObj = out.optJSONObject("params");
        String taskPlatform = paramsObj == null ? "" : paramsObj.optString("platform");
        String postId = out.optString("photoId");
        if (!postId.isEmpty()) {
            String postData = scalar("SELECT data FROM posts WHERE id=?", postId);
            if (!postData.isEmpty()) {
                try {
                    JSONObject post = new JSONObject(postData);
                    String postTitle = post.optString("title");
                    if (!postTitle.isEmpty() && !"null".equals(postTitle)) out.put("postTitle", postTitle);
                    if (taskPlatform.isEmpty()) taskPlatform = post.optString("platform");
                } catch (Exception ignored) {}
            }
            if (taskPlatform.isEmpty() && postId.matches("[0-9a-fA-F]{24}")) taskPlatform = "xhs";
        }
        if (!taskPlatform.isEmpty()) out.put("platform", taskPlatform);
        return out;
    }

    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        authorize();
        return callInternal(method, extras);
    }

    private synchronized Bundle callInternal(String method, Bundle extras) {
        Bundle out = new Bundle();
        try {
            JSONObject input = extras != null && extras.containsKey("body") ? new JSONObject(extras.getString("body")) : new JSONObject();
            if ("runtime".equals(method) || "runtime_update".equals(method)) {
                if ("runtime_update".equals(method)) {
                    JSONObject report = new JSONObject();
                    for (String key : Arrays.asList("loadedBuild", "appVersion", "pid", "stage", "nativeState", "boundPagers", "cachedRoots", "taskId", "generation", "errorType"))
                        if (input.has(key)) report.put(key, input.opt(key));
                    report.put("heartbeatAt", System.currentTimeMillis());
                    db.execSQL("INSERT OR REPLACE INTO settings VALUES ('hook_runtime',?)", new Object[]{report.toString()});
                }
                String saved = scalar("SELECT data FROM settings WHERE name='hook_runtime'");
                JSONObject report = saved.isEmpty() ? new JSONObject() : new JSONObject(saved);
                android.content.pm.PackageInfo installed = getContext().getPackageManager().getPackageInfo("dev.local.commentcollector", 0);
                report.put("installedVersion", installed.versionName).put("installedVersionCode", installed.getLongVersionCode());
                out.putString("data", report.toString()); return out;
            }
            if ("health".equals(method)) {
                out.putString("data", new JSONObject().put("apiVersion", 1).put("platform", "kuaishou")
                    .put("storageReady", true).put("comments", Integer.parseInt(scalar("SELECT count(DISTINCT id) FROM comments")))
                    .put("posts", Integer.parseInt(scalar("SELECT count(*) FROM posts")))
                    .put("users", Integer.parseInt(scalar("SELECT count(*) FROM users")))
                    .put("platformBlock", scalar("SELECT data FROM settings WHERE name='platform_block'")).toString());
                return out;
            }
            if ("api_control".equals(method)) {
                if (input.has("enabled")) {
                    if (input.getBoolean("enabled")) LocalApi.enable(getContext()); else LocalApi.disable();
                    db.execSQL("INSERT OR REPLACE INTO settings VALUES ('api_enabled',?)", new Object[]{String.valueOf(input.getBoolean("enabled"))});
                }
                out.putString("data", new JSONObject().put("running", LocalApi.running()).put("port", LocalApi.PORT).toString()); return out;
            }
            if ("comments".equals(method)) { out.putString("data", comments(input).toString()); return out; }
            if ("analysis".equals(method)) { out.putString("data", Analysis.run(input, (query, sink, max) -> comments(query, sink, max)).toString()); return out; }
            if ("batch_start".equals(method)) {
                JSONArray requests = input.getJSONArray("tasks"), results = new JSONArray();
                if (requests.length() == 0 || requests.length() > 50) throw new IllegalArgumentException("批量任务需包含 1 至 50 个任务");
                for (int i = 0; i < requests.length(); i++) {
                    JSONObject request = requests.getJSONObject(i).put("enqueue", true); Bundle payload = new Bundle(); payload.putString("body", request.toString());
                    Bundle result = callInternal("start", payload);
                    results.put(result.containsKey("error") ? new JSONObject().put("ok", false).put("error", result.getString("error")) :
                        new JSONObject().put("ok", true).put("task", new JSONObject(result.getString("json"))));
                }
                out.putString("data", new JSONObject().put("items", results).toString()); return out;
            }
            if ("users".equals(method)) { out.putString("data", users(input).toString()); return out; }
            if ("summary".equals(method)) {
                String platform = input.optString("platform");
                if (platform.isEmpty() || "all".equals(platform)) {
                    out.putString("data", new JSONObject().put("posts", Integer.parseInt(scalar("SELECT count(*) FROM posts")))
                        .put("comments", Integer.parseInt(scalar("SELECT count(DISTINCT id) FROM comments"))).put("users", Integer.parseInt(scalar("SELECT count(*) FROM users"))).toString());
                } else {
                    String test = " WHERE coalesce(json_extract(data,'$.platform'),'kuaishou')=?";
                    out.putString("data", new JSONObject().put("posts", Integer.parseInt(scalar("SELECT count(*) FROM posts" + test, platform)))
                        .put("comments", Integer.parseInt(scalar("SELECT count(DISTINCT id) FROM comments" + test, platform)))
                        .put("users", Integer.parseInt(scalar("SELECT count(*) FROM users" + test, platform))).toString());
                }
                return out;
            }
            if ("posts".equals(method)) { out.putString("data", records("posts", input).toString()); return out; }
            if ("cloud_snapshot".equals(method)) { out.putString("data", cloudSnapshot().toString()); return out; }
            if ("record_profile".equals(method)) {
                JSONObject user = input.getJSONObject("user"); String uid = user.getString("userId");
                mergeRecord("users", uid, user);
                long active = activeId();
                long targetTask = input.optLong("taskId", active);
                db.execSQL("UPDATE profile_targets SET status='complete',issue=NULL,updated=? WHERE user=? AND (task=? OR ?=0)",
                    new Object[]{System.currentTimeMillis(), uid, targetTask, targetTask});
                if (targetTask != 0) {
                    event(targetTask, "public_profile_saved");
                    finishProfiles(targetTask);
                }
                out.putString("data", new JSONObject().put("saved", true).toString()); return out;
            }
            if ("profile_queue_add".equals(method)) {
                long targetTask = input.getLong("taskId");
                JSONArray uids = input.optJSONArray("userIds");
                JSONObject secMap = input.optJSONObject("secUids");
                int added = 0;
                db.beginTransaction();
                try {
                    if (uids != null) {
                        for (int i = 0; i < uids.length(); i++) {
                            String uid = uids.optString(i);
                            if (uid.isEmpty()) continue;
                            String sec = secMap != null ? secMap.optString(uid) : "";
                            String userJson = scalar("SELECT data FROM users WHERE id=?", uid);
                            if (!userJson.isEmpty()) {
                                try { if (new JSONObject(userJson).has("profileCapturedAt")) continue; } catch (Throwable ignored) {}
                            }
                            db.execSQL("INSERT OR IGNORE INTO profile_targets (task, user, status, issue, sec_uid, attempts, updated) VALUES (?, ?, 'pending', NULL, ?, 0, ?)",
                                new Object[]{targetTask, uid, sec, System.currentTimeMillis()});
                            added++;
                        }
                    }
                    if (input.optBoolean("backfillTask")) {
                        String sql = "SELECT DISTINCT json_extract(c.data, '$.userId'), coalesce(json_extract(c.data, '$.secUid'), '') " +
                                     "FROM comments c LEFT JOIN users u ON json_extract(c.data, '$.userId') = u.id " +
                                     "WHERE c.task=? AND json_extract(c.data, '$.userId') IS NOT NULL AND json_extract(c.data, '$.userId') != '' " +
                                     "AND (u.data IS NULL OR json_extract(u.data, '$.profileCapturedAt') IS NULL)";
                        try (Cursor cursor = db.rawQuery(sql, new String[]{String.valueOf(targetTask)})) {
                            while (cursor.moveToNext()) {
                                String uid = cursor.getString(0);
                                String sec = cursor.getString(1);
                                if (uid != null && !uid.isEmpty()) {
                                    db.execSQL("INSERT OR IGNORE INTO profile_targets (task, user, status, issue, sec_uid, attempts, updated) VALUES (?, ?, 'pending', NULL, ?, 0, ?)",
                                        new Object[]{targetTask, uid, sec == null ? "" : sec, System.currentTimeMillis()});
                                    added++;
                                }
                            }
                        }
                    }
                    String prio = input.optString("prioritizeUser");
                    if (!prio.isEmpty()) {
                        db.execSQL("INSERT OR REPLACE INTO profile_targets (task, user, status, issue, sec_uid, attempts, updated) VALUES (?, ?, 'pending', NULL, ?, 0, 0)",
                            new Object[]{targetTask, prio, input.optString("secUid", "")});
                        added++;
                    }
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                out.putString("data", new JSONObject().put("added", added).toString()); return out;
            }
            if ("profile_queue_next".equals(method)) {
                long targetTask = input.optLong("taskId", activeId());
                if (targetTask == 0) { out.putString("data", new JSONObject().put("empty", true).toString()); return out; }
                String user = null;
                String secUid = "";
                try (Cursor cursor = db.rawQuery("SELECT user, coalesce(sec_uid,'') FROM profile_targets WHERE task=? AND status='pending' ORDER BY updated ASC, user ASC LIMIT 1",
                    new String[]{String.valueOf(targetTask)})) {
                    if (cursor.moveToFirst()) {
                        user = cursor.getString(0);
                        secUid = cursor.getString(1);
                    }
                }
                if (user == null || user.isEmpty()) {
                    out.putString("data", new JSONObject().put("empty", true).toString());
                    return out;
                }
                db.execSQL("UPDATE profile_targets SET status='running', updated=? WHERE task=? AND user=?",
                    new Object[]{System.currentTimeMillis(), targetTask, user});
                JSONObject res = new JSONObject().put("empty", false).put("userId", user).put("secUid", secUid).put("taskId", targetTask);
                out.putString("data", res.toString());
                return out;
            }
            if ("profile_queue_fail".equals(method)) {
                long targetTask = input.getLong("taskId");
                String uid = input.getString("userId");
                String issue = input.optString("issue", "profile_fetch_failed");
                db.execSQL("UPDATE profile_targets SET attempts=attempts+1, status=CASE WHEN attempts>=2 THEN 'failed' ELSE 'pending' END, issue=?, updated=? WHERE task=? AND user=?",
                    new Object[]{issue, System.currentTimeMillis(), targetTask, uid});
                finishProfiles(targetTask);
                out.putString("data", new JSONObject().put("ok", true).toString());
                return out;
            }
            if ("record_post".equals(method)) {
                JSONObject post = input.getJSONObject("post"); mergeRecord("posts", post.getString("postId"), post);
                out.putString("data", "{\"saved\":true}"); return out;
            }
            if ("record_search".equals(method)) {
                long active = activeId(); JSONObject task = status(active), page = input.getJSONObject("page");
                if (active != 0 && "discovery".equals(task.optString("kind")) && "running".equals(task.optString("status")) &&
                    input.optLong("taskId") == active && input.optLong("generation") == task.optLong("generation") &&
                    page.optString("keyword").equals(task.getJSONObject("params").optString("keyword"))) {
                    JSONArray posts = page.getJSONArray("posts"); StringBuilder fingerprint = new StringBuilder(page.optString("cursor"));
                    for (int i = 0; i < posts.length(); i++) fingerprint.append(':').append(posts.getJSONObject(i).getString("postId"));
                    ContentValues values = new ContentValues(); values.put("task", active); values.put("fingerprint", fingerprint.toString());
                    db.beginTransaction();
                    try {
                        if (db.insertWithOnConflict("pages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1) {
                            db.execSQL("UPDATE tasks SET pages=pages+1,updated=? WHERE id=?", new Object[]{System.currentTimeMillis(), active});
                            for (int i = 0; i < posts.length(); i++) {
                                JSONObject post = posts.getJSONObject(i).put("searchKeyword", page.optString("keyword")); String pid = post.getString("postId");
                                mergeRecord("posts", pid, post); db.execSQL("INSERT OR IGNORE INTO discovered VALUES (?,?)", new Object[]{active, pid});
                            }
                            JSONObject current = status(active); JSONObject params = task.getJSONObject("params");
                            String done = current.optInt("discoveredPosts") >= params.optInt("target", 20) ? "target_reached" :
                                Boolean.FALSE.equals(page.opt("hasMore")) ? "search_eof_confirmed" : "";
                            if (!done.isEmpty()) { db.execSQL("UPDATE tasks SET status='complete',issue=? WHERE id=?", new Object[]{done, active}); event(active, done); }
                            else if (current.optInt("pages") >= params.optInt("maxPages", 20)) {
                                db.execSQL("UPDATE tasks SET status='paused',issue='search_page_limit' WHERE id=?", new Object[]{active}); event(active, "search_page_limit");
                            }
                        }
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    if ("complete".equals(status(active).optString("status"))) advance();
                }
                out.putString("data", "{\"saved\":true}"); return out;
            }
            if ("profile_next".equals(method)) {
                long active = activeId();
                if (active == 0 || !"profile".equals(status(active).optString("kind"))) throw new IllegalStateException("没有资料任务");
                if (input.optLong("taskId") != active || !status(active).optString("status").startsWith("running")) throw new IllegalStateException("资料任务已变化");
                if (input.has("failedUserId")) {
                    db.execSQL("UPDATE profile_targets SET status='failed',issue='profile_not_returned' WHERE task=? AND user=? AND status='pending'", new Object[]{active, input.getString("failedUserId")});
                    event(active, "profile_not_returned"); finishProfiles(active);
                }
                out.putString("data", new JSONObject().put("userId", scalar("SELECT user FROM profile_targets WHERE task=? AND status='pending' ORDER BY user LIMIT 1", String.valueOf(active))).toString()); return out;
            }
            if ("tasks".equals(method)) {
                JSONArray tasks = new JSONArray();
                try (Cursor cursor = db.rawQuery("SELECT id FROM tasks ORDER BY id DESC LIMIT 100", null)) {
                    while (cursor.moveToNext()) tasks.put(status(cursor.getLong(0)));
                }
                out.putString("data", new JSONObject().put("items", tasks).toString()); return out;
            }
            if ("events".equals(method)) {
                JSONArray events = new JSONArray();
                try (Cursor cursor = db.rawQuery("SELECT time,code FROM events WHERE task=? ORDER BY time DESC LIMIT 200", new String[]{String.valueOf(input.getLong("taskId"))})) {
                    while (cursor.moveToNext()) events.put(new JSONObject().put("time", cursor.getLong(0)).put("code", cursor.getString(1)));
                }
                out.putString("data", new JSONObject().put("items", events).toString()); return out;
            }
            if ("targets".equals(method)) {
                JSONArray targets = new JSONArray();
                try (Cursor cursor = db.rawQuery("SELECT user,status,issue FROM profile_targets WHERE task=? ORDER BY user LIMIT 200", new String[]{String.valueOf(input.getLong("taskId"))})) {
                    while (cursor.moveToNext()) targets.put(new JSONObject().put("userId", cursor.getString(0)).put("status", cursor.getString(1)).put("issue", cursor.isNull(2) ? JSONObject.NULL : cursor.getString(2)));
                }
                out.putString("data", new JSONObject().put("items", targets).toString()); return out;
            }
            if ("settings_get".equals(method) || "settings_save".equals(method)) {
                String name = input.optString("name", "filters");
                if (!name.matches("filters|analysis|ui|cloud|video_info")) throw new IllegalArgumentException("未知设置组");
                if ("settings_save".equals(method)) db.execSQL("INSERT OR REPLACE INTO settings VALUES (?,?)", new Object[]{name, input.getJSONObject("value").toString()});
                String value = scalar("SELECT data FROM settings WHERE name=?", name);
                out.putString("data", value.isEmpty() ? "{}" : value); return out;
            }
            if ("api_token".equals(method)) {
                String value = scalar("SELECT data FROM settings WHERE name='api_token'");
                if (value.isEmpty() || input.optBoolean("rotate")) {
                    byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
                    value = Base64.encodeToString(random, Base64.NO_WRAP | Base64.URL_SAFE);
                    db.execSQL("INSERT OR REPLACE INTO settings VALUES ('api_token',?)", new Object[]{value});
                }
                out.putString("data", new JSONObject().put("token", value).toString()); return out;
            }
            if ("delete_task".equals(method)) {
                long targetId = input.getLong("taskId");
                db.beginTransaction();
                try {
                    String[] arg = new String[]{String.valueOf(targetId)};
                    db.execSQL("DELETE FROM comments WHERE task=?", arg);
                    db.execSQL("DELETE FROM pages WHERE task=?", arg);
                    db.execSQL("DELETE FROM groups_eof WHERE task=?", arg);
                    db.execSQL("DELETE FROM events WHERE task=?", arg);
                    db.execSQL("DELETE FROM profile_targets WHERE task=?", arg);
                    db.execSQL("DELETE FROM discovered WHERE task=?", arg);
                    db.execSQL("DELETE FROM tasks WHERE id=?", arg);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                advance();
                long current = activeId();
                writeStatus(status(current));
                JSONObject res = new JSONObject().put("deleted", true).put("taskId", targetId);
                out.putString("data", res.toString());
                out.putString("json", res.toString());
                return out;
            }
            if ("refresh_task".equals(method)) {
                long targetId = input.getLong("taskId");
                JSONObject previous = status(targetId);
                if ("idle".equals(previous.optString("status"))) throw new IllegalStateException("任务不存在");
                String[] arg = new String[]{String.valueOf(targetId)};
                db.beginTransaction();
                try {
                    db.execSQL("DELETE FROM pages WHERE task=?", arg);
                    db.execSQL("DELETE FROM groups_eof WHERE task=?", arg);
                    if ("profile".equals(previous.optString("kind"))) {
                        db.execSQL("UPDATE profile_targets SET status='pending',issue=NULL WHERE task=?", arg);
                    }
                    long currentActive = activeId();
                    long now = System.currentTimeMillis();
                    if (currentActive != 0 && currentActive != targetId) {
                        if ("paused".equals(status(currentActive).optString("status"))) {
                            db.execSQL("UPDATE tasks SET status='stopped',issue='superseded_by_refresh',updated=? WHERE id=?", new Object[]{now, currentActive});
                        } else {
                            db.execSQL("UPDATE tasks SET status='queued',pages=0,root_eof=0,root_evidence=NULL,issue=NULL,auto=1,generation=generation+1,updated=? WHERE id=?", new Object[]{now, targetId});
                            event(targetId, "queued_for_refresh");
                            db.setTransactionSuccessful();
                            JSONObject st = status(targetId);
                            out.putString("data", st.toString());
                            out.putString("json", st.toString());
                            return out;
                        }
                    }
                    db.execSQL("DELETE FROM settings WHERE name='platform_block'");
                    String plat = previous.optJSONObject("params") != null ? previous.optJSONObject("params").optString("platform") : "";
                    String nextDriver = "douyin".equals(plat) ? "native-root" : "ui";
                    String nextState = "profile".equals(previous.optString("kind")) ? "running-profiles" : "running";
                    db.execSQL("UPDATE tasks SET status=?,pages=0,root_eof=0,root_evidence=NULL,issue=NULL,auto=1,paging_driver=?,generation=generation+1,updated=? WHERE id=?", new Object[]{nextState, nextDriver, now, targetId});
                    event(targetId, "refreshed");
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                JSONObject st = status(targetId);
                writeStatus(st);
                out.putString("data", st.toString());
                out.putString("json", st.toString());
                return out;
            }
            long id = activeId();
            if ("append".equals(method)) {
                if (extras == null || extras.getLong("taskId") != id || id == 0 ||
                    extras.getLong("generation") != status(id).optLong("generation")) {
                    out.putString("json", status(id).toString()); return out;
                }
            }
            if (input.has("taskId") && !"start".equals(method) && !"append".equals(method)) {
                long requested = input.getLong("taskId");
                if (!"status".equals(method) && !"export".equals(method) && !"retry".equals(method) && !"resume".equals(method) && requested != id &&
                    !("stop".equals(method) && "queued".equals(status(requested).optString("status"))))
                    throw new IllegalStateException("任务已变化，请刷新状态");
                id = requested;
            }
            if ("status".equals(method) && input.has("taskId")) id = input.getLong("taskId");
            if ("paging_driver".equals(method) || "pending_reply_ids".equals(method) || "native_eof".equals(method)) {
                JSONObject current = status(id);
                if (id == 0 || input.optLong("generation") != current.optLong("generation") || !current.optString("status").startsWith("running"))
                    throw new IllegalStateException("任务已变化，请刷新状态");
                if ("pending_reply_ids".equals(method)) {
                    JSONArray ids = new JSONArray();
                    try (Cursor cursor = db.rawQuery("SELECT c.id FROM comments c WHERE c.task=? AND c.root='' AND c.replies>0 AND NOT EXISTS (SELECT 1 FROM groups_eof g WHERE g.task=c.task AND g.root=c.id) ORDER BY c.id LIMIT 2000", new String[]{String.valueOf(id)})) {
                        while (cursor.moveToNext()) ids.put(cursor.getString(0));
                    }
                    out.putString("data", new JSONObject().put("items", ids).toString()); return out;
                }
                if ("native_eof".equals(method)) {
                    String stream = input.optString("stream", input.optString("phase"));
                    if (!"root".equals(stream)) throw new IllegalArgumentException("原生末页流无效");
                    String evidence = input.optString("evidence");
                    if (!"native_response_has_more_false".equals(evidence)) throw new IllegalArgumentException("原生末页证据无效");
                    db.execSQL("UPDATE tasks SET root_eof=1,root_evidence=?,updated=? WHERE id=?", new Object[]{evidence, System.currentTimeMillis(), id});
                    JSONObject after = status(id);
                    int pendingReplies = after.optInt("pendingReplies");
                    int pendingProfiles = after.optInt("profilesPending");
                    String next = pendingReplies > 0 ? "running-replies" : pendingProfiles > 0 ? "running-profiles" : "complete";
                    String nextIssue = "running-profiles".equals(next) ? "采集资料中" : null;
                    db.execSQL("UPDATE tasks SET status=?,issue=?,updated=? WHERE id=?", new Object[]{next, nextIssue, System.currentTimeMillis(), id});
                    event(id, "root_eof:" + evidence);
                    if ("complete".equals(next)) advance();
                    out.putString("data", status(id).toString()); return out;
                }
                String driver = input.optString("driver");
                if (!Arrays.asList("ui", "native-root", "native-replies").contains(driver)) throw new IllegalArgumentException("分页模式无效");
                boolean changed = !driver.equals(current.optString("pagingDriver"));
                db.execSQL("UPDATE tasks SET paging_driver=?,native_loads=native_loads+?,ui_scrolls=ui_scrolls+? WHERE id=?", new Object[]{driver, input.optBoolean("nativeLoad") ? 1 : 0, input.optBoolean("uiScroll") ? 1 : 0, id});
                if (changed) event(id, "paging_driver:" + driver);
                out.putString("data", status(id).toString()); return out;
            }
            if ("retry".equals(method)) {
                long original = input.getLong("taskId");
                JSONObject previous = status(original);
                if (previous.optString("status").startsWith("running") || "queued".equals(previous.optString("status")))
                    throw new IllegalStateException("原任务仍在执行");
                if (!Arrays.asList("paused", "stopped").contains(previous.optString("status")))
                    throw new IllegalStateException("只有未完成任务可续跑");
                id = original; method = "resume";
            }
            if ("start".equals(method)) {
                String photo = input.has("photoId") ? input.optString("photoId") : extras.getString("photoId", "");
                photo = photo.trim();
                if (!photo.isEmpty() && !photo.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("作品 ID 格式不正确");
                String kind = input.optString("kind", "comment");
                if (!Arrays.asList("comment", "profile", "discovery").contains(kind)) throw new IllegalArgumentException("任务类型无效");
                if ("comment".equals(kind) && photo.isEmpty()) throw new IllegalArgumentException("请提供明确的作品 ID");
                if (!Arrays.asList("auto", "native", "ui").contains(input.optString("pagingMode", "auto"))) throw new IllegalArgumentException("分页模式无效");
                if ("discovery".equals(kind) && (input.optString("keyword").isBlank() || input.optString("keyword").length() > 100))
                    throw new IllegalArgumentException("请输入搜索关键词（最多 100 字）");
                if ("discovery".equals(kind) && (input.optInt("target", 20) < 1 || input.optInt("target", 20) > 1000 || input.optInt("maxPages", 20) < 1 || input.optInt("maxPages", 20) > 200))
                    throw new IllegalArgumentException("目标数量范围 1 至 1000，页数范围 1 至 200");
                if ("profile".equals(kind) && (input.optJSONArray("userIds") == null || input.getJSONArray("userIds").length() == 0 || input.getJSONArray("userIds").length() > 200))
                    throw new IllegalArgumentException("资料任务需包含 1 至 200 个用户");
                if ("profile".equals(kind)) for (int i = 0; i < input.getJSONArray("userIds").length(); i++)
                    if (!input.getJSONArray("userIds").getString(i).matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("用户 ID 格式不正确");
                if ("profile".equals(kind)) {
                    java.util.Set<String> targets = new java.util.TreeSet<>();
                    for (int i = 0; i < input.getJSONArray("userIds").length(); i++) targets.add(input.getJSONArray("userIds").getString(i));
                    input.put("userIds", new JSONArray(targets));
                }
                input.put("photoId", photo);
                JSONObject storedParams = new JSONObject(input.toString()); storedParams.remove("enqueue"); storedParams.remove("retryOf");
                String fingerprint = JsonContracts.canonical(storedParams);
                String duplicate = "comment".equals(kind) ? scalar("SELECT id FROM tasks WHERE photo=? AND kind=? AND status IN ('queued','running','running-replies','running-profiles','paused') LIMIT 1", photo, kind) :
                    scalar("SELECT id FROM tasks WHERE params=? AND status IN ('queued','running','running-replies','running-profiles','paused') LIMIT 1", fingerprint);
                if (!duplicate.isEmpty()) { out.putString("json", status(Long.parseLong(duplicate)).put("duplicate", true).toString()); return out; }
                // A paused task (stalled paging, verification, etc.) must not hold a new task in the queue
                // forever. Park it as stopped — it stays resumable via "续跑" — and run the new task now.
                if (id != 0 && "paused".equals(status(id).optString("status"))) {
                    db.execSQL("UPDATE tasks SET status='stopped',generation=generation+1,updated=? WHERE id=?", new Object[]{System.currentTimeMillis(), id});
                    event(id, "superseded_by_new_task");
                    id = 0;
                }
                if (id != 0 && !input.optBoolean("enqueue")) throw new IllegalStateException("已有任务，请继续或加入队列");
                ContentValues values = new ContentValues();
                values.put("photo", photo); values.put("status", id != 0 || !scalar("SELECT data FROM settings WHERE name='platform_block'").isEmpty() ? "queued" : "running");
                String startPlat = input.optString("platform");
                if (startPlat.isEmpty()) startPlat = photo.matches("[0-9a-fA-F]{24}") ? "xhs" : photo.matches("\\d{19}") ? "douyin" : "kuaishou";
                String initialDriver = "auto".equals(input.optString("pagingMode", "auto")) ? ("douyin".equals(startPlat) ? "native-root" : "ui") : input.optString("pagingMode");
                values.put("paging_driver", initialDriver);
                values.put("auto", input.has("auto") ? (input.optBoolean("auto") ? 1 : 0) : extras != null && extras.containsKey("auto") ? (extras.getBoolean("auto") ? 1 : 0) : 1);
                values.put("kind", kind); values.put("params", fingerprint); values.put("created", System.currentTimeMillis());
                if (input.has("retryOf")) values.put("retry_of", input.getLong("retryOf"));
                values.put("updated", System.currentTimeMillis());
                id = db.insertOrThrow("tasks", null, values);
                if ("profile".equals(kind)) {
                    JSONArray targets = input.getJSONArray("userIds");
                    for (int i = 0; i < targets.length(); i++) {
                        String uid = targets.getString(i);
                        db.execSQL("INSERT OR IGNORE INTO profile_targets VALUES (?,?,'pending',NULL)", new Object[]{id, uid});
                    }
                }
                event(id, "created");
            } else if ("resume".equals(method) || "stop".equals(method)) {
                if (id == 0 && "resume".equals(method)) {
                    id = latestId();
                    if (id == 0 || !"stopped".equals(status(id).optString("status")))
                        throw new IllegalStateException("没有可继续的未完成任务");
                }
                JSONObject previous = status(id);
                if ("resume".equals(method) && !Arrays.asList("paused", "stopped").contains(previous.optString("status")))
                    throw new IllegalStateException("任务不可继续");
                if ("resume".equals(method)) {
                    long currentActive = activeId();
                    if (currentActive != 0 && currentActive != id) {
                        JSONObject activeTask = status(currentActive);
                        if ("paused".equals(activeTask.optString("status"))) {
                            db.execSQL("UPDATE tasks SET status='stopped',generation=generation+1,updated=? WHERE id=?", new Object[]{System.currentTimeMillis(), currentActive});
                            event(currentActive, "superseded_by_resume");
                        } else {
                            db.execSQL("UPDATE tasks SET status='queued',issue=NULL,generation=generation+1,updated=? WHERE id=?", new Object[]{System.currentTimeMillis(), id});
                            event(id, "queued_for_resume");
                            out.putString("json", status(id).toString());
                            writeStatus(status(id));
                            return out;
                        }
                    }
                    db.execSQL("DELETE FROM settings WHERE name='platform_block'");
                }
                String nextState = "resume".equals(method) ? ("profile".equals(previous.optString("kind")) ? "running-profiles" : previous.optBoolean("rootComplete") ? "running-replies" : "running") : "stopped";
                if ("resume".equals(method) && "profile".equals(previous.optString("kind")))
                    db.execSQL("UPDATE profile_targets SET status='pending',issue=NULL WHERE task=? AND status='failed'", new Object[]{id});
                db.execSQL("UPDATE tasks SET status=?,issue=NULL,generation=generation+1,updated=? WHERE id=?", new Object[]{nextState, System.currentTimeMillis(), id});
                event(id, "resume".equals(method) ? "resumed" : "stopped");
                if ("stop".equals(method)) advance();
            } else if ("auto".equals(method) && id != 0) {
                db.execSQL("UPDATE tasks SET auto=?,updated=? WHERE id=?", new Object[]{input.getBoolean("enabled") ? 1 : 0, System.currentTimeMillis(), id});
                event(id, input.getBoolean("enabled") ? "auto_enabled" : "auto_disabled");
            } else if ("pause".equals(method) && id != 0) {
                if (input.has("generation") && input.optLong("generation") != status(id).optLong("generation")) {
                    out.putString("json", status(id).toString()); return out;
                }
                String code = input.has("code") ? input.optString("code") : extras.getString("code", "capture_error");
                if (!code.matches("[a-z0-9_:.-]{1,120}")) code = "capture_error";
                db.execSQL("UPDATE tasks SET status='paused',issue=?,updated=? WHERE id=?", new Object[]{code, System.currentTimeMillis(), id});
                if (code.matches("manual_(?:login|verification)_required|http_(?:401|403|406|429)"))
                    db.execSQL("INSERT OR REPLACE INTO settings VALUES ('platform_block',?)", new Object[]{code});
                event(id, code);
            } else if ("append".equals(method) && id != 0) {
                JSONObject page = new JSONObject(extras.getString("page"));
                append(id, page);
            } else if ("export".equals(method)) {
                if (id == 0) id = latestId();
                export(id);
                out.putString("file", new File(getContext().getFilesDir(), "export.json").getAbsolutePath());
            } else if (!"status".equals(method) && !"start".equals(method) && !"resume".equals(method) && !"stop".equals(method) && !"pause".equals(method) && !"append".equals(method)) {
                throw new IllegalArgumentException("Unknown operation");
            }
            if (id == 0) id = latestId();
            out.putString("json", status(id).toString());
            writeStatus(status(id));
        } catch (Exception error) { out.putString("error", error.getMessage()); }
        return out;
    }

    private long latestId() {
        String value = scalar("SELECT max(id) FROM tasks");
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }
    private void event(long id, String code) {
        db.execSQL("INSERT INTO events VALUES (?,?,?)", new Object[]{id, System.currentTimeMillis(), code});
    }
    private void append(long id, JSONObject page) throws Exception {
        JSONObject before = status(id);
        if (!"comment".equals(before.optString("kind")) || !before.optString("status").startsWith("running")) return;
        String photo = page.getString("photoId");
        if (photo.isEmpty() || (!before.optString("photoId").isEmpty() && !photo.equals(before.getString("photoId")))) return;
        JSONArray rows = page.getJSONArray("rows");
        String root = page.optString("rootId");
        StringBuilder fingerprint = new StringBuilder(root).append(':').append(page.optString("cursor")).append(':').append(page.opt("hasMore"));
        for (int i = 0; i < rows.length(); i++) fingerprint.append(':').append(rows.getJSONObject(i).getString("commentId"));
        db.beginTransaction();
        try {
            ContentValues pageValues = new ContentValues(); pageValues.put("task", id); pageValues.put("fingerprint", fingerprint.toString());
            boolean newPage = db.insertWithOnConflict("pages", null, pageValues, SQLiteDatabase.CONFLICT_IGNORE) != -1;
            db.execSQL("UPDATE tasks SET photo=?,pages=pages+?,updated=? WHERE id=?", new Object[]{photo, newPage ? 1 : 0, System.currentTimeMillis(), id});
            String existing = scalar("SELECT data FROM posts WHERE id=?", photo);
            JSONObject existingPost = existing.isEmpty() ? new JSONObject() : new JSONObject(existing);
            String pagePlatform = page.optString("platform");
            if (pagePlatform.isEmpty() && rows.length() > 0) pagePlatform = rows.getJSONObject(0).optString("platform");
            if (pagePlatform.isEmpty()) pagePlatform = existingPost.optString("platform");
            if (pagePlatform.isEmpty()) pagePlatform = page.optString("source").startsWith("xhs") ? "xhs" : page.optString("source").startsWith("douyin") ? "douyin" : "kuaishou";
            JSONObject post = existingPost.put("platform", pagePlatform).put("postId", photo)
                .put("snapshotAt", System.currentTimeMillis()).put("source", page.optString("source"));
            if (!page.isNull("total")) post.put("commentTotal", page.opt("total"));
            db.execSQL("INSERT OR REPLACE INTO posts VALUES (?,?,?)", new Object[]{photo, post.toString(), System.currentTimeMillis()});
            if (!page.isNull("total")) db.execSQL("UPDATE tasks SET total=? WHERE id=?", new Object[]{page.getLong("total"), id});
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if (!photo.equals(row.getString("photoId"))) throw new IllegalArgumentException("Mixed post data");
                String parent = row.optString("rootId");
                if (parent.equals(row.getString("commentId"))) parent = "";
                ContentValues values = new ContentValues(); values.put("task", id); values.put("id", row.getString("commentId"));
                values.put("root", parent); values.put("replies", row.isNull("replies") ? 0 : row.optInt("replies")); values.put("data", row.toString());
                db.insertWithOnConflict("comments", null, values, SQLiteDatabase.CONFLICT_REPLACE);
                String userId = row.optString("userId");
                if (!userId.isEmpty()) {
                    JSONObject user = new JSONObject().put("userId", userId).put("platform", row.optString("platform", pagePlatform)).put("nickname", row.opt("nickname"))
                        .put("lastCommentIpRegion", row.opt("ipRegion")).put("lastCommentAt", row.optLong("created")).put("lastCapturedAt", row.optLong("capturedAt"));
                    if (!row.optString("avatarUrl").isEmpty()) user.put("avatarUrl", row.optString("avatarUrl"));
                    if (!"unknown".equals(row.optString("gender", "unknown"))) user.put("gender", row.optString("gender"));
                    mergeRecord("users", userId, user);
                    String secUid = row.optString("secUid");
                    String userJson = scalar("SELECT data FROM users WHERE id=?", userId);
                    boolean hasProfile = false;
                    if (!userJson.isEmpty()) {
                        try { hasProfile = new JSONObject(userJson).has("profileCapturedAt"); } catch (Throwable ignored) {}
                    }
                    if (!hasProfile) {
                        db.execSQL("INSERT OR IGNORE INTO profile_targets (task, user, status, issue, sec_uid, attempts, updated) VALUES (?, ?, 'pending', NULL, ?, 0, ?)",
                            new Object[]{id, userId, secUid, System.currentTimeMillis()});
                    }
                }
            }
            if (page.has("hasMore") && !page.isNull("hasMore") && !page.getBoolean("hasMore")) {
                if (root.isEmpty()) db.execSQL("UPDATE tasks SET root_eof=1,root_evidence=? WHERE id=?", new Object[]{page.optString("eofEvidence", "explicit_has_more_false"), id});
                else markGroup(id, root);
            }
            JSONArray groups = page.optJSONArray("groups");
            if (groups != null) for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.getJSONObject(i);
                if (group.optBoolean("complete")) markGroup(id, group.getString("rootId"));
            }
            JSONObject after = status(id);
            if (after.optBoolean("rootComplete")) {
                int pendingReplies = count("SELECT count(*) FROM comments c WHERE c.task=? AND c.root='' AND c.replies>0 AND NOT EXISTS (SELECT 1 FROM groups_eof g WHERE g.task=c.task AND g.root=c.id)", id);
                int pendingProfiles = count("SELECT count(*) FROM profile_targets WHERE task=? AND status IN ('pending','running')", id);
                String nextState = pendingReplies > 0 ? "running-replies" : pendingProfiles > 0 ? "running-profiles" : "complete";
                String nextIssue = "running-profiles".equals(nextState) ? "采集资料中" : null;
                db.execSQL("UPDATE tasks SET status=?,issue=? WHERE id=?", new Object[]{nextState, nextIssue, id});
            }
            event(id, page.optBoolean("fromNativeCache") ? "cached_root_page_saved" : newPage ? "page_saved" : "page_refreshed");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        enforceLimits(id);
        if ("complete".equals(status(id).optString("status"))) advance();
    }
    /**
     * Optional stop conditions from the task's own conditions: targetCount (matching comments) and
     * maxPages (root pages). Reaching them stops the task with an explicit reason; it is never reported
     * as a confirmed end of the comment list.
     */
    private void enforceLimits(long id) throws Exception {
        JSONObject task = status(id);
        if (!"comment".equals(task.optString("kind")) || !task.optString("status").startsWith("running")) return;
        JSONObject params = task.optJSONObject("params"); if (params == null) return;
        String reason = null;
        int maxPages = params.optInt("maxPages");
        if (maxPages > 0 && task.optInt("pages") >= maxPages) reason = "page_limit_reached";
        int target = params.optInt("targetCount");
        if (reason == null && target > 0) {
            JSONObject query = new JSONObject(params.toString()).put("taskId", id).put("limit", 1); query.remove("photoId");
            if (comments(query).optInt("total") >= target) reason = "target_reached";
        }
        if (reason == null) return;
        db.execSQL("UPDATE tasks SET status='stopped',issue=?,generation=generation+1,updated=? WHERE id=?", new Object[]{reason, System.currentTimeMillis(), id});
        event(id, reason);
        advance();
    }
    private void advance() throws Exception {
        if (activeId() != 0 || !scalar("SELECT data FROM settings WHERE name='platform_block'").isEmpty()) return;
        String next = scalar("SELECT id FROM tasks WHERE status='queued' ORDER BY id LIMIT 1");
        if (!next.isEmpty()) {
            long id = Long.parseLong(next);
            JSONObject queued = status(id);
            String state = "profile".equals(queued.optString("kind")) ? "running-profiles" : queued.optBoolean("rootComplete") ? "running-replies" : "running";
            db.execSQL("UPDATE tasks SET status=?,updated=? WHERE id=?", new Object[]{state, System.currentTimeMillis(), id});
            event(id, "queue_started");
        }
    }
    private void finishProfiles(long id) throws Exception {
        if (id == 0) return;
        if (count("SELECT count(*) FROM profile_targets WHERE task=? AND status IN ('pending','running')", id) > 0) return;
        boolean failed = count("SELECT count(*) FROM profile_targets WHERE task=? AND status='failed'", id) > 0;
        JSONObject task = status(id);
        String st = task.optString("status");
        if ("running-profiles".equals(st) || "profile".equals(task.optString("kind"))) {
            db.execSQL("UPDATE tasks SET status=?,issue=?,updated=? WHERE id=?", new Object[]{failed ? "paused" : "complete", failed ? "profiles_incomplete" : null, System.currentTimeMillis(), id});
            if (!failed) advance();
        }
    }
    private void mergeRecord(String table, String id, JSONObject incoming) throws Exception {
        String previous = scalar("SELECT data FROM " + table + " WHERE id=?", id);
        JSONObject merged = previous.isEmpty() ? new JSONObject() : new JSONObject(previous);
        java.util.Iterator<String> keys = incoming.keys();
        while (keys.hasNext()) { String key = keys.next(); Object value = incoming.opt(key);
            if (incoming.has("profileCapturedAt") && Arrays.asList("gender", "age", "bio", "location", "profileIpRegion", "latestPostLocation", "latestPostCity", "latestPostAt").contains(key)) { merged.put(key, value); continue; }
            if ("lastCommentAt".equals(key) && merged.optLong(key) > incoming.optLong(key)) continue;
            if (value != null && value != JSONObject.NULL && !(value instanceof String && ((String) value).isEmpty())) merged.put(key, value);
        }
        if ("users".equals(table) && !merged.has("gender")) merged.put("gender", "unknown");
        db.execSQL("INSERT OR REPLACE INTO " + table + " VALUES (?,?,?)", new Object[]{id, merged.toString(), System.currentTimeMillis()});
    }
    private JSONObject comments(JSONObject query) throws Exception { return comments(query, null, 0); }
    private JSONObject comments(JSONObject query, java.util.function.Consumer<JSONObject> sink, int maxRows) throws Exception {
        List<String> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("c.task=(SELECT max(d.task) FROM comments d WHERE d.id=c.id)");
        if (query.has("taskId")) { where = new StringBuilder("c.task=?"); args.add(String.valueOf(query.getLong("taskId"))); }
        platformClause(where, args, query, "c.data");
        match(where, args, query, "photoId", "json_extract(c.data,'$.photoId')", false);
        match(where, args, query, "userId", "json_extract(c.data,'$.userId')", false);
        // Keyword and region combine with AND (default) or OR, like the desktop "条件关系".
        StringBuilder regionClause = new StringBuilder(); List<String> regionArgs = new ArrayList<>();
        RegionFilter.append(regionClause, regionArgs, query, "ipRegion", "ipRegions", ipExpression(query.optString("ipSource"), "json_extract(c.data,'$.ipRegion')", "u"));
        StringBuilder keywordClause = new StringBuilder(); List<String> keywordArgs = new ArrayList<>();
        keywordTerms(keywordClause, keywordArgs, query.optString("keyword"), "json_extract(c.data,'$.text')");
        if ("or".equals(query.optString("relation")) && regionClause.length() > 0 && keywordClause.length() > 0) {
            where.append(" AND (").append(regionClause.substring(5)).append(" OR ").append(keywordClause.substring(5)).append(')');
            args.addAll(regionArgs); args.addAll(keywordArgs);
        } else { where.append(regionClause).append(keywordClause); args.addAll(regionArgs); args.addAll(keywordArgs); }
        for (String term : terms(query.optString("excludeKeyword"))) {
            where.append(" AND coalesce(json_extract(c.data,'$.text'),'') NOT LIKE ? ESCAPE '\\'"); args.add("%" + like(term) + "%");
        }
        if (query.optInt("recentDays") > 0) {
            // created may be seconds or milliseconds depending on the source.
            where.append(" AND (CASE WHEN CAST(json_extract(c.data,'$.created') AS INTEGER) < 100000000000 THEN CAST(json_extract(c.data,'$.created') AS INTEGER)*1000 ELSE CAST(json_extract(c.data,'$.created') AS INTEGER) END) >= ?");
            args.add(String.valueOf(System.currentTimeMillis() - query.optInt("recentDays") * 86400000L));
        }
        String search = query.optString("search").trim();
        if (!search.isEmpty()) {
            where.append(" AND (coalesce(json_extract(c.data,'$.text'),'') LIKE ? ESCAPE '\\' OR coalesce(json_extract(c.data,'$.nickname'),'') LIKE ? ESCAPE '\\' OR json_extract(c.data,'$.userId')=?)");
            args.add("%" + like(search) + "%"); args.add("%" + like(search) + "%"); args.add(search);
        }
        String gender = query.optString("gender", "all");
        if (gender != null && !gender.isEmpty() && !"all".equals(gender)) {
            List<String> gArgs = new ArrayList<>();
            StringBuilder gSb = new StringBuilder();
            genderClause(gSb, gArgs, "coalesce(json_extract(u.data,'$.gender'),'unknown')", gender);
            if (gSb.length() > 0) {
                where.append(" AND (").append(gSb.substring(5)).append(" OR json_extract(u.data,'$.profileCapturedAt') IS NULL)");
                args.addAll(gArgs);
            }
        }
        for (String key : Arrays.asList("minAge", "maxAge")) if (query.has(key) && !query.isNull(key)) {
            where.append(" AND (CAST(json_extract(u.data,'$.age') AS INTEGER)").append("minAge".equals(key) ? ">=?" : "<=?").append(" OR json_extract(u.data,'$.profileCapturedAt') IS NULL)");
            args.add(String.valueOf(query.getInt(key)));
        }
        String order = switch (query.optString("sort", "captured_desc")) {
            case "created_asc" -> "CAST(json_extract(c.data,'$.created') AS INTEGER) ASC";
            case "created_desc" -> "CAST(json_extract(c.data,'$.created') AS INTEGER) DESC";
            case "likes_desc" -> "CAST(json_extract(c.data,'$.likes') AS INTEGER) DESC";
            case "captured_asc" -> "CAST(json_extract(c.data,'$.capturedAt') AS INTEGER) ASC";
            case "captured_desc" -> "CAST(json_extract(c.data,'$.capturedAt') AS INTEGER) DESC";
            default -> throw new IllegalArgumentException("排序条件无效");
        };
        int limit = Math.max(1, Math.min(200, query.optInt("limit", 50))), offset = Math.max(0, query.optInt("offset"));
        String from = " FROM comments c LEFT JOIN users u ON u.id=json_extract(c.data,'$.userId') WHERE " + where;
        if (sink != null) {
            // Streaming mode (analysis): one pass, no ORDER BY / OFFSET / count, capped at maxRows.
            int read = 0; boolean more = false;
            try (Cursor cursor = db.rawQuery("SELECT c.data,u.data" + from, args.toArray(new String[0]))) {
                while (cursor.moveToNext()) {
                    if (read >= maxRows) { more = true; break; }
                    JSONObject row = new JSONObject(cursor.getString(0));
                    JSONObject user = cursor.isNull(1) ? new JSONObject() : new JSONObject(cursor.getString(1));
                    row.put("user", user);
                    boolean profileCaptured = user.has("profileCapturedAt");
                    row.put("profilePending", !profileCaptured);
                    if (profileCaptured) {
                        row.put("gender", user.optString("gender", "unknown"));
                        row.put("profileIpRegion", user.optString("profileIpRegion", ""));
                    } else {
                        row.put("gender", "pending");
                        row.put("profileIpRegion", "");
                    }
                    if (row.optString("avatarUrl").isEmpty() && !user.optString("avatarUrl").isEmpty()) row.put("avatarUrl", user.optString("avatarUrl"));
                    if (row.optString("nickname").isEmpty() && !user.optString("nickname").isEmpty()) row.put("nickname", user.optString("nickname"));
                    sink.accept(row); read++;
                }
            }
            return new JSONObject().put("read", read).put("more", more);
        }
        int total = Integer.parseInt(scalar("SELECT count(*)" + from, args.toArray(new String[0])));
        int pending = Integer.parseInt(scalar("SELECT count(*)" + from + " AND json_extract(u.data,'$.profileCapturedAt') IS NULL", args.toArray(new String[0])));
        JSONArray items = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT c.data,c.task,u.data" + from + " ORDER BY " + order + ",c.id LIMIT " + limit + " OFFSET " + offset, args.toArray(new String[0]))) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject(cursor.getString(0)).put("taskId", cursor.getLong(1));
                JSONObject user = cursor.isNull(2) ? new JSONObject() : new JSONObject(cursor.getString(2));
                row.put("user", user);
                boolean profileCaptured = user.has("profileCapturedAt");
                row.put("profilePending", !profileCaptured);
                if (profileCaptured) {
                    row.put("gender", user.optString("gender", "unknown"));
                    row.put("profileIpRegion", user.optString("profileIpRegion", ""));
                } else {
                    row.put("gender", "pending");
                    row.put("profileIpRegion", "");
                }
                if (row.optString("avatarUrl").isEmpty() && !user.optString("avatarUrl").isEmpty()) row.put("avatarUrl", user.optString("avatarUrl"));
                if (row.optString("nickname").isEmpty() && !user.optString("nickname").isEmpty()) row.put("nickname", user.optString("nickname"));
                items.put(row);
            }
        }
        return new JSONObject().put("items", items).put("total", total).put("matched", total - pending).put("pending", pending).put("limit", limit).put("offset", offset);
    }
    /** Splits "a, b；c" style input into terms. */
    private static List<String> terms(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String term : value.split("[,，、;；|]+")) { term = term.trim(); if (!term.isEmpty() && out.size() < 20) out.add(term.length() > 50 ? term.substring(0, 50) : term); }
        return out;
    }
    private static String like(String value) { return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }
    /** Keyword terms match if any term appears (desktop behaviour). Appends " AND (...)" or nothing. */
    private static void keywordTerms(StringBuilder where, List<String> args, String value, String expression) {
        List<String> list = terms(value); if (list.isEmpty()) return;
        where.append(" AND (");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) where.append(" OR ");
            where.append("coalesce(").append(expression).append(",'') LIKE ? ESCAPE '\\'"); args.add("%" + like(list.get(i)) + "%");
        }
        where.append(')');
    }
    private void match(StringBuilder where, List<String> args, JSONObject query, String key, String expression, boolean partial) {
        String value = query.optString(key).trim(); if (value.isEmpty()) return;
        where.append(" AND ").append(expression).append(partial ? " LIKE ?" : "=?"); args.add(partial ? "%" + value + "%" : value);
    }
    private JSONObject users(JSONObject query) throws Exception {
        List<String> args = new ArrayList<>(); StringBuilder where = new StringBuilder("1=1");
        platformClause(where, args, query, "data");
        match(where, args, query, "keyword", "json_extract(data,'$.nickname')", true);
        RegionFilter.append(where, args, query, "ipRegion", "ipRegions", ipExpression(query.optString("ipSource"), "json_extract(data,'$.lastCommentIpRegion')", ""));
        match(where, args, query, "profileIpRegion", "json_extract(data,'$.profileIpRegion')", true);
        String userSearch = query.optString("search").trim();
        if (!userSearch.isEmpty()) { where.append(" AND (coalesce(json_extract(data,'$.nickname'),'') LIKE ? ESCAPE '\\' OR id=?)"); args.add("%" + like(userSearch) + "%"); args.add(userSearch); }
        genderClause(where, args, "coalesce(json_extract(data,'$.gender'),'unknown')", query.optString("gender", "all"));
        for (String key : Arrays.asList("minAge", "maxAge")) if (query.has(key)) {
            where.append(" AND CAST(json_extract(data,'$.age') AS INTEGER)").append("minAge".equals(key) ? ">=?" : "<=?"); args.add(String.valueOf(query.getInt(key)));
        }
        int limit = Math.max(1, Math.min(200, query.optInt("limit", 50))), offset = Math.max(0, query.optInt("offset"));
        int total = Integer.parseInt(scalar("SELECT count(*) FROM users WHERE " + where, args.toArray(new String[0])));
        String order = query.optString("sort", "captured_desc").contains("created") ? "CAST(json_extract(data,'$.lastCommentAt') AS INTEGER)" : "updated";
        order += query.optString("sort").endsWith("asc") ? " ASC" : " DESC";
        JSONArray items = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT data FROM users WHERE " + where + " ORDER BY " + order + ",id LIMIT " + limit + " OFFSET " + offset, args.toArray(new String[0]))) {
            while (cursor.moveToNext()) items.put(new JSONObject(cursor.getString(0)));
        }
        JSONObject base = new JSONObject().put("items", items).put("total", total).put("limit", limit).put("offset", offset);
        for (int i = 0; i < items.length(); i++) {
            JSONObject user = items.getJSONObject(i);
            String id = user.optString("userId");
            user.put("commentCount", Integer.parseInt(scalar("SELECT count(DISTINCT id) FROM comments WHERE json_extract(data,'$.userId')=?", id)));
            user.put("postCount", Integer.parseInt(scalar("SELECT count(DISTINCT json_extract(data,'$.photoId')) FROM comments WHERE json_extract(data,'$.userId')=?", id)));
        }
        return base;
    }
    private JSONObject records(String table, JSONObject query) throws Exception {
        int limit = Math.max(1, Math.min(200, query.optInt("limit", 50))), offset = Math.max(0, query.optInt("offset"));
        List<String> args = new ArrayList<>(); StringBuilder where = new StringBuilder("1=1");
        if (query.has("taskId")) {
            where.append(" AND EXISTS (SELECT 1 FROM discovered d WHERE d.task=? AND d.post=posts.id)");
            args.add(String.valueOf(query.getLong("taskId")));
        }
        platformClause(where, args, query, "data");
        match(where, args, query, "keyword", "json_extract(data,'$.title')", true);
        match(where, args, query, "searchKeyword", "json_extract(data,'$.searchKeyword')", false);
        String postSearch = query.optString("search").trim();
        if (!postSearch.isEmpty()) { where.append(" AND (coalesce(json_extract(data,'$.title'),'') LIKE ? ESCAPE '\\' OR coalesce(json_extract(data,'$.authorName'),'') LIKE ? ESCAPE '\\' OR id=?)");
            args.add("%" + like(postSearch) + "%"); args.add("%" + like(postSearch) + "%"); args.add(postSearch); }
        String order = switch (query.optString("sort", "captured_desc")) {
            case "created_asc" -> "CAST(json_extract(data,'$.created') AS INTEGER) ASC";
            case "created_desc" -> "CAST(json_extract(data,'$.created') AS INTEGER) DESC";
            case "likes_desc" -> "CAST(json_extract(data,'$.likes') AS INTEGER) DESC";
            case "captured_asc" -> "updated ASC";
            case "captured_desc" -> "updated DESC";
            default -> throw new IllegalArgumentException("排序条件无效");
        };
        JSONArray items = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT data FROM " + table + " WHERE " + where + " ORDER BY " + order + ",id LIMIT " + limit + " OFFSET " + offset, args.toArray(new String[0]))) {
            while (cursor.moveToNext()) items.put(new JSONObject(cursor.getString(0)));
        }
        return new JSONObject().put("items", items).put("total", Integer.parseInt(scalar("SELECT count(*) FROM " + table + " WHERE " + where, args.toArray(new String[0]))))
            .put("limit", limit).put("offset", offset);
    }
    private JSONObject cloudSnapshot() throws Exception {
        JSONArray posts = new JSONArray(), comments = new JSONArray(), users = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT id,data FROM posts ORDER BY updated DESC", null)) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0); JSONObject row = new JSONObject(cursor.getString(1));
                row.put("id", "kuaishou:post:" + id).put("url", "https://www.kuaishou.com/short-video/" + id)
                    .put("source", "android-lsposed"); posts.put(row);
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT c.data FROM comments c WHERE c.task=(SELECT max(d.task) FROM comments d WHERE d.id=c.id) ORDER BY c.id", null)) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject(cursor.getString(0)); String id = row.optString("commentId", row.optString("id"));
                if (id.isEmpty()) continue; row.put("id", "kuaishou:comment:" + id).put("source", "android-lsposed"); comments.put(row);
            }
        }
        try (Cursor cursor = db.rawQuery("SELECT id,data FROM users ORDER BY updated DESC", null)) {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0); JSONObject row = new JSONObject(cursor.getString(1));
                row.put("profile", "kwai://profile/" + id).put("source", "android-lsposed"); users.put(row);
            }
        }
        return new JSONObject().put("schemaVersion", 1).put("platform", "kuaishou").put("posts", posts)
            .put("comments", comments).put("users", users).put("analysisScopes", new JSONArray());
    }
    private void markGroup(long id, String root) throws Exception {
        ContentValues values = new ContentValues(); values.put("task", id); values.put("root", root);
        db.insertWithOnConflict("groups_eof", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        int pendingReplies = count("SELECT count(*) FROM comments c WHERE c.task=? AND c.root='' AND c.replies>0 AND NOT EXISTS (SELECT 1 FROM groups_eof g WHERE g.task=c.task AND g.root=c.id)", id);
        if (pendingReplies == 0) {
            int pendingProfiles = count("SELECT count(*) FROM profile_targets WHERE task=? AND status IN ('pending','running')", id);
            String nextState = pendingProfiles > 0 ? "running-profiles" : "complete";
            String nextIssue = "running-profiles".equals(nextState) ? "采集资料中" : null;
            db.execSQL("UPDATE tasks SET status=?,issue=?,updated=? WHERE id=? AND status='running-replies'", new Object[]{nextState, nextIssue, System.currentTimeMillis(), id});
            if ("complete".equals(nextState)) advance();
        }
    }
    private void writeStatus(JSONObject status) throws Exception {
        AtomicFile file = new AtomicFile(new File(getContext().getFilesDir(), "status.json"));
        FileOutputStream stream = file.startWrite();
        try { stream.write(status.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); }
        catch (Exception error) { file.failWrite(stream); throw error; }
    }
    private void export(long id) throws Exception {
        JSONArray rows = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT data FROM comments WHERE task=? ORDER BY id", new String[]{String.valueOf(id)})) {
            while (cursor.moveToNext()) rows.put(new JSONObject(cursor.getString(0)));
        }
        JSONArray events = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT time,code FROM events WHERE task=? ORDER BY time", new String[]{String.valueOf(id)})) {
            while (cursor.moveToNext()) events.put(new JSONObject().put("time", cursor.getLong(0)).put("code", cursor.getString(1)));
        }
        AtomicFile file = new AtomicFile(new File(getContext().getFilesDir(), "export.json"));
        FileOutputStream stream = file.startWrite();
        try {
            stream.write(new JSONObject().put("schemaVersion", 1).put("task", status(id)).put("comments", rows).put("events", events).toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (Exception error) { file.failWrite(stream); throw error; }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return "application/json"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }

    /** Gender filter: "all", a single key, the legacy "female-or-unknown", or a comma list such as "female,unknown". */
    static void genderClause(StringBuilder where, List<String> args, String expression, String gender) {
        if (gender == null || gender.isEmpty() || "all".equals(gender)) return;
        if ("female-or-unknown".equals(gender)) gender = "female,unknown";
        List<String> keys = new java.util.ArrayList<>();
        for (String key : gender.split(",")) {
            key = key.trim();
            if (!Arrays.asList("male", "female", "unknown").contains(key)) throw new IllegalArgumentException("性别条件无效");
            if (!keys.contains(key)) keys.add(key);
        }
        if (keys.size() >= 3) return; // all three selected means no restriction
        where.append(" AND ").append(expression).append(" IN (");
        for (int i = 0; i < keys.size(); i++) { where.append(i == 0 ? "?" : ",?"); args.add(keys.get(i)); }
        where.append(")");
    }

    /**
     * Which IP a region filter looks at: "profile" (the profile page, also the default), "comment" (shown with the
     * comment) or "post" (where the user's latest post was published, city-level on Douyin). A missing value falls back
     * to the profile IP first; the profile IP itself falls back to the comment IP only when the profile has none.
     */
    static String ipExpression(String source, String commentExpression, String userAlias) {
        String prefix = userAlias.isEmpty() ? "" : userAlias + ".";
        String profile = "nullif(json_extract(" + prefix + "data,'$.profileIpRegion'),'')";
        String post = "nullif(json_extract(" + prefix + "data,'$.latestPostLocation'),'')";
        String comment = "nullif(" + commentExpression + ",'')";
        return switch (source == null ? "" : source) {
            case "comment" -> "coalesce(" + comment + "," + profile + ")";
            case "post" -> "coalesce(" + post + "," + profile + "," + comment + ")";
            default -> "coalesce(" + profile + "," + comment + ")";
        };
    }

    /** Restricts a query to one platform ("all" or empty means every platform). Rows saved before platforms existed are Kuaishou. */
    static void platformClause(StringBuilder where, List<String> args, JSONObject query, String dataColumn) {
        String platform = query.optString("platform");
        if (platform.isEmpty() || "all".equals(platform)) return;
        where.append(" AND coalesce(json_extract(").append(dataColumn).append(",'$.platform'),'kuaishou')=?"); args.add(platform);
    }
}
