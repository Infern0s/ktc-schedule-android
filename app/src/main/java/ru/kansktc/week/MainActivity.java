package ru.kansktc.week;

import android.app.Activity;
import android.app.AlertDialog;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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
    private static final String GRADES = BASE + "/grade/report/overview/index.php";
    private LinearLayout days, scheduleControls, root;
    private WebView grades;
    private boolean gradesVisible;
    private ScrollView scroll;
    private final List<LessonHighlight> lessonHighlights = new ArrayList<>();
    private final List<TextView> dayHeadings = new ArrayList<>();
    private String displayedToday;
    private final Runnable clockUpdate = new Runnable() {
        @Override public void run() {
            refreshHighlights();
            scroll.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000);
        }
    };

    private static final class LessonHighlight {
        final View card;
        final GradientDrawable background;
        final String date;
        final int start, end;
        final TextView time;
        final String label;
        boolean active;

        LessonHighlight(View card, GradientDrawable background, String date, int start,
                        int end, TextView time, String label) {
            this.card = card;
            this.background = background;
            this.date = date;
            this.start = start;
            this.end = end;
            this.time = time;
            this.label = label;
        }
    }
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
        root = column();
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
        LinearLayout tabs = new LinearLayout(this);
        button(tabs, "Расписание", "Расписание", () -> showTab(false));
        button(tabs, "Оценки", "Оценки и вход в ПроКолледж", () -> showTab(true));
        header.addView(tabs);
        scheduleControls = column();
        header.addView(scheduleControls);
        scheduleControls.addView(week);
        LinearLayout nav = new LinearLayout(this);
        button(nav, "‹", "Предыдущая неделя", () -> move(-1));
        button(nav, "Сегодня", "Текущая неделя", () -> { date.setTimeInMillis(System.currentTimeMillis()); load(); });
        button(nav, "›", "Следующая неделя", () -> move(1));
        scheduleControls.addView(nav);
        LinearLayout actions = new LinearLayout(this);
        button(actions, "Группа", "Выбрать группу", this::chooseGroup);
        button(actions, "Обновить", "Обновить расписание", this::load);
        scheduleControls.addView(actions);
        root.addView(header);
        scroll = new ScrollView(this);
        days = column();
        days.setPadding(dp(16), dp(12), dp(16), dp(24));
        scroll.addView(days);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        groupId = getPreferences(MODE_PRIVATE).getString("groupId", "");
        groupName = getPreferences(MODE_PRIVATE).getString("groupName", "");
        load();
        if (state != null && state.getBoolean("gradesVisible")) showTab(true);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("date", date.getTimeInMillis());
        state.putBoolean("gradesVisible", gradesVisible);
        super.onSaveInstanceState(state);
    }

    private void showTab(boolean showGrades) {
        gradesVisible = showGrades;
        scheduleControls.setVisibility(showGrades ? View.GONE : View.VISIBLE);
        scroll.setVisibility(showGrades ? View.GONE : View.VISIBLE);
        if (showGrades && grades == null) {
            grades = new WebView(this);
            grades.getSettings().setJavaScriptEnabled(true);
            grades.getSettings().setDomStorageEnabled(true);
            grades.getSettings().setAllowFileAccess(false);
            grades.getSettings().setAllowContentAccess(false);
            grades.getSettings().setUseWideViewPort(true);
            grades.getSettings().setLoadWithOverviewMode(true);
            grades.getSettings().setBuiltInZoomControls(true);
            grades.getSettings().setDisplayZoomControls(false);
            CookieManager.getInstance().setAcceptThirdPartyCookies(grades, false);
            grades.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    Uri url = request.getUrl();
                    return !"https".equals(url.getScheme())
                        || !"pro.kansk-tc.ru".equalsIgnoreCase(url.getHost())
                        || (url.getPort() != -1 && url.getPort() != 443);
                }
            });
            root.addView(grades, new LinearLayout.LayoutParams(-1, 0, 1));
            grades.loadUrl(GRADES);
        }
        if (grades != null) grades.setVisibility(showGrades ? View.VISIBLE : View.GONE);
        if (!showGrades) refreshHighlights();
    }

    @Override public void onBackPressed() {
        if (gradesVisible) {
            if (grades.canGoBack()) grades.goBack();
            else showTab(false);
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        clockUpdate.run();
    }

    @Override protected void onPause() {
        scroll.removeCallbacks(clockUpdate);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (grades != null) {
            root.removeView(grades);
            grades.destroy();
        }
        super.onDestroy();
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
                        .setCancelable(true)
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
            week.setText("Группа не выбрана");
            days.removeAllViews();
            days.addView(label("Нажми «Группа», чтобы открыть расписание.", 16, muted, false));
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
        lessonHighlights.clear();
        dayHeadings.clear();
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
        lessonHighlights.clear();
        dayHeadings.clear();
        LinearLayout todayPanel = null;
        days.removeAllViews();
        String today = format("yyyy.MM.dd", new Date());
        for (int i = 0; i < data.length(); i++) {
            JSONObject day = data.optJSONObject(i);
            if (day == null) continue;
            String dayDate = day.optString("date");
            LinearLayout panel = column();
            panel.setPadding(0, dp(4), 0, dp(12));
            TextView heading = label(day.optString("dayName") + "  ·  " + dayDate, 18, accent, true);
            heading.setTag(dayDate);
            heading.setPadding(dp(10), dp(5), dp(10), dp(5));
            dayHeadings.add(heading);
            panel.addView(heading);
            if (today.equals(dayDate)) todayPanel = panel;
            JSONArray slots = day.optJSONArray("lessons");
            int count = addLessons(panel, slots, dayDate);
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
        displayedToday = null;
        refreshHighlights();
        if (todayPanel != null && !gradesVisible) {
            View destination = todayPanel;
            scroll.post(() -> {
                if (destination.getParent() == days && !gradesVisible) {
                    scroll.smoothScrollTo(0, destination.getTop() - dp(8));
                }
            });
        }
    }

    private int addLessons(LinearLayout panel, JSONArray slots, String dayDate) {
        int count = 0;
        for (int i = 0; slots != null && i < slots.length(); i++) {
            JSONArray variants = slots.optJSONArray(i);
            if (variants == null) continue;
            List<JSONObject> active = new ArrayList<>();
            List<JSONObject> cancelled = new ArrayList<>();
            Set<String> originalSubjects = new LinkedHashSet<>();
            for (int j = 0; j < variants.length(); j++) {
                JSONObject item = variants.optJSONObject(j);
                if (item == null || item.isNull("discipline") || item.optString("discipline").isEmpty()) continue;
                if (item.optInt("cancel") == 1) {
                    cancelled.add(item);
                    originalSubjects.add(item.optString("discipline"));
                } else {
                    active.add(item);
                }
            }
            for (JSONObject item : active) {
                int weekNumber = item.optInt("week", -1);
                boolean replacement = !cancelled.isEmpty() && (active.size() == 1
                    || (weekNumber > 0 && weekNumber == item.optInt("study_w")));
                addCard(panel, item, dayDate, replacement ? android.text.TextUtils.join(" / ", originalSubjects) : null);
                count++;
            }
            if (active.isEmpty()) {
                for (JSONObject item : cancelled) {
                    addCard(panel, item, dayDate, null);
                    count++;
                }
            }
        }
        return count;
    }

    private void addCard(LinearLayout panel, JSONObject item, String dayDate, String original) {
        LinearLayout card = column();
        card.setPadding(dp(15), dp(12), dp(15), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(surface);
        background.setCornerRadius(dp(16));
        card.setBackground(background);
        String time = item.optString("lesson") + "-я пара  ·  "
            + item.optString("lesson_start") + "–" + item.optString("lesson_end");
        TextView timeLabel = label(time, 13, accent, true);
        card.addView(timeLabel);
        if (item.optInt("cancel") != 1) {
            lessonHighlights.add(new LessonHighlight(card, background, dayDate,
                minutes(item.optString("lesson_start")), minutes(item.optString("lesson_end")),
                timeLabel, time));
        }
        if (original != null) addStatus(card, "Замена", true);
        else if (item.optInt("cancel") == 1) addStatus(card, "Отмена", false);
        card.addView(label(item.optString("discipline"), 17, ink, true));
        String teacher = item.optString("lastname", "");
        String room = item.optString("classroom", "");
        if (!room.isEmpty()) teacher += (teacher.isEmpty() ? "" : "  ·  ") + "Ауд. " + room;
        if (!teacher.isEmpty()) card.addView(label(teacher, 14, muted, false));
        if (original != null) {
            TextView previous = label("Было: " + original, 13, muted, false);
            previous.setPadding(0, dp(7), 0, 0);
            card.addView(previous);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(8);
        panel.addView(card, params);
    }

    private int minutes(String value) {
        String[] parts = value.split(":");
        if (parts.length < 2) return -1;
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return hour >= 0 && hour < 24 && minute >= 0 && minute < 60 ? hour * 60 + minute : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private void refreshHighlights() {
        Date now = new Date();
        String today = format("yyyy.MM.dd", now);
        if (!today.equals(displayedToday)) {
            String previous = displayedToday;
            displayedToday = today;
            if (previous != null && previous.equals(format("yyyy.MM.dd", date.getTime()))) {
                date.setTime(now);
                load();
                return;
            }
            for (TextView heading : dayHeadings) {
                boolean current = today.equals(heading.getTag());
                String text = heading.getText().toString().replace("  ·  Сегодня", "");
                heading.setText(current ? text + "  ·  Сегодня" : text);
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(12));
                pill.setColor(current ? (dark ? 0xff243856 : 0xffdbe8ff) : Color.TRANSPARENT);
                heading.setBackground(pill);
            }
        }
        Calendar time = Calendar.getInstance(zone);
        time.setTime(now);
        int minute = time.get(Calendar.HOUR_OF_DAY) * 60 + time.get(Calendar.MINUTE);
        for (LessonHighlight lesson : lessonHighlights) {
            boolean active = today.equals(lesson.date) && lesson.start >= 0
                && minute >= lesson.start && minute < lesson.end;
            if (active == lesson.active) continue;
            lesson.active = active;
            lesson.background.setColor(active ? (dark ? 0xff233d52 : 0xffe0f3ed) : surface);
            lesson.background.setStroke(active ? dp(1) : 0, dark ? 0xff6dbfa6 : 0xff377e6a);
            lesson.time.setText(active ? lesson.label + "  ·  Идёт сейчас" : lesson.label);
            float scale = active ? 1.025f : 1f;
            lesson.card.animate().scaleX(scale).scaleY(scale).setDuration(240)
                .setInterpolator(ENTER).start();
        }
    }

    private void addStatus(LinearLayout card, String status, boolean replacement) {
        int foreground = replacement ? (dark ? 0xffffd18a : 0xff704300)
            : (dark ? 0xffffb3b3 : 0xff972929);
        int fill = replacement ? (dark ? 0xff49351a : 0xffffeccb)
            : (dark ? 0xff51292f : 0xffffe3e3);
        TextView badge = label(status, 12, foreground, true);
        badge.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(14));
        badge.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMargins(0, dp(6), 0, dp(5));
        card.addView(badge, params);
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
