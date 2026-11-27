package ru.kansktc.week;

import android.app.Activity;
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
    private View auxiliaryPage;
    private Button backButton;
    private boolean groupsLoading;
    private List<JSONObject> groupCatalog;
    private LinearLayout groupResults;
    private EditText groupSearch;
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
    private boolean foreground;
    private boolean checkingUpdate, downloadingUpdate, installPermissionPending;
    private AppUpdates.Release availableUpdate;
    private boolean exactSettingsOpened;
    private boolean syncInFlight;
    private TextView screenTitle;
    private String displayedSchedule;
    private final Runnable periodicSync = new Runnable() {
        @Override public void run() {
            if (foreground && !groupId.isEmpty()) loadSchedule(false, true);
            queueSync();
        }
    };

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
        backButton = new Button(this);
        backButton.setText("‹");
        backButton.setTextSize(27);
        backButton.setContentDescription("Вернуться к расписанию");
        styleButton(backButton);
        backButton.setOnClickListener(v -> showTab(false));
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(42), dp(46));
        backParams.rightMargin = dp(12);
        title.addView(backButton, backParams);
        backButton.setVisibility(View.GONE);
        screenTitle = label("Расписание пар", 27, Color.WHITE, true);
        title.addView(screenTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = new Button(this);
        menu.setText("☰");
        menu.setTextSize(21);
        menu.setContentDescription("Открыть меню");
        styleButton(menu);
        menu.setOnClickListener(v -> openMenu());
        title.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(46)));
        header.addView(title);
        week = label("", 15, 0xffc5d5f2, false);
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
        LessonAlerts.scheduleSync(this);
        LessonAlerts.refreshReminders(this);
        if (!groupId.isEmpty()) requestInitialNotifications();
        if (state != null && state.getBoolean("gradesVisible")) showTab(true);
        installPermissionPending = state != null && state.getBoolean("installPermissionPending");
        if (state == null) checkForUpdates(false);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putLong("date", date.getTimeInMillis());
        state.putBoolean("gradesVisible", gradesVisible);
        state.putBoolean("installPermissionPending", installPermissionPending);
        super.onSaveInstanceState(state);
    }

    private void checkForUpdates(boolean manual) {
        if (checkingUpdate || downloadingUpdate) {
            if (manual) Toast.makeText(this, "Обновление уже проверяется или скачивается", Toast.LENGTH_SHORT).show();
            return;
        }
        if (manual && availableUpdate != null) {
            offerUpdate(availableUpdate);
            return;
        }
        checkingUpdate = true;
        new Thread(() -> {
            try {
                AppUpdates.Release release = AppUpdates.check(getApplicationContext());
                runOnUiThread(() -> {
                    checkingUpdate = false;
                    if (isDestroyed() || isFinishing()) return;
                    availableUpdate = release;
                    if (release == null) {
                        if (manual) Toast.makeText(this, "Установлена последняя версия", Toast.LENGTH_SHORT).show();
                    } else if (foreground && (manual || !release.version.equals(
                            getPreferences(MODE_PRIVATE).getString("deferredUpdate", "")))) {
                        offerUpdate(release);
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    checkingUpdate = false;
                    if (manual && !isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "Не удалось проверить GitHub. Попробуй позже.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void offerUpdate(AppUpdates.Release release) {
        new android.app.AlertDialog.Builder(this).setTitle("Доступна версия " + release.version)
            .setMessage("Скачать обновление из GitHub и открыть установщик Android?")
            .setNegativeButton("Позже", (dialog, which) -> getPreferences(MODE_PRIVATE).edit()
                .putString("deferredUpdate", release.version).apply())
            .setPositiveButton("Обновить", (dialog, which) -> downloadUpdate(release))
            .show();
    }

    private void downloadUpdate(AppUpdates.Release release) {
        if (downloadingUpdate) return;
        downloadingUpdate = true;
        Toast.makeText(this, "Скачиваю версию " + release.version + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                AppUpdates.download(getApplicationContext(), release);
                runOnUiThread(() -> {
                    downloadingUpdate = false;
                    if (!isDestroyed() && !isFinishing()) openDownloadedUpdate(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    downloadingUpdate = false;
                    if (!isDestroyed() && !isFinishing()) {
                        Toast.makeText(this, "Не удалось скачать или проверить APK", Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void openDownloadedUpdate(boolean askPermission) {
        if (!AppUpdates.apk(this).isFile()) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                if (askPermission) {
                    new android.app.AlertDialog.Builder(this).setTitle("Разрешение на установку")
                        .setMessage("Android попросит разрешить «Парам КТК» открыть скачанное обновление.")
                        .setNegativeButton("Позже", null).setPositiveButton("Открыть настройки", (dialog, which) -> {
                            installPermissionPending = true;
                            try {
                                startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                            } catch (android.content.ActivityNotFoundException unavailable) {
                                installPermissionPending = false;
                                Toast.makeText(this, "Настройка установки недоступна", Toast.LENGTH_LONG).show();
                            }
                        }).show();
                }
                return;
            }
            android.content.Intent install = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            install.setDataAndType(UpdateProvider.uri(this), UpdateProvider.MIME);
            install.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, "Не удалось открыть установщик Android", Toast.LENGTH_LONG).show();
        }
    }

    private void openMenu() {
        LinearLayout body = column();
        class AnimatedMenu extends android.app.Dialog {
            private boolean closing;

            AnimatedMenu() {
                super(MainActivity.this);
            }

            @Override public void cancel() {
                close(null);
            }

            void close(Runnable action) {
                if (closing) return;
                closing = true;
                for (int i = 0; i < body.getChildCount(); i++) {
                    View item = body.getChildAt(i);
                    item.setEnabled(false);
                    item.animate().cancel();
                }
                body.animate().cancel();
                body.animate().alpha(0f).scaleX(0.96f).scaleY(0.88f).translationY(-dp(6))
                    .setStartDelay(0).setDuration(170)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(() -> {
                        dismiss();
                        if (action != null && !isFinishing() && !isDestroyed()) action.run();
                    }).start();
            }
        }
        AnimatedMenu menu = new AnimatedMenu();
        body.setPadding(dp(16), dp(10), dp(16), dp(12));
        body.setBackground(glass(0xff344f75, 0xff192940));
        String updateLabel = availableUpdate == null ? "Обновление приложения" : "Обновить до " + availableUpdate.version;
        String[] labels = {"Расписание", "Оценки", "Группа", "Обновить расписание", updateLabel,
            dark ? "Светлая тема" : "Тёмная тема", "Настройки"};
        Runnable[] actions = {() -> showTab(false), () -> showTab(true), this::chooseGroup, () -> {
            if (auxiliaryPage != null) showTab(false);
            if (gradesVisible) loadJournal(journalUrl);
            else loadSchedule(true, true);
        }, () -> checkForUpdates(true), () -> {
            getPreferences(MODE_PRIVATE).edit().putBoolean("darkTheme", !dark).apply();
            recreate();
        }, this::showSettings};
        for (int i = 0; i < labels.length; i++) {
            Button item = new Button(this);
            item.setAllCaps(false);
            item.setText(labels[i]);
            styleButton(item);
            item.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            item.setPadding(dp(16), 0, dp(16), 0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(6);
            body.addView(item, params);
            Runnable action = actions[i];
            item.setOnClickListener(view -> menu.close(action));
        }
        body.setAlpha(0f);
        body.setScaleX(0.96f);
        body.setScaleY(0.78f);
        body.setTranslationY(-dp(8));
        for (int i = 0; i < body.getChildCount(); i++) {
            View item = body.getChildAt(i);
            item.setAlpha(0f);
            item.setTranslationY(-dp(8));
            item.setScaleY(0.91f);
        }
        menu.setContentView(body);
        menu.setCanceledOnTouchOutside(true);
        android.view.Window window = menu.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setWindowAnimations(0);
        window.setGravity(Gravity.TOP | Gravity.END);
        android.view.WindowManager.LayoutParams position = window.getAttributes();
        position.x = dp(12);
        position.y = dp(12);
        window.setAttributes(position);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0.35f);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            root.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(12), dp(12),
                android.graphics.Shader.TileMode.CLAMP));
            menu.setOnDismissListener(dialog -> root.setRenderEffect(null));
        }
        menu.show();
        window.setLayout(dp(235), -2);
        body.post(() -> {
            if (!menu.isShowing() || menu.closing) return;
            body.setPivotX(body.getWidth());
            body.setPivotY(0);
            body.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0)
                .setDuration(290).setInterpolator(ENTER).start();
            for (int i = 0; i < body.getChildCount(); i++) {
                View item = body.getChildAt(i);
                item.animate().alpha(1f).translationY(0).scaleY(1f)
                    .setStartDelay(30 + i * 30L).setDuration(230).setInterpolator(ENTER).start();
            }
        });
    }

    private void showSettings() {
        android.content.SharedPreferences settings = getPreferences(MODE_PRIVATE);
        LinearLayout page = column();
        page.setPadding(dp(16), dp(12), dp(16), dp(8));
        ScrollView content = new ScrollView(this);
        LinearLayout options = column();
        content.addView(options);
        page.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        showAuxiliary("Настройки", page);

        LinearLayout alerts = settingsPanel(options, "Уведомления");
        String[] keys = {"alertBefore", "alertCancelled", "alertAdded", "alertChanged"};
        String[] names = {"Перед началом пары", "Об отмене", "О новой паре", "О замене и изменениях"};
        Switch[] toggles = new Switch[keys.length];
        ColorStateList thumb = new ColorStateList(new int[][]{
            {android.R.attr.state_checked}, {}}, new int[]{accent, muted});
        ColorStateList track = new ColorStateList(new int[][]{
            {android.R.attr.state_checked}, {}}, new int[]{0xff446798, 0xff687485});
        for (int i = 0; i < keys.length; i++) {
            Switch toggle = new Switch(this);
            toggle.setText(names[i]);
            toggle.setTextSize(15);
            toggle.setTextColor(ink);
            toggle.setThumbTintList(thumb);
            toggle.setTrackTintList(track);
            toggle.setMinHeight(dp(46));
            toggle.setChecked(settings.getBoolean(keys[i], true));
            alerts.addView(toggle);
            toggles[i] = toggle;
        }
        int[] lead = {settings.getInt("leadMinutes", 5)};
        minuteOptions(alerts, "Напоминать до начала", new int[]{1, 3, 5, 10, 15, 20, 30, 60}, lead);

        LinearLayout updating = settingsPanel(options, "Обновление расписания");
        int[] sync = {settings.getInt("syncMinutes", 5)};
        minuteOptions(updating, "Интервал проверки", new int[]{5, 10, 15, 30, 60}, sync);
        TextView hint = label("В фоне Android проверяет расписание каждые 15 минут или реже.", 13, muted, false);
        hint.setPadding(0, dp(5), 0, 0);
        updating.addView(hint);

        if (android.os.Build.VERSION.SDK_INT >= 31
                && !getSystemService(android.app.AlarmManager.class).canScheduleExactAlarms()) {
            LinearLayout exact = new LinearLayout(this);
            button(exact, "Разрешить точные напоминания", "Разрешить точные напоминания", () -> {
                exactSettingsOpened = true;
                startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
            });
            options.addView(exact);
        }

        LinearLayout actions = new LinearLayout(this);
        button(actions, "Отмена", "Вернуться без сохранения", () -> showTab(false));
        Button save = button(actions, "Сохранить", "Сохранить настройки", () -> {
            android.content.SharedPreferences.Editor edit = settings.edit();
            boolean anyEnabled = false;
            for (int i = 0; i < keys.length; i++) {
                edit.putBoolean(keys[i], toggles[i].isChecked());
                anyEnabled |= toggles[i].isChecked();
            }
            edit.putInt("leadMinutes", lead[0]).putInt("syncMinutes", sync[0]).apply();
            LessonAlerts.scheduleSync(this);
            LessonAlerts.refreshReminders(this);
            queueSync();
            showTab(false);
            if (anyEnabled) requestNotificationPermission();
        });
        save.setSelected(true);
        page.addView(actions);
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            TextView versionLabel = label("Версия " + version, 11, muted, false);
            versionLabel.setGravity(Gravity.CENTER);
            versionLabel.setPadding(0, dp(2), 0, dp(8));
            page.addView(versionLabel, new LinearLayout.LayoutParams(-1, -2));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // This footer is optional if package information is unavailable.
        }
    }

    private LinearLayout settingsPanel(LinearLayout parent, String title) {
        LinearLayout section = column();
        section.setBackground(panelShape(false));
        section.setPadding(dp(14), dp(14), dp(14), dp(12));
        section.addView(label(title, 19, ink, true));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(12);
        parent.addView(section, params);
        return section;
    }

    private void minuteOptions(LinearLayout section, String title, int[] values, int[] selected) {
        TextView caption = label(title, 14, muted, true);
        caption.setPadding(0, dp(9), 0, 0);
        section.addView(caption);
        List<Button> choices = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < values.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(this);
                section.addView(row);
            }
            int minutes = values[i];
            Button choice = button(row, minutes + " мин", title + " " + minutes + " минут", () -> {
                selected[0] = minutes;
                for (int index = 0; index < choices.size(); index++) {
                    choices.get(index).setSelected(values[index] == minutes);
                }
            });
            choice.setTextSize(12);
            choice.setSelected(selected[0] == minutes);
            choices.add(choice);
        }
    }

    private void requestInitialNotifications() {
        android.content.SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        if (prefs.getBoolean("notificationPrompted", false)) return;
        prefs.edit().putBoolean("notificationPrompted", true).apply();
        requestNotificationPermission();
    }

    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1041);
        }
    }

    private void showTab(boolean showGrades) {
        closeAuxiliary();
        gradesVisible = showGrades;
        screenTitle.setText(showGrades ? "Оценки" : "Расписание пар");
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
        grades.setVisibility(gradesVisible && auxiliaryPage == null && visible ? View.VISIBLE : View.GONE);
        gradePanel.setVisibility(gradesVisible && auxiliaryPage == null && !visible ? View.VISIBLE : View.GONE);
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
        if (auxiliaryPage != null) showTab(false);
        else if (gradesVisible && webVisible) loadJournal(journalUrl);
        else if (gradesVisible && !journalUrl.equals(GRADES)) loadJournal(GRADES);
        else if (gradesVisible) showTab(false);
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        if (installPermissionPending) {
            installPermissionPending = false;
            openDownloadedUpdate(false);
        }
        if (exactSettingsOpened) {
            exactSettingsOpened = false;
            LessonAlerts.refreshReminders(this);
        }
        clockUpdate.run();
        if (!groupId.isEmpty() && !syncInFlight) loadSchedule(false, true);
        queueSync();
    }

    @Override protected void onPause() {
        foreground = false;
        scroll.removeCallbacks(clockUpdate);
        scroll.removeCallbacks(periodicSync);
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

    private void showAuxiliary(String title, View page) {
        closeAuxiliary();
        gradesVisible = false;
        auxiliaryPage = page;
        screenTitle.setText(title);
        backButton.setVisibility(View.VISIBLE);
        scheduleControls.setVisibility(View.GONE);
        scroll.setVisibility(View.GONE);
        if (gradePanel != null) gradePanel.setVisibility(View.GONE);
        if (grades != null) grades.setVisibility(View.GONE);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        page.setAlpha(0);
        page.setTranslationY(dp(8));
        page.animate().alpha(1).translationY(0).setInterpolator(ENTER).setDuration(220).start();
    }

    private void closeAuxiliary() {
        if (auxiliaryPage == null) return;
        if (groupSearch != null) {
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                .hideSoftInputFromWindow(groupSearch.getWindowToken(), 0);
            groupSearch = null;
            groupResults = null;
        }
        root.removeView(auxiliaryPage);
        auxiliaryPage = null;
        backButton.setVisibility(View.GONE);
    }

    private void chooseGroup() {
        LinearLayout page = column();
        page.setPadding(dp(16), dp(16), dp(16), 0);
        page.setFocusableInTouchMode(true);
        showAuxiliary("Группы", page);
        groupSearch = new EditText(this);
        groupSearch.setSingleLine();
        groupSearch.setTextColor(ink);
        groupSearch.setHintTextColor(muted);
        groupSearch.setHint("Поиск группы");
        groupSearch.setContentDescription("Поиск группы");
        groupSearch.setPadding(dp(14), dp(10), dp(14), dp(10));
        groupSearch.setBackground(panelShape(false));
        page.addView(groupSearch, new LinearLayout.LayoutParams(-1, -2));
        ScrollView list = new ScrollView(this);
        groupResults = column();
        groupResults.setPadding(0, dp(10), 0, dp(18));
        list.addView(groupResults);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        page.requestFocus();
        groupSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (groupCatalog != null && groupResults != null) renderGroups(text.toString());
            }
            @Override public void afterTextChanged(android.text.Editable text) { }
        });
        if (groupCatalog != null) renderGroups("");
        else loadGroups();
    }

    private void loadGroups() {
        if (groupResults == null) return;
        groupResults.removeAllViews();
        groupResults.addView(label("Загрузка групп…", 15, muted, false));
        if (groupsLoading) return;
        groupsLoading = true;
        new Thread(() -> {
            List<JSONObject> entries = new ArrayList<>();
            try {
                JSONArray result = ScheduleStore.request("getGroups", "");
                for (int i = 0; i < result.length(); i++) {
                    JSONObject group = result.optJSONObject(i);
                    if (group != null && group.optString("id").matches("[0-9]+")
                            && !group.optString("name").isEmpty()) entries.add(group);
                }
                Collections.sort(entries, (a, b) -> a.optString("name").compareTo(b.optString("name")));
            } catch (Exception ignored) {
                // Display a retry action in the group tab.
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                groupsLoading = false;
                if (!entries.isEmpty()) groupCatalog = entries;
                if (groupResults == null) return;
                if (groupCatalog != null) renderGroups(groupSearch.getText().toString());
                else {
                    groupResults.removeAllViews();
                    groupResults.addView(label("Не удалось загрузить группы", 16, ink, true));
                    LinearLayout actions = new LinearLayout(this);
                    button(actions, "Повторить", "Загрузить список групп", this::loadGroups);
                    groupResults.addView(actions);
                }
            });
        }).start();
    }

    private String groupQuery(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]", "");
    }

    private void renderGroups(String query) {
        groupResults.removeAllViews();
        String filter = groupQuery(query);
        for (JSONObject group : groupCatalog) {
            String name = group.optString("name");
            if (!groupQuery(name).contains(filter) && !groupQuery(group.optString("shortname")).contains(filter)) continue;
            boolean selected = groupId.equals(group.optString("id"));
            TextView row = label(selected ? name + "  ✓" : name, 17, selected ? accent : ink, true);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setMinHeight(dp(52));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x447698dc), panelShape(selected), null));
            row.setOnClickListener(v -> {
                groupId = group.optString("id");
                groupName = name;
                getPreferences(MODE_PRIVATE).edit().putString("groupId", groupId)
                    .putString("groupName", groupName).apply();
                date.setTimeInMillis(System.currentTimeMillis());
                showTab(false);
                LessonAlerts.refreshReminders(this);
                load();
                requestInitialNotifications();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(7);
            groupResults.addView(row, params);
        }
        if (groupResults.getChildCount() == 0) {
            groupResults.addView(label("Группы не найдены", 16, muted, false));
        }
    }

    private GradientDrawable panelShape(boolean selected) {
        int top = selected ? (dark ? 0xff2c4669 : 0xffe3eeff) : (dark ? 0xff23344d : Color.WHITE);
        int bottom = selected ? (dark ? 0xff20324e : 0xffcedfff) : surface;
        GradientDrawable shape = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{top, bottom});
        shape.setCornerRadius(dp(16));
        shape.setStroke(dp(1), dark ? 0xff435777 : 0xffcfdef2);
        return shape;
    }

    private void load() {
        loadSchedule(false, false);
    }

    private void loadSchedule(boolean force, boolean quiet) {
        if (quiet && syncInFlight) return;
        if (groupId.isEmpty()) {
            week.setText("Группа не выбрана");
            days.removeAllViews();
            days.addView(label("Выбери группу, чтобы открыть расписание.", 16, muted, false));
            LinearLayout actions = new LinearLayout(this);
            button(actions, "Выбрать группу", "Открыть группы", this::chooseGroup);
            days.addView(actions);
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
        String group = groupId;
        Date requested = date.getTime();
        String key = ScheduleStore.weekKey(group, requested);
        JSONArray cached = ScheduleStore.cached(this, key);
        if (!quiet) {
            displayedSchedule = null;
            days.removeAllViews();
            lessonHighlights.clear();
            dayHeadings.clear();
            scroll.scrollTo(0, 0);
            if (cached != null) {
                displayedSchedule = cached.toString();
                show(cached, slideDirection, true);
            } else {
                days.addView(new ProgressBar(this), new LinearLayout.LayoutParams(dp(36), dp(36)));
            }
        }
        syncInFlight = true;
        new Thread(() -> {
            JSONArray result = null;
            try {
                result = ScheduleStore.sync(getApplicationContext(), group, requested, force);
            } catch (Exception ignored) {
                // A failed request leaves the last successful schedule on screen.
            }
            Date now = new Date();
            if (!key.equals(ScheduleStore.weekKey(group, now))) {
                try {
                    ScheduleStore.sync(getApplicationContext(), group, now, false);
                } catch (Exception ignored) { }
            }
            JSONArray data = result;
            runOnUiThread(() -> {
                if (request != generation || isDestroyed()) return;
                syncInFlight = false;
                if (data != null) {
                    String snapshot = data.toString();
                    if (!snapshot.equals(displayedSchedule)) {
                        boolean firstDraw = displayedSchedule == null;
                        displayedSchedule = snapshot;
                        show(data, slideDirection, firstDraw);
                    }
                } else if (cached == null && displayedSchedule == null) {
                    days.removeAllViews();
                    days.addView(label("Не удалось загрузить расписание", 18, ink, true));
                    days.addView(label("Проверь интернет и выбери «Обновить» в меню.", 15, muted, false));
                }
            });
        }).start();
    }

    private void queueSync() {
        scroll.removeCallbacks(periodicSync);
        if (foreground) scroll.postDelayed(periodicSync,
            getPreferences(MODE_PRIVATE).getInt("syncMinutes", 5) * 60_000L);
    }

    private void show(JSONArray data, int slideDirection, boolean navigate) {
        int oldScroll = scroll.getScrollY();
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
            if (navigate) {
                panel.setAlpha(0);
                panel.setTranslationY(dp(10));
                panel.setTranslationX(dp(20) * slideDirection);
                panel.animate().alpha(1).translationX(0).translationY(0)
                    .setInterpolator(ENTER).setStartDelay(Math.min(i * 28L, 112L))
                    .setDuration(380).start();
            }
        }
        if (days.getChildCount() == 0) days.addView(label(dayOnly ? "Сегодня пар нет" : "На этой неделе расписания нет", 18, ink, true));
        displayedToday = null;
        refreshHighlights();
        if (!navigate) scroll.post(() -> scroll.scrollTo(0, oldScroll));
        if (navigate && todayPanel != null && !gradesVisible) {
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
