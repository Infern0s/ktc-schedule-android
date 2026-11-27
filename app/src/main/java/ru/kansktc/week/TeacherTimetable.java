package ru.kansktc.week;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

final class TeacherTimetable {
    static final class Teacher {
        final String id;
        final String name;
        final List<JSONObject> lessons = new ArrayList<>();

        Teacher(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static final class Week {
        final List<Teacher> teachers;
        final int failedGroups;

        Week(List<Teacher> teachers, int failedGroups) {
            this.teachers = teachers;
            this.failedGroups = failedGroups;
        }
    }

    private static String cachedWeek;
    private static Week cached;
    private static long savedAt;

    static synchronized Week load(Context context, Date date) throws Exception {
        String key = ScheduleStore.weekKey("", date);
        if (key.equals(cachedWeek) && System.currentTimeMillis() - savedAt < 300_000) return cached;
        JSONArray groups = ScheduleStore.request("getGroups", "");
        ExecutorService workers = Executors.newFixedThreadPool(5);
        List<Future<List<JSONObject>>> requests = new ArrayList<>();
        Map<String, Teacher> teachers = new HashMap<>();
        int failed = 0;
        try {
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.optJSONObject(i);
                if (group == null || !group.optString("id").matches("[0-9]+")) continue;
                requests.add(workers.submit(() -> readGroup(context, date, group)));
            }
            for (Future<List<JSONObject>> request : requests) {
                try {
                    for (JSONObject item : request.get()) {
                        String id = item.optString("uid");
                        Teacher teacher = teachers.get(id);
                        if (teacher == null) {
                            teacher = new Teacher(id, item.optString("lastname").trim());
                            teachers.put(id, teacher);
                        }
                        teacher.lessons.add(item);
                    }
                } catch (ExecutionException unavailable) {
                    failed++;
                }
            }
        } finally {
            workers.shutdownNow();
        }
        if (requests.isEmpty() || failed == requests.size()) throw new IOException("Teacher schedule unavailable");
        List<Teacher> sorted = new ArrayList<>(teachers.values());
        Collections.sort(sorted, (a, b) -> a.name.compareToIgnoreCase(b.name));
        for (Teacher teacher : sorted) {
            Collections.sort(teacher.lessons, (a, b) -> {
                int day = a.optString("date").compareTo(b.optString("date"));
                return day == 0 ? Integer.compare(a.optInt("lesson"), b.optInt("lesson")) : day;
            });
        }
        Week result = new Week(sorted, failed);
        if (failed == 0) {
            cachedWeek = key;
            cached = result;
            savedAt = System.currentTimeMillis();
        }
        return result;
    }

    private static List<JSONObject> readGroup(Context context, Date date, JSONObject group) throws Exception {
        String id = group.optString("id");
        JSONArray days = ScheduleStore.cached(context, ScheduleStore.weekKey(id, date));
        if (days == null || !ScheduleStore.isFresh(context, ScheduleStore.weekKey(id, date), 5)) {
            days = ScheduleStore.request("getGroupTimetable",
                "group_id=" + id + "&date=" + ScheduleStore.date(date) + "&mode=week");
        }
        List<JSONObject> active = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject day = days.optJSONObject(i);
            JSONArray slots = day == null ? null : day.optJSONArray("lessons");
            for (int s = 0; slots != null && s < slots.length(); s++) {
                JSONArray variants = slots.optJSONArray(s);
                if (variants == null) continue;
                String original = null;
                for (int n = 0; n < variants.length(); n++) {
                    JSONObject lesson = variants.optJSONObject(n);
                    if (lesson != null && lesson.optInt("cancel") == 1 && !lesson.isNull("discipline")) {
                        original = lesson.optString("discipline");
                    }
                }
                for (int n = 0; n < variants.length(); n++) {
                    JSONObject lesson = variants.optJSONObject(n);
                    if (lesson == null || lesson.optInt("cancel") == 1 || lesson.isNull("discipline")
                            || !lesson.optString("uid").matches("[0-9]+")
                            || lesson.isNull("lastname") || lesson.optString("lastname").trim().isEmpty()) continue;
                    JSONObject item = new JSONObject(lesson.toString());
                    item.put("date", day.optString("date"));
                    item.put("groupName", group.optString("name"));
                    if (original != null && !original.isEmpty()) item.put("replacementOf", original);
                    active.add(item);
                }
            }
        }
        return active;
    }
}
