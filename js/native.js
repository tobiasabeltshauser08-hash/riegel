// Brücke zur Android-App (Capacitor). Im Browser gibt es eine Vorschau mit Beispieldaten,
// denn sperren kann Riegel nur als Android-App.
// Gegenstück: android/app/src/main/java/app/riegel/guard/GuardPlugin.java
import { dayKey, SOCIAL } from "./logic.js";

const cap = () => globalThis.Capacitor;
export const isNative = () => !!cap()?.isNativePlatform?.();

const call = (method, options = {}) => cap().nativePromise("Guard", method, options);

const native = {
  permissions: () => call("permissions"),                      // { usage, overlay, notifications, battery }
  request: which => call("request", { which }),                // Systemdialog bzw. Systemseite öffnen
  apps: () => call("apps").then(r => r.apps),                  // [{ pkg, label, icon, never }]
  config: () => call("config"),                                // { limits: {pkg: min}, extras: {pkg: min}, running }
  setLimits: limits => call("setLimits", { limits }),          // speichert und startet/stoppt den Wächter
  addExtra: (pkg, minutes) => call("addExtra", { pkg, minutes }),
  usage: days => call("usage", { days }).then(r => r.days),     // [{ date, apps: {pkg: ms} }], ältester zuerst
  pendingExtra: () => call("pendingExtra").then(r => r.pkg || null), // „Mehr Zeit“ vom Sperrbildschirm
};

// ---------- Vorschau im Browser ----------

const DEMO_APPS = [
  ["com.instagram.android", "Instagram", "#d6249f"], ["com.zhiliaoapp.musically", "TikTok", "#111111"],
  ["com.google.android.youtube", "YouTube", "#ff0000"], ["com.snapchat.android", "Snapchat", "#f7d300"],
  ["com.reddit.frontpage", "Reddit", "#ff4500"], ["com.whatsapp", "WhatsApp", "#25d366"],
  ["com.spotify.music", "Spotify", "#1db954"], ["com.google.android.gm", "Gmail", "#ea4335"],
  ["com.google.android.apps.maps", "Maps", "#34a853"], ["com.android.chrome", "Chrome", "#4285f4"],
];

const icon = (label, color) => "data:image/svg+xml," + encodeURIComponent(
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><rect width="48" height="48" rx="12" fill="${color}"/>` +
  `<text x="24" y="31" font-family="Arial" font-size="20" font-weight="700" fill="#fff" text-anchor="middle">${label[0]}</text></svg>`);

const demoState = { limits: null, extras: {}, perms: { usage: false, overlay: false, notifications: false, battery: false } };

function demoDays(n) {
  const out = [];
  const pattern = { "com.instagram.android": [38, 25, 31, 18, 22, 14, 12], "com.zhiliaoapp.musically": [52, 41, 20, 22, 15, 19, 26],
    "com.google.android.youtube": [30, 44, 35, 28, 20, 25, 17], "com.snapchat.android": [9, 7, 12, 6, 8, 5, 4],
    "com.whatsapp": [20, 25, 18, 30, 22, 19, 11], "com.spotify.music": [60, 45, 50, 70, 40, 55, 30] };
  for (let i = n - 1; i >= 0; i--) {
    const d = new Date(); d.setDate(d.getDate() - i);
    const apps = {};
    for (const [p, v] of Object.entries(pattern)) apps[p] = (v[(n - 1 - i) % v.length] * 60_000) + ((p.length * 7919 * (i + 1)) % 50_000);
    out.push({ date: dayKey(d), apps });
  }
  return out;
}

const demo = {
  permissions: async () => ({ ...demoState.perms }),
  request: async which => { demoState.perms[which] = true; return { ...demoState.perms }; },
  apps: async () => DEMO_APPS.map(([pkg, label, c]) => ({ pkg, label, icon: icon(label, c), never: false })),
  config: async () => ({ limits: demoState.limits || {}, extras: { ...demoState.extras }, running: !!demoState.limits }),
  setLimits: async limits => { demoState.limits = { ...limits }; return { running: true }; },
  addExtra: async (pkg, minutes) => { demoState.extras[pkg] = (demoState.extras[pkg] || 0) + minutes; },
  usage: async days => demoDays(days),
  pendingExtra: async () => null,
};

export const Guard = isNative() ? native : demo;
export const knownLabel = pkg => SOCIAL[pkg] || pkg;
