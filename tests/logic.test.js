// Selbsttest für die Logik von Riegel. Aufruf: node tests/logic.test.js
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import {
  SOCIAL, defaultLimits, loosened, dur, leftText, appState, makeCode, groups, codeMatches, codeProgress,
  CODE_CHARS, CODE_LEN, dayKey, weekday, series, perApp, streak, missing, OWN_PACKAGE, DEFAULT_LIMIT,
} from "../js/logic.js";

const app = (pkg, never = false) => ({ pkg, label: pkg, never });

// Startwerte: nur installierte bekannte Social-Media-Apps, nie Riegel selbst oder „never“
{
  const apps = [app("com.instagram.android"), app("com.ss.android.ugc.trill"), app("com.spotify.music"),
    app("com.whatsapp"), app(OWN_PACKAGE), app("com.google.android.youtube", true)];
  assert.deepEqual(defaultLimits(apps), { "com.instagram.android": DEFAULT_LIMIT, "com.ss.android.ugc.trill": DEFAULT_LIMIT });
  assert.deepEqual(defaultLimits([]), {});
  // Lite-Versionen bekommen ebenfalls ein Limit
  assert.deepEqual(defaultLimits([app("com.instagram.lite"), app("com.zhiliaoapp.musically.go")]),
    { "com.instagram.lite": DEFAULT_LIMIT, "com.zhiliaoapp.musically.go": DEFAULT_LIMIT });
  // YouTube ist bekannt, wird aber nicht vorgewählt
  assert.deepEqual(defaultLimits([app("com.google.android.youtube"), app("com.snapchat.android")]),
    { "com.snapchat.android": DEFAULT_LIMIT });
}

// Jede bekannte Social-Media-App steht in den <queries> des Manifests, sonst sieht Android sie nicht
{
  const manifest = readFileSync(new URL("../android/app/src/main/AndroidManifest.xml", import.meta.url), "utf8");
  for (const pkg of Object.keys(SOCIAL)) assert.ok(manifest.includes(`<package android:name="${pkg}" />`), pkg + " fehlt im Manifest");
}

// Lockern erkennt: höheres Limit, entferntes Limit. Strenger/neu begrenzt ist kein Lockern.
{
  const before = { a: 20, b: 30, c: 15 };
  assert.deepEqual(loosened(before, { a: 30, b: 30, c: 15 }), ["a"]);
  assert.deepEqual(loosened(before, { a: 20, b: 30 }), ["c"]);
  assert.deepEqual(loosened(before, { a: 10, b: 30, c: 15, d: 5 }), []);
  assert.deepEqual(loosened({}, { a: 5 }), []);
  assert.deepEqual(loosened({ a: 5, b: 5 }, {}), ["a", "b"]);
}

// Zeittexte
{
  assert.equal(dur(0), "0 min");
  assert.equal(dur(-5), "0 min");
  assert.equal(dur(NaN), "0 min");
  assert.equal(dur(30_000), "<1 min");
  assert.equal(dur(12 * 60_000 + 59_000), "12 min");
  assert.equal(dur(60 * 60_000), "1 h");
  assert.equal(dur(65 * 60_000), "1 h 5 min");
  assert.equal(leftText(1), "1 min");
  assert.equal(leftText(60_000), "1 min");
  assert.equal(leftText(60_001), "2 min");
  assert.equal(leftText(0), "0 min");
  assert.equal(leftText(125 * 60_000), "2 h 5 min");
}

// Stand einer App
{
  const s = appState(5 * 60_000, 20);
  assert.equal(s.over, false);
  assert.equal(s.text, "noch 15 min");
  assert.equal(s.ratio, 0.25);
  const o = appState(20 * 60_000, 20);
  assert.equal(o.over, true);
  assert.equal(o.text, "gesperrt bis Mitternacht");
  assert.equal(o.ratio, 1);
  const e = appState(20 * 60_000, 20, 10);   // mit Extra-Minuten wieder frei
  assert.equal(e.over, false);
  assert.equal(e.text, "noch 10 min");
}

// Code: Länge, Zeichenvorrat, Gruppen, Vergleich
{
  let i = 0;
  const seq = () => (i++ % CODE_CHARS.length) / CODE_CHARS.length;
  const code = makeCode(CODE_LEN, seq);
  assert.equal(code.length, CODE_LEN);
  assert.ok([...code].every(c => CODE_CHARS.includes(c)));
  assert.ok(!/[0o1li]/.test(CODE_CHARS));
  assert.equal(groups("abcdefghjk"), "abcd efgh jk");
  assert.equal(groups(code).split(" ").length, CODE_LEN / 4);
  assert.ok(codeMatches(groups(code).toUpperCase(), code));
  assert.ok(!codeMatches(code.slice(0, -1), code));
  assert.ok(!codeMatches("", code));
  const r = makeCode();
  assert.equal(r.length, CODE_LEN);
  assert.notEqual(makeCode(), makeCode());
  assert.deepEqual(codeProgress("ab cd", "abcdef"), { ok: 4, typo: false });
  assert.deepEqual(codeProgress("abx", "abcdef"), { ok: 2, typo: true });
  assert.deepEqual(codeProgress("", "abc"), { ok: 0, typo: false });
}

// Statistik
{
  assert.equal(dayKey(new Date(2026, 0, 5, 23, 59)), "2026-01-05");
  assert.equal(weekday("2026-11-10"), "Di");
  const m = 60_000;
  const days = [
    { date: "2026-11-08", apps: { a: 10 * m, b: 40 * m, x: 99 * m } },
    { date: "2026-11-09", apps: { a: 25 * m } },
    { date: "2026-11-10", apps: { b: 5 * m } },
  ];
  assert.deepEqual(series(days, ["a", "b"]).map(d => [d.label, d.total]), [["So", 50 * m], ["Mo", 25 * m], ["Di", 5 * m]]);
  const pa = perApp(days, ["a", "b"]);
  assert.deepEqual(pa.map(p => p.pkg), ["b", "a"]);
  assert.equal(pa[0].today, 5 * m);
  assert.equal(pa[0].sum, 45 * m);
  assert.equal(pa[1].avg, 35 * m / 3);
  // Serie bis gestern: 11-09 ok (a 25 ≤ 30), 11-08 b 40 > 30 → Serie 1; heute zählt nicht
  assert.equal(streak(days, { a: 30, b: 30 }), 1);
  assert.equal(streak(days, { a: 30, b: 45 }), 2);
  assert.equal(streak(days, { a: 20 }), 0);
  assert.equal(streak([], { a: 20 }), 0);
}

// Berechtigungen
{
  assert.deepEqual(missing({}), ["usage", "overlay"]);
  assert.deepEqual(missing({ usage: true, overlay: true }), []);
  assert.deepEqual(missing(null), ["usage", "overlay"]);
}

console.log("OK: Logik von Riegel geprüft");
