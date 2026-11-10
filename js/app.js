// Riegel – Oberfläche: Einrichtung, Heute + 7 Tage, Limits ändern, Mehr Zeit, Hürde (Code oder Warten).
import {
  PERM_STEPS, missing, limitable, defaultLimits, loosened, LIMIT_CHOICES, EXTRA_MIN, WAIT_MS,
  appState, allowedMs, dur, leftText, makeCode, groups, codeMatches, codeProgress, series, perApp, streak,
} from "./logic.js";
import { Guard, isNative, knownLabel } from "./native.js";

const $app = document.getElementById("app");
const S = { view: "load", apps: [], cfg: { limits: {}, extras: {} }, perms: {}, days: [], step: 0, draft: null, query: "" };
const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

const store = {
  get: k => { try { return localStorage.getItem("riegel." + k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem("riegel." + k, v); } catch {} },
};

const ICON = {
  lock: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="10" width="16" height="11" rx="2.5"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/><path d="M12 14.5v2.5"/></svg>',
  back: '<svg viewBox="0 0 24 24"><path d="M15 18l-6-6 6-6"/></svg>',
};

// ---------- Daten ----------

const appByPkg = pkg => S.apps.find(a => a.pkg === pkg);
const label = pkg => appByPkg(pkg)?.label || knownLabel(pkg);
const iconImg = pkg => {
  const src = appByPkg(pkg)?.icon;
  return src ? `<img src="${esc(src)}" alt="">` : '<span class="ph"></span>';
};
const limited = () => Object.keys(S.cfg.limits).sort((a, b) => label(a).localeCompare(label(b), "de"));
const today = () => S.days[S.days.length - 1]?.apps || {};

async function loadAll() {
  const [perms, cfg, apps, days] = await Promise.all([Guard.permissions(), Guard.config(), Guard.apps(), Guard.usage(7)]);
  Object.assign(S, { perms, cfg, apps: apps.sort((a, b) => a.label.localeCompare(b.label, "de")), days });
}

async function refreshUsage() {
  [S.days, S.cfg] = await Promise.all([Guard.usage(7), Guard.config()]);
}

// ---------- Navigation (Android-Zurück-Taste schließt Unterseiten) ----------

function go(view, push = true) {
  if (push && view !== "home" && S.view === "home") history.pushState({ v: view }, "");
  S.view = view;
  render();
  window.scrollTo(0, 0);
}
function home() {
  if (history.state?.v) history.back(); else go("home", false);
}
addEventListener("popstate", () => { stopWait(); go(S.setupDone ? "home" : S.view, false); });

// ---------- Rendering ----------

function render() {
  const v = S.view;
  $app.innerHTML =
    (!isNative() ? demoBanner() : "") +
    (v === "perm" ? viewPerm() : v === "pick" ? viewPick(true) : v === "limits" ? viewPick(false)
      : v === "extra" ? viewExtra() : v === "hurdle" ? viewHurdle() : v === "home" ? viewHome() : "");
  bind();
}

const demoBanner = () => `<div class="banner"><div><b>Vorschau mit Beispieldaten</b>
  Sperren kann Riegel nur als Android-App: <a href="https://github.com/tobiasabeltshauser08-hash/riegel/releases/latest/download/riegel-debug.apk">APK laden</a>
  · <a href="https://github.com/tobiasabeltshauser08-hash/riegel#readme">Anleitung</a>.</div></div>`;

const top = (sub, back = false) => `<header class="top">
  ${back ? `<button class="icon-btn" data-act="home" aria-label="Zurück">${ICON.back}</button>` : `<div class="emblem" aria-hidden="true">${ICON.lock}</div>`}
  <div class="grow"><h1 class="brand">Riegel</h1><p class="top-sub">${sub}</p></div></header>`;

// Berechtigungs-Assistent (einmalig; später nur über den Hinweis auf der Startseite)
function viewPerm() {
  const st = PERM_STEPS[S.step];
  const has = !!S.perms[st.key];
  const bars = PERM_STEPS.map((_, i) => `<i class="${i <= S.step ? "on" : ""}"></i>`).join("") + (S.setupDone ? "" : "<i></i>");
  return `${top(`Einrichten · Schritt ${S.step + 1} von ${PERM_STEPS.length + (S.setupDone ? 0 : 1)}`)}
  <div class="steps" aria-hidden="true">${bars}</div>
  <section class="card perm">
    <h2>${esc(st.title)} ${has ? '<span class="done-tag">✓</span>' : ""}</h2>
    <p class="lead">${esc(st.why)}</p>
    ${st.how && !has ? `<p class="how">${esc(st.how)}</p>` : ""}
    ${st.need && !has && S.step === 0 ? `<p class="hint">Android 13 oder neuer: Ist der Schalter ausgegraut, zuerst Einstellungen → Apps → Riegel → Menü ⋮ → „Eingeschränkte Einstellungen zulassen“.</p>` : ""}
    <div class="btns">
      ${has ? "" : `<button class="btn accent" data-act="grant">${esc(st.btn)}</button>`}
      <button class="btn ${has ? "" : "sec"}" data-act="next" ${st.need && !has ? 'data-need="1"' : ""}>${has ? "Weiter" : st.need ? "Später" : "Überspringen"}</button>
    </div>
  </section>
  ${st.need ? `<p class="hint">Ohne diese Berechtigung kann Riegel nicht sperren.</p>` : ""}`;
}

// Limits wählen: beim Einrichten (setup) frei, danach mit Hürde fürs Lockern
function viewPick(setup) {
  const d = S.draft;
  const q = S.query.trim().toLowerCase();
  const all = S.apps.filter(limitable);
  const extra = Object.keys(d).filter(p => !appByPkg(p)).map(p => ({ pkg: p, label: knownLabel(p) }));
  const list = [...all, ...extra]
    .filter(a => !q || a.label.toLowerCase().includes(q))
    .sort((a, b) => ((b.pkg in d) - (a.pkg in d)) || a.label.localeCompare(b.label, "de"));
  const opts = cur => `<option value="">Kein Limit</option>` +
    LIMIT_CHOICES.map(m => `<option value="${m}" ${cur === m ? "selected" : ""}>${m} min/Tag</option>`).join("");
  const loose = setup ? [] : loosened(S.cfg.limits, d);
  const rows = list.map(a => `<li class="row pick">${iconImg(a.pkg)}<span class="name">${esc(a.label)}</span>
      <select data-pkg="${esc(a.pkg)}" aria-label="Tageslimit für ${esc(a.label)}">${opts(d[a.pkg])}</select>
      ${loose.includes(a.pkg) ? `<span class="lim-hint">mehr Zeit → braucht Code oder 5 min Warten</span>` : ""}</li>`).join("");
  const n = Object.keys(d).length;
  return `${top(setup ? "Einrichten · Tageslimits" : "Limits ändern", !setup)}
  <div><h2 class="h-title">${setup ? "Wie viel Zeit pro Tag?" : "Tageslimits"}</h2>
  <p class="lead">${setup
    ? "Bekannte Social-Media-Apps sind schon vorgewählt. Ist die Zeit einer App für heute aufgebraucht, sperrt Riegel sie bis Mitternacht."
    : "Strenger stellen geht sofort. Mehr Zeit oder ein Limit entfernen geht nur mit Code oder 5 Minuten Warten."}</p></div>
  <input class="search" type="search" placeholder="App suchen" value="${esc(S.query)}" data-act="search" aria-label="App suchen">
  <ul class="rows card flush">${rows || '<li class="empty">Keine App gefunden.</li>'}</ul>
  <button class="btn accent block" data-act="${setup ? "start" : "save"}" ${setup && !n ? "disabled" : ""}>
    ${setup ? `Riegel starten · ${n === 1 ? "1 App" : n + " Apps"}` : loose.length ? "Speichern (mit Hürde)" : "Speichern"}</button>`;
}

function viewHome() {
  const pkgs = limited();
  const t = today();
  const used = pkgs.reduce((s, p) => s + (t[p] || 0), 0);
  const allowed = pkgs.reduce((s, p) => s + allowedMs(S.cfg.limits[p], S.cfg.extras[p] || 0), 0);
  const miss = missing(S.perms);
  const st = streak(S.days, S.cfg.limits);
  const watching = S.cfg.running && !miss.length && pkgs.length;
  const sub = !pkgs.length ? "keine App begrenzt" : watching ? `wacht · ${pkgs.length === 1 ? "1 App" : pkgs.length + " Apps"} begrenzt` : "schläft – Berechtigung fehlt";

  const rows = pkgs.map(p => {
    const lim = S.cfg.limits[p], ex = S.cfg.extras[p] || 0, u = t[p] || 0;
    const s = appState(u, lim, ex);
    return `<li class="row">${iconImg(p)}<span class="name">${esc(label(p))}</span>
      <span class="val num">${dur(u)} / ${lim + ex} min</span>
      <span class="meter ${s.over ? "over" : ""}" role="img" aria-label="${Math.round(s.ratio * 100)} Prozent verbraucht"><i style="width:${(s.ratio * 100).toFixed(1)}%"></i></span>
      <span class="meta ${s.over ? "over" : ""}"><span>${s.text}</span><span>${ex ? `+${ex} min extra` : ""}</span></span></li>`;
  }).join("");

  return `${top(sub)}
  ${miss.length ? `<button class="banner" data-act="fix"><span><b>Berechtigung fehlt – tippen zum Einrichten</b>
    Ohne ${miss.map(k => PERM_STEPS.find(s => s.key === k).title).join(" und ")} kann Riegel nicht sperren.</span></button>` : ""}
  <section class="card hero">
    <span class="status ${watching ? "" : "off"}">${watching ? "Riegel ist aktiv" : "Riegel ist nicht aktiv"}</span>
    <span class="big num">${dur(used)}</span>
    <span class="sub">Social Media heute${pkgs.length ? ` · ${leftText(Math.max(0, allowed - used))} übrig von ${dur(allowed)}` : ""}</span>
    ${st ? `<span class="sub">${st === 1 ? "Gestern" : `${st} Tage in Folge`} alle Limits eingehalten</span>` : ""}
  </section>
  <section>
    <div class="sec-h"><h2>Heute</h2><span>wieder frei um 0:00</span></div>
    <ul class="rows card flush">${rows || '<li class="empty">Noch keine App begrenzt.</li>'}</ul>
  </section>
  <div class="btns">
    <button class="btn sec" data-act="limits">Limits ändern</button>
    <button class="btn sec" data-act="extra" ${pkgs.length ? "" : "disabled"}>Mehr Zeit für heute</button>
  </div>
  ${viewWeek(pkgs)}`;
}

// Letzte 7 Tage: eine Reihe (Social-Media-Zeit der begrenzten Apps pro Tag), Ø-Linie, Tabelle als Alternative
function viewWeek(pkgs) {
  if (!pkgs.length || !S.days.length) return "";
  const ser = series(S.days, pkgs);
  const max = Math.max(...ser.map(d => d.total), 60_000);
  const sum = ser.reduce((s, d) => s + d.total, 0);
  const avg = sum / ser.length;
  const peak = ser.reduce((a, b) => (b.total > a.total ? b : a));
  const cols = ser.map((d, i) => `<div class="col ${i === ser.length - 1 ? "today" : ""}" tabindex="0" aria-label="${d.label}: ${dur(d.total)}">
      <span class="tip">${d.label} ${d.date.slice(8)}.${d.date.slice(5, 7)}. · ${dur(d.total)}</span>
      <div class="bar" style="height:${(d.total / max * 100).toFixed(1)}%"></div></div>`).join("");
  const lbls = ser.map((d, i) => `<span class="${i === ser.length - 1 ? "today" : ""}">${i === ser.length - 1 ? "Heute" : d.label}</span>`).join("");
  const apps = perApp(S.days, pkgs);
  const rows = apps.map(a => `<li class="row">${iconImg(a.pkg)}<span class="name">${esc(label(a.pkg))}</span>
      <span class="val num">Ø ${dur(a.avg)}</span>
      <span class="meta"><span>7 Tage: ${dur(a.sum)}</span><span>heute ${dur(a.today)}</span></span></li>`).join("");
  const table = `<table><thead><tr><th>Tag</th>${pkgs.map(p => `<th>${esc(label(p))}</th>`).join("")}<th>Summe</th></tr></thead><tbody>` +
    S.days.map((d, i) => `<tr><td>${ser[i].label} ${d.date.slice(8)}.</td>${pkgs.map(p => `<td class="num">${dur(d.apps?.[p] || 0)}</td>`).join("")}<td class="num">${dur(ser[i].total)}</td></tr>`).join("") +
    "</tbody></table>";
  return `<section>
    <div class="sec-h"><h2>Letzte 7 Tage</h2><span>begrenzte Apps</span></div>
    <div class="card">
      <div class="chart" role="img" aria-label="Social-Media-Zeit pro Tag, Durchschnitt ${dur(avg)}">
        <div class="plot">${cols}<div class="avg" style="bottom:${(avg / max * 100).toFixed(1)}%"><span>Ø ${dur(avg)}</span></div></div>
        <div class="lbls">${lbls}</div>
      </div>
      <div class="stats-mini">
        <div><b>${dur(avg)}</b><span>Ø pro Tag</span></div>
        <div><b>${dur(sum)}</b><span>Summe</span></div>
        <div><b>${dur(peak.total)}</b><span>meiste (${peak.label})</span></div>
      </div>
      <details class="table"><summary>Als Tabelle</summary>${table}</details>
    </div>
  </section>
  <section>
    <div class="sec-h"><h2>Pro App</h2><span>Durchschnitt pro Tag</span></div>
    <ul class="rows card flush">${rows}</ul>
  </section>`;
}

function viewExtra() {
  const rows = limited().map(p => {
    const s = appState(today()[p] || 0, S.cfg.limits[p], S.cfg.extras[p] || 0);
    return `<li class="row pick">${iconImg(p)}<span class="name">${esc(label(p))}<br><span class="hint">${s.text}</span></span>
      <button class="btn sec" data-act="extra-pkg" data-pkg="${esc(p)}">+${EXTRA_MIN} min</button></li>`;
  }).join("");
  return `${top("Mehr Zeit für heute", true)}
  <div><h2 class="h-title">Wirklich mehr Zeit?</h2>
  <p class="lead">Du bekommst ${EXTRA_MIN} Minuten extra, nur für heute. Davor steht die Hürde: Code abtippen oder 5 Minuten warten.</p></div>
  <ul class="rows card flush">${rows}</ul>`;
}

// ---------- Hürde ----------

let H = null;       // { title, changes, onPass, mode, code, input, endAt, timer }

function hurdle(title, changes, onPass) {
  H = { title, changes, onPass, mode: "code", code: makeCode(), input: "", endAt: 0, timer: 0 };
  go("hurdle");
}

// Code in Vierergruppen; die schon richtig getippten Zeichen grün
function codeHtml(p) {
  let k = 0, ok = "", rest = "";
  for (const ch of groups(H.code)) {
    if (ch === " ") { if (k < p.ok) ok += " "; else rest += " "; continue; }
    if (k < p.ok) ok += ch; else rest += ch;
    k++;
  }
  return (ok ? `<span class="ok">${ok}</span>` : "") + rest;
}

// Beim Tippen nur Anzeige und Hinweis erneuern (kein neues Rendern, damit die Tastatur offen bleibt)
function updateCode(input) {
  const p = codeProgress(H.input, H.code);
  $app.querySelector(".code").innerHTML = codeHtml(p);
  input.classList.toggle("typo", p.typo);
  $app.querySelector(".code-hint").textContent = `${p.ok} / ${H.code.length} Zeichen${p.typo ? " · Tippfehler" : ""}`;
}

function viewHurdle() {
  const p = codeProgress(H.input, H.code);
  const left = H.endAt ? Math.max(0, H.endAt - Date.now()) : WAIT_MS;
  const mm = Math.floor(Math.ceil(left / 1000) / 60), ss = Math.ceil(left / 1000) % 60;
  return `${top("Hürde", true)}
  <div><h2 class="h-title">${esc(H.title)}</h2>
    <ul class="change-list">${H.changes.map(c => `<li>${esc(c)}</li>`).join("")}</ul></div>
  <div class="tabs" role="group" aria-label="Art der Hürde">
    <button data-act="mode" data-mode="code" aria-pressed="${H.mode === "code"}">Code abtippen</button>
    <button data-act="mode" data-mode="wait" aria-pressed="${H.mode === "wait"}">5 min warten</button>
  </div>
  ${H.mode === "code" ? `<section class="card perm">
    <p class="lead">Tippe den Code ab. Einfügen geht nicht, Groß-/Kleinschreibung und Leerzeichen sind egal.</p>
    <div class="code" aria-label="Code">${codeHtml(p)}</div>
    <input class="code-in ${p.typo ? "typo" : ""}" data-act="code" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false"
      inputmode="text" value="${esc(H.input)}" aria-label="Code eingeben">
    <p class="hint num code-hint">${p.ok} / ${H.code.length} Zeichen${p.typo ? " · Tippfehler" : ""}</p>
  </section>` : `<section class="card wait">
    <span class="big num">${mm}:${String(ss).padStart(2, "0")}</span>
    <div class="ring"><i style="width:${((1 - left / WAIT_MS) * 100).toFixed(1)}%"></i></div>
    <p class="hint">Riegel muss die ganze Zeit offen bleiben. Wechselst du die App, beginnt die Zeit von vorn.</p>
    ${H.endAt ? "" : `<button class="btn accent block" data-act="wait-start">Warten starten</button>`}
  </section>`}
  <button class="btn sec block" data-act="home">Lieber doch nicht</button>`;
}

function waitTick() {
  if (!H?.endAt || S.view !== "hurdle") return;
  if (Date.now() >= H.endAt) { passHurdle(); return; }
  const big = $app.querySelector(".wait .big"), bar = $app.querySelector(".ring i");
  const left = H.endAt - Date.now(), sec = Math.ceil(left / 1000);
  if (big) big.textContent = `${Math.floor(sec / 60)}:${String(sec % 60).padStart(2, "0")}`;
  if (bar) bar.style.width = `${((1 - left / WAIT_MS) * 100).toFixed(1)}%`;
}
function stopWait() { if (H?.timer) clearInterval(H.timer); if (H) { H.timer = 0; H.endAt = 0; } }

async function passHurdle() {
  const fn = H.onPass;
  stopWait();
  H = null;
  await fn();
  await refreshUsage();
  home();
}

// ---------- Ereignisse ----------

function bind() {
  $app.querySelectorAll("[data-act]").forEach(el => {
    const act = el.dataset.act;
    if (act === "search") el.addEventListener("input", () => { S.query = el.value; const pos = el.selectionStart; render(); const s = $app.querySelector(".search"); s.focus(); s.setSelectionRange(pos, pos); });
    else if (act === "code") {
      const block = e => e.preventDefault();
      el.addEventListener("paste", block); el.addEventListener("drop", block);
      el.addEventListener("input", () => {
        H.input = el.value;
        if (codeMatches(H.input, H.code)) { passHurdle(); return; }
        updateCode(el);
      });
    } else if (el.tagName === "SELECT") { /* unten */ }
    else el.addEventListener("click", () => actions[act]?.(el));
  });
  $app.querySelectorAll("select[data-pkg]").forEach(sel => sel.addEventListener("change", () => {
    const v = sel.value;
    if (v) S.draft[sel.dataset.pkg] = Number(v); else delete S.draft[sel.dataset.pkg];
    render();
  }));
}

const actions = {
  home: () => { stopWait(); home(); },
  async grant() {
    S.perms = await Guard.request(PERM_STEPS[S.step].key);
    if (!isNative()) render();       // Android: Status wird geprüft, wenn Riegel wieder vorne ist
  },
  next() {
    if (S.step < PERM_STEPS.length - 1) { S.step++; render(); return; }
    if (S.setupDone) { home(); return; }
    S.draft = { ...defaultLimits(S.apps) };
    go("pick", false);
  },
  async start() {
    await Guard.setLimits(S.draft);
    S.setupDone = true;
    if (isNative()) store.set("setup", "1");
    S.cfg = await Guard.config();
    await refreshUsage();
    go("home", false);
  },
  fix() { S.step = Math.max(0, PERM_STEPS.findIndex(s => s.need && !S.perms[s.key])); go("perm"); },
  limits() { S.draft = { ...S.cfg.limits }; S.query = ""; go("limits"); },
  async save() {
    const draft = { ...S.draft };
    const loose = loosened(S.cfg.limits, draft);
    const apply = async () => { await Guard.setLimits(draft); S.cfg = await Guard.config(); toast("Limits gespeichert"); };
    if (!loose.length) { await apply(); home(); return; }
    const changes = loose.map(p => `${label(p)}: ${S.cfg.limits[p]} min → ${p in draft ? draft[p] + " min" : "kein Limit"}`);
    // Lockern: aus der Limits-Seite direkt zur Hürde (ohne zusätzlichen Verlaufseintrag)
    H = { title: "Limits lockern", changes, onPass: apply, mode: "code", code: makeCode(), input: "", endAt: 0, timer: 0 };
    S.view = "hurdle"; render(); scrollTo(0, 0);
  },
  extra() { go("extra"); },
  "extra-pkg"(el) { askExtra(el.dataset.pkg, true); },
  mode(el) {
    if (H.mode === el.dataset.mode) return;
    stopWait();
    H.mode = el.dataset.mode;
    render();
  },
  "wait-start"() {
    H.endAt = Date.now() + WAIT_MS;
    H.timer = setInterval(waitTick, 500);
    render();
  },
};

function askExtra(pkg, replace) {
  const ch = [`${label(pkg)}: +${EXTRA_MIN} min, nur heute`];
  const pass = async () => { await Guard.addExtra(pkg, EXTRA_MIN); toast(`${label(pkg)}: ${EXTRA_MIN} min extra`); };
  if (replace) {
    H = { title: "Mehr Zeit", changes: ch, onPass: pass, mode: "code", code: makeCode(), input: "", endAt: 0, timer: 0 };
    S.view = "hurdle"; render(); scrollTo(0, 0);
  } else hurdle("Mehr Zeit", ch, pass);
}

function toast(msg) {
  const t = document.createElement("div");
  t.className = "toast"; t.setAttribute("role", "status"); t.textContent = msg;
  document.body.append(t);
  setTimeout(() => t.remove(), 2600);
}

// Wer Riegel während des Wartens verlässt, fängt von vorn an. Zurück in Riegel: Berechtigungen und Zahlen neu laden.
document.addEventListener("visibilitychange", async () => {
  if (document.hidden) {
    if (H?.endAt) { stopWait(); H.reset = true; }
    return;
  }
  if (S.view === "load") return;
  S.perms = await Guard.permissions();
  await refreshUsage();
  await checkPending();
  if (H?.reset) { H.reset = false; if (S.view === "hurdle") { render(); toast("App gewechselt – Wartezeit beginnt von vorn"); } return; }
  if (["home", "perm", "extra"].includes(S.view)) render();
});

// „Mehr Zeit …“ auf dem Sperrbildschirm öffnet Riegel; dann direkt die Hürde für diese App zeigen
async function checkPending() {
  const pkg = await Guard.pendingExtra();
  if (pkg && S.setupDone && S.cfg.limits[pkg]) { if (S.view !== "home") go("home", false); askExtra(pkg, false); }
}

setInterval(async () => {
  if (document.hidden || S.view !== "home") return;
  await refreshUsage();
  render();
}, 30_000);

// ---------- Start ----------

(async function init() {
  await loadAll();
  S.setupDone = isNative() ? store.get("setup") === "1" : false;
  if (!S.setupDone) { S.step = 0; go("perm", false); return; }
  go("home", false);
  await checkPending();
})();

if ("serviceWorker" in navigator && !isNative()) navigator.serviceWorker.register("sw.js").catch(() => {});
