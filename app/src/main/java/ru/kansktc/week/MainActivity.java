package ru.kansktc.week;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final String BASE = "https://pro.kansk-tc.ru";
    private static final String GROUP = "312";
    private static final int INK = 0xff162238, MUTED = 0xff53627b, BLUE = 0xff2556c4;
    private final TimeZone zone = TimeZone.getTimeZone("Asia/Krasnoyarsk");
    private final Calendar date = Calendar.getInstance(zone);
    private LinearLayout days;
    private ScrollView scroll;
    private TextView week;
    private int generation;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff152647);
        getWindow().setNavigationBarColor(0xff152647);
        if (state != null) date.setTimeInMillis(state.getLong("date"));
        LinearLayout root = column();
        root.setBackgroundColor(0xffedf2fb);
        LinearLayout header = column();
        header.setPadding(dp(20), dp(17), dp(20), dp(12));
        header.setBackgroundColor(0xff152647);
        header.addView(label("Расписание пар", 27, 0xffffffff, true));
        week = label("", 15, 0xffc5d5f2, false);
        header.addView(week);
        LinearLayout nav = new LinearLayout(this);
        button(nav, "‹", "Предыдущая неделя", () -> move(-1));
        button(nav, "Сегодня", "Текущая неделя", () -> { date.setTimeInMillis(System.currentTimeMillis()); load(); });
        button(nav, "›", "Следующая неделя", () -> move(1));
        header.addView(nav);
        LinearLayout actions = new LinearLayout(this);
        button(actions, "Обновить", "Обновить расписание", this::load);
        button(actions, "Кабинет", "Войти в ПроКолледж", () ->
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(BASE + "/my/"))));
        header.addView(actions);
        root.addView(header);
        scroll = new ScrollView(this);
        days = column();
        days.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(days);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        load();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("date", date.getTimeInMillis());
        super.onSaveInstanceState(state);
    }

    private void move(int direction) {
        date.add(Calendar.DAY_OF_YEAR, direction * 7);
        load();
    }

    private String format(String pattern, Date value) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, new Locale("ru", "RU"));
        format.setTimeZone(zone);
        return format.format(value);
    }

    private void load() {
        int request = ++generation;
        Calendar monday = (Calendar) date.clone();
        monday.add(Calendar.DAY_OF_YEAR, -((monday.get(Calendar.DAY_OF_WEEK) + 5) % 7));
        String start = format("d MMM", monday.getTime());
        monday.add(Calendar.DAY_OF_YEAR, 6);
        String range = start + " – " + format("d MMM yyyy", monday.getTime());
        week.setText(range);
        days.removeAllViews();
        scroll.scrollTo(0, 0);
        days.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(36), dp(36)));
        String queryDate = format("yyyy.MM.dd", date.getTime());
        new Thread(() -> {
            try {
                JSONArray data = fetch(queryDate);
                runOnUiThread(() -> {
                    if (request == generation && !isDestroyed()) show(data, range);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (request != generation || isDestroyed()) return;
                    days.removeAllViews();
                    days.addView(label("Не удалось загрузить расписание", 18, INK, true));
                    days.addView(label("Проверь интернет и нажми «Обновить».", 15, MUTED, false));
                });
            }
        }).start();
    }

    private JSONArray fetch(String queryDate) throws Exception {
        URL url = new URL(BASE + "/procollege/core_procollege/helpers/rest/getGroupTimetable");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream body = connection.getOutputStream()) {
                body.write(("group_id=" + GROUP + "&date=" + queryDate + "&mode=week").getBytes("UTF-8"));
            }
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            try (Reader reader = new InputStreamReader(connection.getInputStream(), "UTF-8")) {
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

    private void show(JSONArray data, String range) {
        days.removeAllViews();
        String today = format("yyyy.MM.dd", new Date());
        for (int i = 0; i < data.length(); i++) {
            JSONObject day = data.optJSONObject(i);
            if (day == null) continue;
            if (i == 0) week.setText(day.optString("gname", "Группа " + GROUP) + "  ·  " + range);
            String dayDate = day.optString("date");
            LinearLayout panel = column();
            panel.setPadding(0, dp(4), 0, dp(12));
            panel.addView(label(day.optString("dayName") + "  ·  " + dayDate
                + (today.equals(dayDate) ? "  ·  Сегодня" : ""), 18, BLUE, true));
            JSONArray slots = day.optJSONArray("lessons");
            int count = addLessons(panel, slots);
            if (count == 0) panel.addView(label("Пар нет", 15, MUTED, false));
            days.addView(panel);
            panel.setAlpha(0);
            panel.setTranslationY(dp(16));
            panel.animate().alpha(1).translationY(0).setStartDelay(i * 45L).setDuration(240).start();
        }
        if (days.getChildCount() == 0) days.addView(label("На этой неделе расписания нет", 18, INK, true));
    }

    private int addLessons(LinearLayout panel, JSONArray slots) {
        int count = 0;
        for (int i = 0; slots != null && i < slots.length(); i++) {
            JSONArray variants = slots.optJSONArray(i);
            for (int j = 0; variants != null && j < variants.length(); j++) {
                JSONObject item = variants.optJSONObject(j);
                if (item == null || item.isNull("discipline") || item.optString("discipline").isEmpty()) continue;
                addCard(panel, item);
                count++;
            }
        }
        return count;
    }

    private void addCard(LinearLayout panel, JSONObject item) {
        LinearLayout card = column();
        card.setPadding(dp(15), dp(12), dp(15), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        String time = item.optString("lesson") + "-я пара  ·  "
            + item.optString("lesson_start") + "–" + item.optString("lesson_end");
        String cancellation = item.optString("cancel", "");
        if (!cancellation.isEmpty() && !cancellation.equals("0") && !cancellation.equals("false")) time += "  ·  Отмена";
        card.addView(label(time, 13, BLUE, true));
        card.addView(label(item.optString("discipline"), 17, INK, true));
        String teacher = item.optString("lastname", "");
        String room = item.optString("classroom", "");
        if (!room.isEmpty()) teacher += (teacher.isEmpty() ? "" : "  ·  ") + "Ауд. " + room;
        if (!teacher.isEmpty()) card.addView(label(teacher, 14, MUTED, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        panel.addView(card, params);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void button(LinearLayout row, String title, String description, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setContentDescription(description);
        view.setAllCaps(false);
        view.setTextColor(0xffffffff);
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff294674));
        row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
        view.setOnClickListener(v -> action.run());
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

