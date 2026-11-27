import { groups, timetable, teachers, today, monday } from './college.mjs';

const token = process.env.TELEGRAM_BOT_TOKEN?.trim();
if (!token || !/^\d+:[A-Za-z0-9_-]+$/.test(token)) {
  console.error('Добавь TELEGRAM_BOT_TOKEN в bot/.env');
  process.exit(1);
}

async function telegram(method, payload = {}) {
  const response = await fetch('https://api.telegram.org/bot' + token + '/' + method, {
    method: 'POST', headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload), signal: AbortSignal.timeout(38000),
  });
  const data = await response.json();
  if (!data.ok) {
    if (data.error_code === 400 && data.description?.includes('message is not modified')) return;
    throw new Error('Telegram HTTP ' + data.error_code);
  }
  return data.result;
}

const btn = (text, callback_data) => ({ text, callback_data });
const home = [btn('Главное меню', 'home')];
const queries = new Map();
const searching = new Map();
const pageSize = 12;

async function screen(chat, message, text, keyboard) {
  const payload = { chat_id: chat, text, reply_markup: { inline_keyboard: keyboard } };
  if (message) return telegram('editMessageText', { ...payload, message_id: message });
  return telegram('sendMessage', payload);
}

function menu(chat, message) {
  searching.delete(chat);
  return screen(chat, message, 'Пары КТК\nВыбери расписание', [
    [btn('По группе', 'g:0:0'), btn('По преподавателю', 't:0:0')],
  ]);
}

function normalize(value) {
  return value.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');
}

async function catalog(chat, message, kind, week, page) {
  const key = chat + ':' + kind;
  const query = queries.get(key) || '';
  if (!query) queries.delete(key);
  const date = monday(today(), week);
  let all, failed = 0;
  if (kind === 't') {
    if (message) await screen(chat, message, 'Загружаю преподавателей…', [home]);
    ({ entries: all, failed } = await teachers(date));
  } else {
    all = await groups();
  }
  const filtered = all.filter(item => normalize(item.name).includes(normalize(query))
    || normalize(item.shortname || '').includes(normalize(query)));
  page = Math.min(Math.max(0, page), Math.max(0, Math.ceil(filtered.length / pageSize) - 1));
  const visible = filtered.slice(page * pageSize, (page + 1) * pageSize);
  const keyboard = visible.map(item => {
    const name = all.some(other => other.id !== item.id && other.name === item.name)
      ? item.name + ' · ' + item.id : item.name;
    return [btn(name.slice(0, 60), 'pick:' + kind + ':' + week + ':' + item.id)];
  });
  const paging = [];
  if (page > 0) paging.push(btn('‹', kind + ':' + week + ':' + (page - 1)));
  if ((page + 1) * pageSize < filtered.length) paging.push(btn('›', kind + ':' + week + ':' + (page + 1)));
  if (paging.length) keyboard.push(paging);
  keyboard.push([btn('Поиск', 'search:' + kind + ':' + week), btn('Сбросить поиск', 'reset:' + kind + ':' + week)]);
  if (kind === 't') keyboard.push([btn('‹ Неделя', 't:' + (week - 1) + ':0'), btn('Неделя ›', 't:' + (week + 1) + ':0')]);
  keyboard.push(home);
  const title = kind === 't' ? 'Преподаватели · неделя с ' + date : 'Группы';
  const hint = query ? '\nПоиск: ' + query : '';
  return screen(chat, message, title + hint + (visible.length ? '' : '\nНичего не найдено.')
    + (failed ? '\nЧасть расписаний пока недоступна.' : ''), keyboard);
}

function weekText(title, records, date, onlyToday, failed) {
  const byDay = new Map();
  const ordered = records.filter(item => !onlyToday || item.date === today()).sort((a, b) =>
    a.date.localeCompare(b.date) || Number(a.lesson) - Number(b.lesson));
  for (const item of ordered) {
    if (!byDay.has(item.date)) byDay.set(item.date, new Map());
    const day = byDay.get(item.date);
    const row = [item.lesson, item.lesson_start, item.lesson_end, item.discipline, item.lastname, item.classroom, item.replacement];
    const key = JSON.stringify(row);
    if (!day.has(key)) day.set(key, { row, groups: new Set() });
    day.get(key).groups.add(item.group);
  }
  const text = [title, onlyToday ? 'На сегодня' : 'Неделя с ' + date];
  for (const [day, slots] of byDay) {
    const caption = new Date(day.replaceAll('.', '-') + 'T12:00:00Z')
      .toLocaleDateString('ru-RU', { weekday: 'short', day: 'numeric', month: 'long', timeZone: 'UTC' });
    text.push('\n' + caption);
    for (const { row: [number, start, end, subject, teacher, room, replaced], groups } of slots.values()) {
      text.push(number + '. ' + start + '–' + end + ' · ' + subject
        + (replaced ? ' · Замена' : '') + '\n' + [teacher, room ? 'ауд. ' + room : '', [...groups].sort().join(', ')].filter(Boolean).join(' · '));
    }
  }
  if (!byDay.size) text.push('\nПар нет.');
  if (failed) text.push('\nЧасть расписаний пока недоступна.');
  return text.join('\n');
}

async function schedule(chat, message, kind, week, id, mode, page) {
  const date = monday(today(), week);
  let entries, failed, title;
  if (kind === 't') {
    const index = await teachers(date);
    const teacher = index.entries.find(item => item.id === id);
    entries = teacher?.lessons || [];
    title = teacher?.name || 'Преподаватель';
    failed = index.failed;
  } else {
    const group = (await groups()).find(item => String(item.id) === id);
    if (!group) return catalog(chat, message, 'g', week, 0);
    entries = await timetable(group, date);
    title = group.name;
  }
  const parts = [];
  let remaining = weekText(title, entries, date, mode === 'd', failed);
  while (remaining.length > 3600) {
    const boundary = remaining.lastIndexOf('\n', 3600);
    const split = boundary > 0 ? boundary : 3600;
    parts.push(remaining.slice(0, split));
    remaining = remaining.slice(split).trimStart();
  }
  parts.push(remaining);
  page = Math.max(0, Math.min(page, parts.length - 1));
  const nav = [];
  if (page > 0) nav.push(btn('‹ Страница', 's:' + kind + ':' + week + ':' + id + ':' + mode + ':' + (page - 1)));
  if (page + 1 < parts.length) nav.push(btn('Страница ›', 's:' + kind + ':' + week + ':' + id + ':' + mode + ':' + (page + 1)));
  return screen(chat, message, parts[page], [
    ...(nav.length ? [nav] : []),
    [btn('Сегодня', 's:' + kind + ':0:' + id + ':d:0'), btn('Неделя', 's:' + kind + ':' + week + ':' + id + ':w:0')],
    [btn('‹ Неделя', 's:' + kind + ':' + (week - 1) + ':' + id + ':w:0'), btn('Неделя ›', 's:' + kind + ':' + (week + 1) + ':' + id + ':w:0')],
    [btn('К списку', kind + ':' + week + ':0'), ...home],
  ]);
}

async function handle(update) {
  const query = update.callback_query;
  const incoming = query?.message || update.message;
  if (!incoming || incoming.chat.type !== 'private') return;
  const chat = incoming.chat.id;
  if (!query) {
    if (/^\/(start|menu)(?:\s|$)/.test(incoming.text || '') || !searching.has(chat)) return menu(chat);
    if (!incoming.text) return;
    const { kind, week } = searching.get(chat);
    searching.delete(chat);
    queries.set(chat + ':' + kind, incoming.text.trim().slice(0, 50));
    return catalog(chat, null, kind, week, 0);
  }
  const message = incoming.message_id;
  await telegram('answerCallbackQuery', { callback_query_id: query.id }).catch(() => {});
  const [action, kind, weekOrPage, id, mode, page] = (query.data || '').split(':');
  if (action === 'home') return menu(chat, message);
  if (action === 'g' || action === 't') return catalog(chat, message, action, Number(kind), Number(weekOrPage));
  if (action === 'pick' || action === 's') return schedule(chat, message, kind, Number(weekOrPage), id, mode || 'w', Number(page || 0));
  if (action === 'reset') {
    queries.delete(chat + ':' + kind);
    return catalog(chat, message, kind, Number(weekOrPage), 0);
  }
  if (action === 'search') {
    searching.set(chat, { kind, week: Number(weekOrPage) });
    return screen(chat, message, 'Напиши часть названия группы или фамилии преподавателя.', [home]);
  }
}

let offset = 0;
let bot;
try {
  bot = await telegram('getMe');
  console.log('@' + bot.username + ' https://t.me/' + bot.username);
} catch (error) {
  console.error('Не удалось подключиться к Telegram: ' + (error.message.startsWith('Telegram HTTP') ? error.message : 'ошибка сети'));
  process.exit(1);
}
const queues = new Map();
while (true) {
  try {
    const updates = await telegram('getUpdates', { offset, timeout: 25, allowed_updates: ['message', 'callback_query'] });
    for (const update of updates) {
      offset = update.update_id + 1;
      const chat = update.message?.chat?.id || update.callback_query?.message?.chat?.id;
      if (!chat) continue;
      const next = (queues.get(chat) || Promise.resolve()).then(() => handle(update)).catch(async () => {
        await screen(chat, null, 'Не удалось загрузить расписание. Попробуй ещё раз.', [home]).catch(() => {});
      });
      queues.set(chat, next);
      next.finally(() => { if (queues.get(chat) === next) queues.delete(chat); });
    }
  } catch (error) {
    console.error(error.message.startsWith('Telegram HTTP') ? error.message : 'Telegram: ошибка сети');
    await new Promise(resolve => setTimeout(resolve, 5000));
  }
}
