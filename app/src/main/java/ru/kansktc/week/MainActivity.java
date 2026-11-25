package ru.kansktc.week;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class MainActivity extends Activity {
    private static final String BASE = "https://pro.kansk-tc.ru";
    private String groupId;
    private String groupName;
    private boolean choosingGroup;
    private static final PathInterpolator ENTER = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private boolean dark;
    private int ink, muted, accent, surface;
    private int direction;
    private final TimeZone zone = TimeZone.getTimeZone("Asia/Krasnoyarsk");
    private final Calendar date = Calendar.getInstance(zone);
    private LinearLayout days;
    private ScrollView scroll;
    private TextView week;
    private int generation;

    @Override public void onCreate(Bundle state) {
        int nightMode = getResources().getConfiguration().uiMode & 0x30;
        dark = getPreferences(MODE_PRIVATE).getBoolean("darkTheme", nightMode == 0x20);
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
            : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        ink = dark ? 0xffedf3ff : 0xff162238;
        muted = dark ? 0xffaabbd4 : 0xff53627b;
        accent = dark ? 0xffa9c5ff : 0xff2556c4;
        surface = dark ? 0xff1a273d : Color.WHITE;
        getWindow().setStatusBarColor(0xff152647);
        getWindow().setNavigationBarColor(0xff152647);
        if (state != null) date.setTimeInMillis(state.getLong("date"));
        LinearLayout root = column();
        root.setBackgroundColor(dark ? 0xff101827 : 0xffedf2fb);
        LinearLayout header = column();
        header.setPadding(dp(20), dp(17), dp(20), dp(12));
        header.setBackgroundColor(0xff152647);
        LinearLayout title = new LinearLayout(this);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView heading = label("Расписание пар", 27, Color.WHITE, true);
        title.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button theme = new Button(this);
        theme.setAllCaps(false);
        theme.setText(dark ? "Светлая" : "Тёмная");
        theme.setContentDescription(dark ? "Включить светлую тему" : "Включить тёмную тему");
        styleButton(theme);
        theme.setOnClickListener(v -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("darkTheme", !dark).apply();
            recreate();
        });
        title.addView(theme);
        header.addView(title);
        week = label("", 15, 0xffc5d5f2, false);
        header.addView(week);
        LinearLayout nav = new LinearLayout(this);
        button(nav, "‹", "Предыдущая неделя", () -> move(-1));
        button(nav, "Сегодня", "Текущая неделя", () -> { date.setTimeInMillis(System.currentTimeMillis()); load(); });
        button(nav, "›", "Следующая неделя", () -> move(1));
        header.addView(nav);
        LinearLayout actions = new LinearLayout(this);
        button(actions, "Группа", "Выбрать группу", this::chooseGroup);
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
        groupId = getPreferences(MODE_PRIVATE).getString("groupId", "");
        groupName = getPreferences(MODE_PRIVATE).getString("groupName", "");
        if (groupId.isEmpty()) chooseGroup();
        else load();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("date", date.getTimeInMillis());
        super.onSaveInstanceState(state);
    }

    private void move(int direction) {
        this.direction = direction;
        date.add(Calendar.DAY_OF_YEAR, direction * 7);
        load();
    }

    private String format(String pattern, Date value) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, new Locale("ru", "RU"));
        format.setTimeZone(zone);
        return format.format(value);
    }

    private void chooseGroup() {
        if (choosingGroup) return;
        choosingGroup = true;
        if (groupId.isEmpty()) week.setText("Выбери свою группу");
        ProgressBar loading = new ProgressBar(this);
        AlertDialog lookup = new AlertDialog.Builder(this)
            .setTitle("Загружаю группы")
            .setView(loading)
            .setCancelable(false)
            .show();
        new Thread(() -> {
            try {
                JSONArray groups = fetch("getGroups", "");
                List<JSONObject> entries = new ArrayList<>();
                for (int i = 0; i < groups.length(); i++) {
                    JSONObject group = groups.optJSONObject(i);
                    if (group != null && group.optString("id").matches("[0-9]+")
                            && !group.optString("name").isEmpty()) entries.add(group);
                }
                if (entries.isEmpty()) throw new IOException("Group list is empty");
                Collections.sort(entries, (first, second) -> first.optString("name").compareTo(second.optString("name")));
                CharSequence[] names = new CharSequence[entries.size()];
                for (int i = 0; i < entries.size(); i++) names[i] = entries.get(i).optString("name");
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    lookup.dismiss();
                    AlertDialog picker = new AlertDialog.Builder(this)
                        .setTitle("Выбери группу")
                        .setItems(names, (dialog, position) -> {
                            JSONObject group = entries.get(position);
                            groupId = group.optString("id");
                            groupName = group.optString("name");
                            getPreferences(MODE_PRIVATE).edit()
                                .putString("groupId", groupId)
                                .putString("groupName", groupName)
                                .apply();
                            load();
                        })
                        .setCancelable(!groupId.isEmpty())
                        .create();
                    picker.setOnDismissListener(dialog -> choosingGroup = false);
                    picker.show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    lookup.dismiss();
                    choosingGroup = false;
                    new AlertDialog.Builder(this)
                        .setMessage("Не удалось загрузить список групп. Проверь интернет.")
                        .setPositiveButton("Повторить", (dialog, which) -> chooseGroup())
                        .setNegativeButton("Закрыть", null)
                        .show();
                });
            }
        }).start();
    }

    private void load() {
        if (groupId.isEmpty()) {
            chooseGroup();
            return;
        }
        int request = ++generation;
        int slideDirection = direction;
        direction = 0;
        Calendar monday = (Calendar) date.clone();
        monday.add(Calendar.DAY_OF_YEAR, -((monday.get(Calendar.DAY_OF_WEEK) + 5) % 7));
        String start = format("d MMM", monday.getTime());
        monday.add(Calendar.DAY_OF_YEAR, 6);
        String range = start + " – " + format("d MMM yyyy", monday.getTime());
        week.setText(groupName + "  ·  " + range);
        days.removeAllViews();
        scroll.scrollTo(0, 0);
        days.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(36), dp(36)));
        String queryDate = format("yyyy.MM.dd", date.getTime());
        String query = "group_id=" + groupId + "&date=" + queryDate + "&mode=week";
        new Thread(() -> {
            try {
                JSONArray data = fetch("getGroupTimetable", query);
                runOnUiThread(() -> {
                    if (request == generation && !isDestroyed()) show(data, slideDirection);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (request != generation || isDestroyed()) return;
                    days.removeAllViews();
                    days.addView(label("Не удалось загрузить расписание", 18, ink, true));
                    days.addView(label("Проверь интернет и нажми «Обновить».", 15, muted, false));
                });
            }
        }).start();
    }

    private JSONArray fetch(String method, String form) throws Exception {
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
                body.write(form.getBytes("UTF-8"));
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

    private void show(JSONArray data, int slideDirection) {
        days.removeAllViews();
        String today = format("yyyy.MM.dd", new Date());
        for (int i = 0; i < data.length(); i++) {
            JSONObject day = data.optJSONObject(i);
            if (day == null) continue;
            String dayDate = day.optString("date");
            LinearLayout panel = column();
            panel.setPadding(0, dp(4), 0, dp(12));
            panel.addView(label(day.optString("dayName") + "  ·  " + dayDate
                + (today.equals(dayDate) ? "  ·  Сегодня" : ""), 18, accent, true));
            JSONArray slots = day.optJSONArray("lessons");
            int count = addLessons(panel, slots);
            if (count == 0) panel.addView(label("Пар нет", 15, muted, false));
            days.addView(panel);
            panel.setAlpha(0);
            panel.setTranslationY(dp(10));
            panel.setTranslationX(dp(20) * slideDirection);
            panel.animate().alpha(1).translationX(0).translationY(0)
                .setInterpolator(ENTER).setStartDelay(Math.min(i * 28L, 112L))
                .setDuration(380).start();
        }
        if (days.getChildCount() == 0) days.addView(label("На этой неделе расписания нет", 18, ink, true));
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
        background.setColor(surface);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        String time = item.optString("lesson") + "-я пара  ·  "
            + item.optString("lesson_start") + "–" + item.optString("lesson_end");
        String cancellation = item.optString("cancel", "");
        if (!cancellation.isEmpty() && !cancellation.equals("0") && !cancellation.equals("false")) time += "  ·  Отмена";
        card.addView(label(time, 13, accent, true));
        card.addView(label(item.optString("discipline"), 17, ink, true));
        String teacher = item.optString("lastname", "");
        String room = item.optString("classroom", "");
        if (!room.isEmpty()) teacher += (teacher.isEmpty() ? "" : "  ·  ") + "Ауд. " + room;
        if (!teacher.isEmpty()) card.addView(label(teacher, 14, muted, false));
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
        styleButton(view);
        row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
        view.setOnClickListener(v -> action.run());
    }

    private void styleButton(Button view) {
        view.setTextColor(Color.WHITE);
        view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            dark ? 0xff263b5e : 0xff294674));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
