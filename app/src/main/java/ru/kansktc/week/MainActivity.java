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
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
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
    private LinearLayout gradePanel;
    private String logoutUrl = "";
    private String courseTitle = "";
    private String journalUrl = GRADES;
    private boolean openLoginAfterLogout;
    private boolean logoutPending;
    private boolean webVisible;
    private boolean dayOnly;
    private Button dayMode, weekMode;
    private LinearLayout weekNav;
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
        dayOnly = getPreferences(MODE_PRIVATE).getBoolean("dayOnly", false);
        if (state != null && !dayOnly) date.setTimeInMillis(state.getLong("date"));
        root = column();
        root.setBackgroundColor(dark ? 0xff101827 : 0xffedf2fb);
        LinearLayout header = column();
        header.setPadding(dp(20), dp(17), dp(20), dp(12));
        header.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            dark ? new int[]{0xff172846, 0xff101e33} : new int[]{0xff344f86, 0xff1b3054}));
        header.setElevation(dp(5));
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
        LinearLayout period = new LinearLayout(this);
        dayMode = button(period, "Сегодня", "Показать пары на сегодня", () -> selectPeriod(true));
        weekMode = button(period, "Неделя", "Показать пары на неделю", () -> selectPeriod(false));
        dayMode.setSelected(dayOnly);
        weekMode.setSelected(!dayOnly);
        scheduleControls.addView(period);
        LinearLayout nav = new LinearLayout(this);
        button(nav, "‹", "Предыдущая неделя", () -> move(-1));
        button(nav, "Сегодня", "Текущая неделя", () -> { date.setTimeInMillis(System.currentTimeMillis()); load(); });
        button(nav, "›", "Следующая неделя", () -> move(1));
        scheduleControls.addView(nav);
        nav.setVisibility(dayOnly ? View.GONE : View.VISIBLE);
        weekNav = nav;
        LinearLayout actions = new LinearLayout(this);
        button(actions, "Группа", "Выбрать группу", this::chooseGroup);
        button(actions, "Обновить", "Обновить расписание", this::load);
        scheduleControls.addView(actions);
        root.addView(header);
        scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        days = column();
        days.setClipChildren(false);
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
        if (showGrades && grades == null) createGradeViews();
        if (gradePanel != null) gradePanel.setVisibility(showGrades && !webVisible ? View.VISIBLE : View.GONE);
        if (grades != null) grades.setVisibility(showGrades && webVisible ? View.VISIBLE : View.GONE);
        if (!showGrades) refreshHighlights();
    }

    private void createGradeViews() {
        gradePanel = column();
        root.addView(gradePanel, new LinearLayout.LayoutParams(-1, 0, 1));
        grades = new WebView(this);
        if (android.os.Build.VERSION.SDK_INT >= 26) grades.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
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
                return !isCollegeUrl(request.getUrl());
            }

            @Override public void onPageFinished(WebView view, String url) {
                inspectGradePage(view, url);
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                   android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) gradeMessage("Не удалось открыть журнал", "Повторить",
                    () -> loadJournal(journalUrl));
            }
        });
        root.addView(grades, new LinearLayout.LayoutParams(-1, 0, 1));
        loadJournal(GRADES);
    }

    private boolean isCollegeUrl(Uri url) {
        return "https".equals(url.getScheme()) && "pro.kansk-tc.ru".equalsIgnoreCase(url.getHost())
            && (url.getPort() == -1 || url.getPort() == 443);
    }

    private void gradeMessage(String message, String action, Runnable callback) {
        setWebVisible(false);
        gradePanel.removeAllViews();
        gradePanel.setGravity(Gravity.CENTER);
        TextView title = label(message, 18, ink, true);
        title.setGravity(Gravity.CENTER);
        gradePanel.addView(title);
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(action);
        styleButton(button);
        button.setOnClickListener(v -> callback.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = dp(12);
        gradePanel.addView(button, params);
    }

    private void setWebVisible(boolean visible) {
        webVisible = visible;
        grades.setVisibility(gradesVisible && visible ? View.VISIBLE : View.GONE);
        gradePanel.setVisibility(gradesVisible && !visible ? View.VISIBLE : View.GONE);
    }

    private void loadJournal(String url) {
        journalUrl = url;
        gradeMessage("Загрузка оценок…", "Обновить", () -> grades.reload());
        grades.loadUrl(url);
    }

    private void openLogin() {
        setWebVisible(true);
        grades.loadUrl(BASE + "/login/index.php");
    }

    private void inspectGradePage(WebView view, String address) {
        Uri url = Uri.parse(address);
        if (!isCollegeUrl(url) || !address.equals(view.getUrl())) return;
        String path = url.getPath();
        if (path == null || path.startsWith("/mod/")) return;
        if (path.equals("/login/logout.php")) return;
        String read = "(function(){"
            + "let login=!!document.querySelector('form input[type=password]');"
            + "let logout=document.querySelector('a[href*=\"/login/logout.php\"]');"
            + "let overview=document.querySelector('#overview-grade');"
            + "let detail=document.querySelector('table.user-grade');"
            + "let rows=[];"
            + "if(overview) for(let row of overview.querySelectorAll('tbody tr')){"
            + "let cells=row.cells,a=cells[0]&&cells[0].querySelector('a');"
            + "if(a&&cells.length>=2)rows.push({name:a.innerText,grade:cells[1].innerText,url:a.href});}"
            + "if(detail) for(let row of detail.querySelectorAll('tbody tr')){"
            + "let item=row.querySelector('.column-itemname.item'),g=row.querySelector('.column-grade');"
            + "if(item&&g){let a=item.querySelector('a[href*=\"/mod/\"]');"
            + "rows.push({name:item.innerText,grade:g.innerText,url:a?a.href:''});}}"
            + "return {login:login,logout:logout?logout.href:'',kind:overview?'overview':detail?'detail':'',rows:rows};"
            + "})()";
        view.evaluateJavascript(read, value -> {
            if (isDestroyed() || !address.equals(view.getUrl()) || value == null || value.equals("null")) return;
            try {
                JSONObject page = new JSONObject(value);
                String logout = page.optString("logout");
                if (!logout.isEmpty() && isCollegeUrl(Uri.parse(logout))) logoutUrl = logout;
                String kind = page.optString("kind");
                if (logoutPending && logout.isEmpty()) {
                    logoutPending = false;
                    if (openLoginAfterLogout) openLogin();
                    else gradeMessage("Войди, чтобы увидеть оценки", "Выполнить вход", this::openLogin);
                    openLoginAfterLogout = false;
                } else if (page.optBoolean("login")) {
                    if (!webVisible) gradeMessage("Войди, чтобы увидеть оценки", "Выполнить вход", this::openLogin);
                } else if (!kind.isEmpty()) {
                    renderGrades(page.getJSONArray("rows"), kind.equals("detail"));
                } else if (webVisible && !logout.isEmpty()) {
                    loadJournal(GRADES);
                } else if (!webVisible && !logout.isEmpty()) {
                    gradeMessage("Журнал пока недоступен", "Повторить", () -> loadJournal(GRADES));
                } else if (!webVisible) {
                    gradeMessage("Войди, чтобы увидеть оценки", "Выполнить вход", this::openLogin);
                }
            } catch (JSONException error) {
                if (!webVisible) gradeMessage("Не удалось прочитать журнал", "Повторить", () -> loadJournal(GRADES));
            }
        });
    }

    private void renderGrades(JSONArray rows, boolean detail) {
        setWebVisible(false);
        gradePanel.removeAllViews();
        gradePanel.setGravity(Gravity.NO_GRAVITY);
        gradePanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout actions = new LinearLayout(this);
        if (detail) button(actions, "Все предметы", "Вернуться к предметам", () -> loadJournal(GRADES));
        button(actions, "Сменить", "Сменить аккаунт", () -> signOut(true));
        button(actions, "Выйти", "Выйти из аккаунта", () -> signOut(false));
        gradePanel.addView(actions);
        ScrollView gradeScroll = new ScrollView(this);
        LinearLayout table = column();
        table.setPadding(dp(3), dp(8), dp(3), dp(12));
        if (detail) table.addView(label(courseTitle, 20, ink, true));
        table.addView(gradeRow(detail ? "Задание" : "Предмет", "Оценка", true));
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name").trim();
            String url = item.optString("url");
            LinearLayout row = gradeRow(name, item.optString("grade").trim(), false);
            if (isCollegeUrl(Uri.parse(url))) {
                row.setOnClickListener(v -> {
                    if (detail) {
                        setWebVisible(true);
                        grades.loadUrl(url);
                    } else {
                        courseTitle = name;
                        loadJournal(url);
                    }
                });
            }
            table.addView(row);
        }
        if (rows.length() == 0) table.addView(label("Оценок пока нет", 16, muted, false));
        gradeScroll.addView(table);
        gradePanel.addView(gradeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private LinearLayout gradeRow(String name, String grade, boolean heading) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(heading ? (dark ? 0xff273855 : 0xffdbe8ff) : surface);
        background.setCornerRadius(dp(12));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x337698dc), background, null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(6);
        row.setLayoutParams(params);
        row.addView(label(name, 15, ink, heading), new LinearLayout.LayoutParams(0, -2, 1));
        TextView score = label(grade.isEmpty() ? "—" : grade, 15, accent, true);
        score.setGravity(Gravity.END);
        score.setPadding(dp(8), 0, 0, 0);
        row.addView(score, new LinearLayout.LayoutParams(dp(85), -2));
        return row;
    }

    private void signOut(boolean switchAccount) {
        String target = logoutUrl;
        logoutUrl = "";
        courseTitle = "";
        journalUrl = GRADES;
        openLoginAfterLogout = switchAccount;
        gradeMessage("Завершение сеанса…", "Повторить", () -> signOut(switchAccount));
        if (isCollegeUrl(Uri.parse(target))) {
            logoutPending = true;
            grades.loadUrl(target);
        } else {
            CookieManager.getInstance().removeAllCookies(removed -> {
                if (isDestroyed()) return;
                logoutPending = false;
                openLoginAfterLogout = false;
                if (switchAccount) openLogin();
                else gradeMessage("Войди, чтобы увидеть оценки", "Выполнить вход", this::openLogin);
            });
        }
    }

    @Override public void onBackPressed() {
        if (gradesVisible && webVisible) loadJournal(journalUrl);
        else if (gradesVisible && !journalUrl.equals(GRADES)) loadJournal(GRADES);
        else if (gradesVisible) showTab(false);
        else super.onBackPressed();
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

    private void selectPeriod(boolean today) {
        dayOnly = today;
        getPreferences(MODE_PRIVATE).edit().putBoolean("dayOnly", today).apply();
        dayMode.setSelected(today);
        weekMode.setSelected(!today);
        weekNav.setVisibility(today ? View.GONE : View.VISIBLE);
        date.setTimeInMillis(System.currentTimeMillis());
        load();
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
        if (dayOnly) date.setTimeInMillis(System.currentTimeMillis());
        int request = ++generation;
        int slideDirection = direction;
        direction = 0;
        Calendar monday = (Calendar) date.clone();
        monday.add(Calendar.DAY_OF_YEAR, -((monday.get(Calendar.DAY_OF_WEEK) + 5) % 7));
        String start = format("d MMM", monday.getTime());
        monday.add(Calendar.DAY_OF_YEAR, 6);
        String range = start + " – " + format("d MMM yyyy", monday.getTime());
        week.setText(groupName + "  ·  " + (dayOnly ? format("d MMMM", date.getTime()) : range));
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
            if (dayOnly && !today.equals(dayDate)) continue;
            LinearLayout panel = column();
            panel.setClipChildren(false);
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
        if (days.getChildCount() == 0) days.addView(label(dayOnly ? "Сегодня пар нет" : "На этой неделе расписания нет", 18, ink, true));
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
        background.setColors(new int[]{dark ? 0xff223149 : Color.WHITE, surface});
        background.setStroke(dp(1), dark ? 0xff34455f : 0xffdce7f5);
        card.setElevation(dp(4));
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
            lesson.background.setColors(active ? new int[]{dark ? 0xff2a4760 : 0xffeffdf8, dark ? 0xff233d52 : 0xffe0f3ed}
                : new int[]{dark ? 0xff223149 : Color.WHITE, surface});
            lesson.background.setStroke(dp(1), active ? (dark ? 0xff6dbfa6 : 0xff377e6a)
                : (dark ? 0xff34455f : 0xffdce7f5));
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

    private Button button(LinearLayout row, String title, String description, Runnable action) {
        Button view = new Button(this);
        view.setText(title);
        view.setContentDescription(description);
        view.setAllCaps(false);
        styleButton(view);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(3), dp(4), dp(3), dp(4));
        row.addView(view, params);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void styleButton(Button view) {
        view.setTextColor(Color.WHITE);
        view.setBackgroundTintList(null);
        view.setMinHeight(dp(40));
        view.setMinimumHeight(dp(40));
        view.setPadding(dp(7), dp(7), dp(7), dp(7));
        view.setElevation(dp(2));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_selected}, glass(0xff5779b6, 0xff304e83));
        states.addState(new int[0], glass(0xe0476088, 0xe0263a60));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55ffffff), states, null));
    }

    private GradientDrawable glass(int top, int bottom) {
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{top, bottom});
        shape.setCornerRadius(dp(13));
        shape.setStroke(dp(1), 0x60c5daff);
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
