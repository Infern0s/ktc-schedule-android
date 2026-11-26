package ru.kansktc.week;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class LessonAlerts extends BroadcastReceiver {
    private static final String CHANNEL = "lessons";
    private static final int JOB = 1040;
    private static final Object ALARM_LOCK = new Object();

    static void scheduleSync(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        int minutes = Math.max(15, ScheduleStore.settings(context).getInt("syncMinutes", 5));
        for (JobInfo current : jobs.getAllPendingJobs()) {
            if (current.getId() == JOB && current.getIntervalMillis() == minutes * 60_000L) return;
        }
        jobs.schedule(new JobInfo.Builder(JOB, new ComponentName(context, BackgroundSync.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(minutes * 60_000L).setPersisted(true).build());
    }

    public static final class BackgroundSync extends JobService {
        @Override public boolean onStartJob(JobParameters params) {
            new Thread(() -> {
                try {
                    String group = ScheduleStore.settings(this).getString("groupId", "");
                    if (!group.isEmpty()) ScheduleStore.sync(this, group, new Date(), false);
                } catch (Exception ignored) {
                    // Keep the cached schedule when the college server is unavailable.
                } finally {
                    jobFinished(params, false);
                }
            }).start();
            return true;
        }

        @Override public boolean onStopJob(JobParameters params) {
            return false;
        }
    }

    private static final class Slot {
        final String key;
        final String date;
        final String number;
        final Set<String> subjects = new LinkedHashSet<>();
        final Set<String> signature = new HashSet<>();
        String start;
        boolean cancelled;
        Slot(String date, String number) {
            this.date = date;
            this.number = number;
            key = date + ":" + number;
        }
        String title() { return android.text.TextUtils.join(" / ", subjects); }
    }

    private static Map<String, Slot> slots(JSONArray days) {
        Map<String, Slot> result = new LinkedHashMap<>();
        for (int d = 0; days != null && d < days.length(); d++) {
            JSONObject day = days.optJSONObject(d);
            if (day == null) continue;
            JSONArray lessons = day.optJSONArray("lessons");
            for (int n = 0; lessons != null && n < lessons.length(); n++) {
                JSONArray variants = lessons.optJSONArray(n);
                for (int i = 0; variants != null && i < variants.length(); i++) {
                    JSONObject lesson = variants.optJSONObject(i);
                    if (lesson == null || lesson.isNull("discipline")) continue;
                    String subject = lesson.optString("discipline");
                    if (subject.isEmpty()) continue;
                    String date = day.optString("date"), number = lesson.optString("lesson");
                    String key = date + ":" + number;
                    Slot slot = result.get(key);
                    if (slot == null) {
                        slot = new Slot(date, number);
                        result.put(key, slot);
                    }
                    if (lesson.optInt("cancel") == 1) {
                        slot.cancelled = true;
                        continue;
                    }
                    String start = lesson.optString("lesson_start");
                    if (slot.start == null || startsAt(date, start) < startsAt(date, slot.start)) slot.start = start;
                    slot.subjects.add(subject);
                    slot.signature.add(subject + "|" + start + "|" + lesson.optString("lesson_end")
                        + "|" + lesson.optString("lastname") + "|" + lesson.optString("classroom"));
                }
            }
        }
        return result;
    }

    static void sync(Context context, String group, JSONArray previous, JSONArray latest) {
        SharedPreferences preferences = ScheduleStore.settings(context);
        Map<String, Slot> next = slots(latest);
        if (previous != null) {
            Map<String, Slot> before = slots(previous);
            Set<String> keys = new LinkedHashSet<>(before.keySet());
            keys.addAll(next.keySet());
            String today = ScheduleStore.date(new Date());
            for (String key : keys) {
                Slot old = before.get(key), current = next.get(key);
                Slot reference = current == null ? old : current;
                if (reference.date.compareTo(today) < 0) continue;
                boolean had = old != null && !old.signature.isEmpty();
                boolean has = current != null && !current.signature.isEmpty();
                if (had && !has && preferences.getBoolean("alertCancelled", true)) {
                    change(context, group, reference, "Пару отменили", old.title());
                } else if (!had && has && preferences.getBoolean("alertAdded", true)) {
                    change(context, group, reference, "Добавили пару", current.title());
                } else if (had && has && (!old.signature.equals(current.signature)
                        || old.cancelled != current.cancelled) && preferences.getBoolean("alertChanged", true)) {
                    change(context, group, reference, "Пару изменили", current.title());
                }
            }
        }
        synchronized (ALARM_LOCK) {
            if (group.equals(preferences.getString("groupId", ""))) reschedule(context, group, next);
        }
    }

    static void refreshReminders(Context context) {
        synchronized (ALARM_LOCK) {
            String group = ScheduleStore.settings(context).getString("groupId", "");
            reschedule(context, group, slots(ScheduleStore.cached(context, ScheduleStore.weekKey(group, new Date()))));
        }
    }

    private static void reschedule(Context context, String group, Map<String, Slot> slots) {
        SharedPreferences preferences = ScheduleStore.settings(context);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        for (String id : preferences.getStringSet("lessonAlarms", Collections.emptySet())) {
            alarms.cancel(alarm(context, id, null));
        }
        Set<String> ids = new HashSet<>();
        Set<String> delivered = preferences.getStringSet("deliveredLessons", Collections.emptySet());
        if (preferences.getBoolean("alertBefore", true)) {
            int lead = preferences.getInt("leadMinutes", 5);
            long now = System.currentTimeMillis();
            for (Slot slot : slots.values()) {
                if (slot.start == null) continue;
                long start = startsAt(slot.date, slot.start);
                long trigger = start - lead * 60_000L;
                if (start <= now) continue;
                String id = group + ":" + slot.key;
                if (delivered.contains(start + ":" + id)) continue;
                trigger = Math.max(now + 1_000, trigger);
                Intent extras = new Intent().putExtra("group", group).putExtra("start", start)
                    .putExtra("description", slot.start + " · " + slot.title());
                PendingIntent reminder = alarm(context, id, extras);
                try {
                    if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, reminder);
                    } else {
                        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, reminder);
                    }
                } catch (SecurityException denied) {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, reminder);
                }
                ids.add(id);
            }
        }
        preferences.edit().putStringSet("lessonAlarms", ids).apply();
    }

    private static PendingIntent alarm(Context context, String id, Intent extras) {
        Intent intent = new Intent(context, LessonAlerts.class)
            .setData(Uri.parse("ktc://lesson/" + Uri.encode(id)));
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE | (extras != null ? PendingIntent.FLAG_UPDATE_CURRENT : 0));
    }

    private static long startsAt(String date, String time) {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy.MM.dd H:mm", Locale.ROOT);
            format.setTimeZone(ScheduleStore.ZONE);
            format.setLenient(false);
            return format.parse(date + " " + time).getTime();
        } catch (Exception invalid) {
            return 0;
        }
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            scheduleSync(context);
            refreshReminders(context);
            return;
        }
        SharedPreferences settings = ScheduleStore.settings(context);
        if (!settings.getBoolean("alertBefore", true)
                || !settings.getString("groupId", "").equals(intent.getStringExtra("group"))
                || intent.getLongExtra("start", 0) <= System.currentTimeMillis()) return;
        long start = intent.getLongExtra("start", 0);
        String id = intent.getData().getLastPathSegment();
        String key = start + ":" + id;
        synchronized (ALARM_LOCK) {
            Set<String> delivered = new HashSet<>(settings.getStringSet("deliveredLessons", Collections.emptySet()));
            if (delivered.contains(key)) return;
            long cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L;
            Iterator<String> entries = delivered.iterator();
            while (entries.hasNext()) {
                String entry = entries.next();
                if (Long.parseLong(entry.substring(0, entry.indexOf(':'))) < cutoff) entries.remove();
            }
            delivered.add(key);
            settings.edit().putStringSet("deliveredLessons", delivered).apply();
        }
        post(context, intent.getDataString().hashCode(), "Скоро пара", intent.getStringExtra("description"));
    }

    private static void change(Context context, String group, Slot slot, String title, String subject) {
        post(context, ("change:" + group + ":" + slot.key).hashCode(), title,
            slot.date + " · " + slot.number + "-я пара · " + subject);
    }

    private static void post(Context context, int id, String title, String text) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        Notification.Builder notification;
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Расписание пар",
                NotificationManager.IMPORTANCE_DEFAULT));
            notification = new Notification.Builder(context, CHANNEL);
        } else {
            notification = new Notification.Builder(context);
        }
        Intent launch = new Intent(context, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE);
        manager.notify(id, notification.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(tap).setAutoCancel(true).build());
    }
}
