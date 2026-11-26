package ru.kansktc.week;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class ScheduleStore {
    static final String BASE = "https://pro.kansk-tc.ru";
    static final TimeZone ZONE = TimeZone.getTimeZone("Asia/Krasnoyarsk");
    static final Object SYNC_LOCK = new Object();

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
    }

    static String date(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy.MM.dd", Locale.ROOT);
        format.setTimeZone(ZONE);
        return format.format(date);
    }

    static String weekKey(String group, Date date) {
        Calendar monday = Calendar.getInstance(ZONE);
        monday.setTime(date);
        monday.add(Calendar.DAY_OF_YEAR, -((monday.get(Calendar.DAY_OF_WEEK) + 5) % 7));
        return group + ":" + date(monday.getTime());
    }

    static JSONArray cached(Context context, String key) {
        try {
            String raw = context.getSharedPreferences("schedule", Context.MODE_PRIVATE).getString(key, null);
            return raw == null ? null : new JSONArray(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    static JSONArray sync(Context context, String group, Date date, boolean force) throws Exception {
        String key = weekKey(group, date);
        synchronized (SYNC_LOCK) {
            SharedPreferences cache = context.getSharedPreferences("schedule", Context.MODE_PRIVATE);
            JSONArray previous = cached(context, key);
            long elapsed = System.currentTimeMillis() - cache.getLong(key + ":time", 0);
            long interval = settings(context).getInt("syncMinutes", 5) * 60_000L;
            if (!force && previous != null && elapsed >= 0 && elapsed < interval) return previous;
            JSONArray latest = request("getGroupTimetable", "group_id=" + group
                + "&date=" + date(date) + "&mode=week");
            for (int i = 0; i < latest.length(); i++) {
                JSONObject day = latest.optJSONObject(i);
                if (day == null || day.optJSONArray("lessons") == null) throw new IOException("Invalid timetable");
            }
            cache.edit().putString(key, latest.toString()).putLong(key + ":time", System.currentTimeMillis()).apply();
            if (group.equals(settings(context).getString("groupId", ""))
                    && key.equals(weekKey(group, new Date()))) {
                LessonAlerts.sync(context, group, previous, latest);
            }
            return latest;
        }
    }

    static JSONArray request(String method, String form) throws Exception {
        URL url = new URL(BASE + "/procollege/core_procollege/helpers/rest/" + method);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream body = connection.getOutputStream()) {
                body.write(form.getBytes(StandardCharsets.UTF_8));
            }
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            try (Reader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                StringBuilder json = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) json.append(buffer, 0, count);
                return new JSONArray(json.toString());
            }
        } finally {
            connection.disconnect();
        }
    }
}
