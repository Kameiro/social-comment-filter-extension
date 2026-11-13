package dev.local.commentcollector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Helpers for the "后台运行检查" card in the collector app: system settings intents and evidence wording. */
final class BackgroundCheck {
    static final String SECTION = "background_check";
    private static final String OPLUS_BATTERY = "com.oplus.battery";
    private BackgroundCheck() {}

    static boolean batteryIgnored(Context context) {
        PowerManager power = context.getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static boolean installed(Context context, String pkg) {
        try { context.getPackageManager().getPackageInfo(pkg, 0); return true; } catch (Throwable error) { return false; }
    }

    private static Intent appDetails(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
    }
    /** The ColorOS screen when it resolves on this device, else comso's own app-details page. */
    private static Intent component(Context context, String pkg, String cls) {
        Intent intent = new Intent().setClassName(pkg, cls);
        try { if (context.getPackageManager().resolveActivity(intent, 0) != null) return intent; } catch (Throwable ignored) {}
        return appDetails(context);
    }
    static Intent startupIntent(Context context) { return component(context, OPLUS_BATTERY, "com.oplus.startupapp.view.StartupAppListActivity"); }
    static Intent powerIntent(Context context) { return component(context, OPLUS_BATTERY, "com.oplus.powermanager.fuelgaue.PowerControlActivity"); }
    static Intent ignoreBatteryIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.getPackageName()));
        try { if (context.getPackageManager().resolveActivity(intent, 0) != null) return intent; } catch (Throwable ignored) {}
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }
    static void open(Activity activity, Intent intent) {
        try { activity.startActivity(intent); }
        catch (Throwable error) {
            try { activity.startActivity(appDetails(activity)); } catch (Throwable ignored) {}
        }
    }

    static String time(long millis) { return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(millis)); }

    static String contactLine(JSONObject state, String pkg) {
        long at = state == null || state.optJSONObject("contacts") == null ? 0 : state.optJSONObject("contacts").optLong(pkg);
        if (at <= 0) return "从未连接";
        long minutes = Math.max(0, (state.optLong("now", System.currentTimeMillis()) - at) / 60000L);
        return minutes < 1 ? "最近连接：刚刚" : minutes < 120 ? "最近连接：" + minutes + " 分钟前" : "最近连接：" + (minutes / 60) + " 小时前";
    }

    /** Latest outage report, or "" when there is none. */
    static String reportLine(JSONObject state) {
        JSONArray reports = state == null ? null : state.optJSONArray("reports");
        if (reports == null || reports.length() == 0) return "";
        JSONObject last = reports.optJSONObject(reports.length() - 1);
        if (last == null) return "";
        String host = Platforms.label(Platforms.name(last.optString("hostPackage")));
        return host + "在 " + time(last.optLong("outageStart")) + "–" + time(last.optLong("outageEnd")) + " 连不上采集服务 "
            + last.optInt("failures") + " 次，通常是自启动或关联启动被系统拦截";
    }
}
