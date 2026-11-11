// Riegel – reine Logik ohne DOM, damit sie sich mit Node testen lässt (tests/logic.test.js).
// Die Zeittexte entsprechen android/.../guard/Texts.java (dort mit JUnit getestet).

export const OWN_PACKAGE = "app.riegel";

// Bekannte Social-Media-Apps. Beim Einrichten bekommt jede installierte davon ein Tageslimit (außer NO_DEFAULT).
export const SOCIAL = {
  "com.instagram.android": "Instagram",
  "com.instagram.lite": "Instagram Lite",
  "com.zhiliaoapp.musically": "TikTok",
  "com.ss.android.ugc.trill": "TikTok",
  "com.zhiliaoapp.musically.go": "TikTok Lite",
  "com.google.android.youtube": "YouTube",
  "com.snapchat.android": "Snapchat",
  "com.facebook.katana": "Facebook",
  "com.facebook.lite": "Facebook Lite",
  "com.twitter.android": "X (Twitter)",
  "com.reddit.frontpage": "Reddit",
  "com.instagram.barcelona": "Threads",
  "com.pinterest": "Pinterest",
  "tv.twitch.android.app": "Twitch",
  "com.bereal.ft": "BeReal",
  "com.tumblr": "Tumblr",
  "com.ninegag.android.app": "9GAG",
};

// Nie begrenzbar (das native Plugin ergänzt Startbildschirm, Standard-Telefon-App und Einstellungen des Geräts)
export const NEVER = [
  OWN_PACKAGE, "com.android.settings", "com.android.dialer", "com.google.android.dialer",
  "com.samsung.android.dialer", "com.android.phone", "com.android.systemui", "com.android.emergency",
];

// Bekannt, aber beim Einrichten nicht vorgewählt (lässt sich von Hand begrenzen)
export const NO_DEFAULT = ["com.google.android.youtube"];

export const limitable = app => !app.never && !NEVER.includes(app.pkg);

export const DEFAULT_LIMIT = 20;                       // Minuten pro Tag
export const LIMIT_CHOICES = [5, 10, 15, 20, 30, 45, 60, 90, 120];
export const EXTRA_MIN = 10;                           // „Mehr Zeit“ gibt so viele Minuten für heute
export const WAIT_MS = 5 * 60_000;                     // Hürde: so lange warten …
export const CODE_LEN = 32;                            // … oder so viele Zeichen abtippen
export const WARN_MS = 2 * 60_000;                     // Hinweis, wenn nur noch so viel übrig ist

/** Startwerte: jede installierte bekannte Social-Media-App bekommt DEFAULT_LIMIT (außer NO_DEFAULT). */
export function defaultLimits(apps) {
  const out = {};
  for (const a of apps) if (limitable(a) && a.pkg in SOCIAL && !NO_DEFAULT.includes(a.pkg)) out[a.pkg] = DEFAULT_LIMIT;
  return out;
}

/**
 * Welche Apps durch eine Änderung mehr Zeit bekämen (höheres Limit oder gar keins mehr).
 * Nur dafür braucht es die Hürde; strenger machen geht immer sofort.
 */
export function loosened(before, after) {
  const lim = (m, p) => (p in m ? m[p] : Infinity);
  const pkgs = new Set([...Object.keys(before), ...Object.keys(after)]);
  return [...pkgs].filter(p => lim(after, p) > lim(before, p)).sort();
}

// ---------- Zeitfenster: App nur von … bis … frei, sonst ganz gesperrt ----------
// Ein Fenster ist [von, bis] in Minuten nach Mitternacht; bis < von heißt über Mitternacht (19:45–5:00).

export const DEFAULT_WINDOW = [19 * 60, 21 * 60];

/** Ist die Minute des Tages (0–1439) im freien Fenster? */
export function inWindow(minute, [from, to]) {
  return from < to ? minute >= from && minute < to : from > to ? minute >= from || minute < to : false;
}

/** „5:00“, „19:45“ */
export const hm = min => `${Math.floor(min / 60)}:${String(min % 60).padStart(2, "0")}`;
/** „08:05“ für <input type="time"> und zurück */
export const hhmm = min => `${String(Math.floor(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")}`;
export const parseHm = s => { const m = /^(\d{1,2}):(\d{2})/.exec(s || ""); return m ? Math.min(23, +m[1]) * 60 + Math.min(59, +m[2]) : null; };

export const windowText = ([from, to]) => `frei ${hm(from)}–${hm(to)}`;

/** Stand jetzt: { open, text } – „frei bis 5:00“ bzw. „gesperrt bis 19:45“. */
export function windowState(w, minute) {
  const open = inWindow(minute, w);
  return { open, text: open ? `frei bis ${hm(w[1])}` : `gesperrt bis ${hm(w[0])}` };
}

/**
 * Welche Apps durch eine Änderung mehr dürften. cfg = { limits: {pkg: min}, windows: {pkg: [von, bis]} }.
 * Mehr Zeit: höheres Limit, kein Limit mehr, ein Fenster mit einer Minute, die vorher gesperrt war,
 * und jeder Wechsel zwischen Tageslimit und Zeitfenster.
 */
export function loosenedCfg(before, after) {
  const bl = before.limits || {}, bw = before.windows || {}, al = after.limits || {}, aw = after.windows || {};
  const pkgs = new Set([...Object.keys(bl), ...Object.keys(bw), ...Object.keys(al), ...Object.keys(aw)]);
  const out = [];
  for (const p of pkgs) {
    const was = p in bl ? "l" : p in bw ? "w" : "", now = p in al ? "l" : p in aw ? "w" : "";
    if (!was) continue;
    if (was !== now) out.push(p);
    else if (now === "l" ? al[p] > bl[p] : widened(bw[p], aw[p])) out.push(p);
  }
  return out.sort();
}

const widened = (before, after) => {
  for (let m = 0; m < 1440; m++) if (inWindow(m, after) && !inWindow(m, before)) return true;
  return false;
};

/** Dauer: „0 min“, „<1 min“, „12 min“, „1 h“, „1 h 5 min“ (abgerundet auf volle Minuten). */
export function dur(ms) {
  if (!(ms > 0)) return "0 min";
  if (ms < 60_000) return "<1 min";
  const min = Math.floor(ms / 60_000);
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60), m = min % 60;
  return m ? `${h} h ${m} min` : `${h} h`;
}

/** Erlaubte Zeit heute in ms (Limit + gewährte Extra-Minuten). */
export const allowedMs = (limitMin, extraMin = 0) => (limitMin + extraMin) * 60_000;

/** Stand einer App heute: { left, over, ratio, text }. */
export function appState(usedMs, limitMin, extraMin = 0) {
  const allowed = allowedMs(limitMin, extraMin);
  const left = Math.max(0, allowed - usedMs);
  const over = usedMs >= allowed;
  const ratio = allowed ? Math.min(1, usedMs / allowed) : 1;
  return { left, over, ratio, text: over ? "gesperrt bis Mitternacht" : `noch ${leftText(left)}` };
}

/** Restzeit aufgerundet, damit „noch 0 min“ nie erscheint, solange die App offen ist. */
export function leftText(ms) {
  if (ms <= 0) return "0 min";
  const min = Math.ceil(ms / 60_000);
  if (min < 60) return `${min} min`;
  const h = Math.floor(min / 60), m = min % 60;
  return m ? `${h} h ${m} min` : `${h} h`;
}

// ---------- Hürde: Code abtippen ----------

// ohne leicht verwechselbare Zeichen (0/o, 1/l/i)
export const CODE_CHARS = "abcdefghjkmnpqrstuvwxyz23456789";

/** Zufälliger Code; rand liefert Zahlen in [0, 1) (Tests geben einen festen Generator mit). */
export function makeCode(len = CODE_LEN, rand = Math.random) {
  let s = "";
  for (let i = 0; i < len; i++) s += CODE_CHARS[Math.floor(rand() * CODE_CHARS.length)];
  return s;
}

/** In Vierergruppen zum Abtippen: „abcd efgh …“. */
export const groups = code => code.match(/.{1,4}/g).join(" ");

const norm = s => s.toLowerCase().replace(/\s+/g, "");

/** Stimmt die Eingabe (Leerzeichen und Groß-/Kleinschreibung egal)? */
export const codeMatches = (input, code) => norm(input) === norm(code);

/** Wie viele Zeichen vom Anfang schon stimmen – für die Fortschrittsanzeige. */
export function codeProgress(input, code) {
  const a = norm(input), b = norm(code);
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i++;
  return { ok: i, typo: i < a.length };
}

// ---------- Statistik ----------

/** Lokales Datum als „2026-11-10“. */
export function dayKey(d) {
  const p = n => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

const WD = ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"];
export const weekday = key => WD[new Date(key + "T12:00:00").getDay()];

/**
 * Tageswerte für das Diagramm. days = [{ date, apps: { pkg: ms } }] vom Plugin (ältester zuerst),
 * pkgs = welche Apps zählen (die mit Limit). → [{ date, label, total }]
 */
export function series(days, pkgs) {
  return days.map(d => ({
    date: d.date,
    label: weekday(d.date),
    total: pkgs.reduce((s, p) => s + (d.apps?.[p] || 0), 0),
  }));
}

/** Je App: heute, Summe und Schnitt über die Tage. Sortiert nach Summe, größte zuerst. */
export function perApp(days, pkgs) {
  const today = days[days.length - 1]?.apps || {};
  return pkgs.map(pkg => {
    const sum = days.reduce((s, d) => s + (d.apps?.[pkg] || 0), 0);
    return { pkg, today: today[pkg] || 0, sum, avg: days.length ? sum / days.length : 0 };
  }).sort((a, b) => b.sum - a.sum || a.pkg.localeCompare(b.pkg));
}

/** Tage in Folge (bis gestern), an denen keine begrenzte App über ihrem Limit lag. */
export function streak(days, limits) {
  let n = 0;
  for (let i = days.length - 2; i >= 0; i--) {
    const d = days[i];
    const ok = Object.entries(limits).every(([p, m]) => (d.apps?.[p] || 0) <= m * 60_000 + 30_000);
    if (!ok) break;
    n++;
  }
  return n;
}

// ---------- Berechtigungen ----------

// Schritte des Assistenten. need = ohne diese Berechtigung kann Riegel nicht sperren.
export const PERM_STEPS = [
  { key: "usage", title: "Nutzungszugriff", why: "Damit Riegel misst, wie lange du welche App nutzt, und erkennt, welche gerade offen ist. Es bleibt alles auf dem Handy.", btn: "Einstellungen öffnen", how: "Riegel in der Liste antippen und „Nutzungszugriff erlauben“ einschalten, dann zurück.", need: true },
  { key: "overlay", title: "Über anderen Apps anzeigen", why: "Damit Riegel den Sperrbildschirm über eine App legen kann, deren Zeit für heute aufgebraucht ist.", btn: "Einstellungen öffnen", how: "Schalter „Über anderen Apps anzeigen“ einschalten, dann zurück.", need: true },
  { key: "notifications", title: "Benachrichtigungen", why: "Riegel braucht eine dauerhafte Benachrichtigung, um im Hintergrund wach zu bleiben, und warnt dich 2 Minuten vor dem Limit.", btn: "Erlauben", need: false },
  { key: "battery", title: "Akku-Optimierung ausnehmen", why: "Empfohlen: Manche Handys beenden Riegel sonst nach einiger Zeit im Hintergrund.", btn: "Ausnehmen", need: false },
];

export const missing = perms => PERM_STEPS.filter(s => s.need && !perms?.[s.key]).map(s => s.key);
