const BASE = 'https://pro.kansk-tc.ru/procollege/core_procollege/helpers/rest/';
const cache = new Map();
const dayFormat = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Krasnoyarsk', year: 'numeric', month: '2-digit', day: '2-digit',
});

export function today() {
  return dayFormat.format(new Date()).replaceAll('-', '.');
}

export function monday(date, offset = 0) {
  const day = new Date(date.replaceAll('.', '-') + 'T12:00:00Z');
  day.setUTCDate(day.getUTCDate() - ((day.getUTCDay() + 6) % 7) + offset * 7);
  return day.toISOString().slice(0, 10).replaceAll('-', '.');
}

function cached(key, loader) {
  const hit = cache.get(key);
  if (hit && hit.until > Date.now()) return hit.promise;
  for (const [id, entry] of cache) if (entry.until <= Date.now()) cache.delete(id);
  const promise = Promise.resolve().then(loader).catch(error => {
    cache.delete(key);
    throw error;
  });
  cache.set(key, { until: Date.now() + 300_000, promise });
  return promise;
}

async function request(endpoint, data = {}) {
  const response = await fetch(BASE + endpoint, {
    method: 'POST', body: new URLSearchParams(data), signal: AbortSignal.timeout(12000),
  });
  if (!response.ok) throw new Error('Колледж HTTP ' + response.status);
  const result = await response.json();
  if (!Array.isArray(result)) throw new Error('Неожиданный ответ колледжа');
  return result;
}

export function groups() {
  return cached('groups', async () => (await request('getGroups'))
    .filter(group => /^\d+$/.test(group.id) && group.name)
    .sort((a, b) => a.name.localeCompare(b.name, 'ru')));
}

export function lessons(days, group) {
  return days.flatMap(day => (day.lessons || []).flatMap(slot => {
    const actual = slot.filter(item => item?.discipline && Number(item.cancel) !== 1);
    const replacement = slot.some(item => item?.discipline && Number(item.cancel) === 1);
    return actual.map(item => ({
      ...item, date: day.date, group: group.name, replacement,
    }));
  }));
}

export function timetable(group, date) {
  const week = monday(date);
  return cached(group.id + ':' + week, async () => lessons(
    await request('getGroupTimetable', { group_id: group.id, date: week, mode: 'week' }), group,
  ));
}

export function teachers(date) {
  const week = monday(date);
  return cached('teachers:' + week, async () => {
    const catalog = await groups();
    const byId = new Map();
    let cursor = 0, failed = 0;
    await Promise.all(Array.from({ length: 5 }, async () => {
      while (cursor < catalog.length) {
        const group = catalog[cursor++];
        try {
          for (const lesson of await timetable(group, week)) {
            if (!/^\d+$/.test(lesson.uid) || !lesson.lastname?.trim()) continue;
            const teacher = byId.get(String(lesson.uid)) || { id: String(lesson.uid), name: lesson.lastname.trim(), lessons: [] };
            teacher.lessons.push(lesson);
            byId.set(teacher.id, teacher);
          }
        } catch {
          failed++;
        }
      }
    }));
    if (failed === catalog.length || !catalog.length) throw new Error('Список преподавателей недоступен');
    if (failed) cache.delete('teachers:' + week);
    return { entries: [...byId.values()].sort((a, b) => a.name.localeCompare(b.name, 'ru')), failed };
  });
}
