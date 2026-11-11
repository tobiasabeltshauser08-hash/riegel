package app.riegel.guard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dauerhaft gespeicherte Einstellungen: Tageslimits, Zeitfenster, Extra-Minuten von heute, Warnungen von heute,
 * Bitte „Mehr Zeit“ vom Sperrbildschirm und die Tageswerte der letzten Wochen.
 */
public final class GuardState {
    private static final String PREFS = "riegel_guard";
    private static final int KEEP_DAYS = 40;
    private final SharedPreferences p;

    public GuardState(Context ctx) {
        p = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String day(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date(ms)); }
    public static String today() { return day(System.currentTimeMillis()); }

    // ---------- Limits (Minuten pro Tag) ----------

    public Map<String, Integer> limits() { return readInts(p.getString("limits", "{}")); }

    public void setLimits(Map<String, Integer> limits) { p.edit().putString("limits", writeInts(limits)).apply(); }

    // ---------- Zeitfenster: pkg → {von, bis} in Minuten nach Mitternacht ----------

    public Map<String, int[]> windows() {
        Map<String, int[]> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(p.getString("windows", "{}"));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                JSONArray a = o.getJSONArray(k);
                m.put(k, new int[] { a.getInt(0), a.getInt(1) });
            }
        } catch (JSONException ignored) {}
        return m;
    }

    public void setWindows(Map<String, int[]> windows) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, int[]> e : windows.entrySet())
                o.put(e.getKey(), new JSONArray().put(e.getValue()[0]).put(e.getValue()[1]));
        } catch (JSONException ignored) {}
        p.edit().putString("windows", o.toString()).apply();
    }

    /** Gibt es irgendetwas zu bewachen? */
    public boolean guardsAnything() { return !limits().isEmpty() || !windows().isEmpty(); }

    // ---------- Extra-Minuten, gelten nur heute ----------

    public Map<String, Integer> extras() {
        if (!today().equals(p.getString("extrasDay", ""))) return new HashMap<>();
        return readInts(p.getString("extras", "{}"));
    }

    public void addExtra(String pkg, int minutes) {
        Map<String, Integer> m = extras();
        Integer old = m.get(pkg);
        m.put(pkg, (old == null ? 0 : old) + minutes);
        p.edit().putString("extrasDay", today()).putString("extras", writeInts(m)).apply();
    }

    public int allowedMin(String pkg) {
        Integer lim = limits().get(pkg), ex = extras().get(pkg);
        return (lim == null ? 0 : lim) + (ex == null ? 0 : ex);
    }

    // ---------- Warnung „noch 2 min“ höchstens einmal pro App und Tag ----------

    public boolean warned(String pkg) {
        return today().equals(p.getString("warnDay", "")) && p.getStringSet("warned", new HashSet<>()).contains(pkg);
    }

    public void setWarned(String pkg) {
        Set<String> s = today().equals(p.getString("warnDay", "")) ? new HashSet<>(p.getStringSet("warned", new HashSet<>())) : new HashSet<>();
        s.add(pkg);
        p.edit().putString("warnDay", today()).putStringSet("warned", s).apply();
    }

    // ---------- „Mehr Zeit …“ vom Sperrbildschirm ----------

    public void setPendingExtra(String pkg) { p.edit().putString("pending", pkg).apply(); }

    public String takePendingExtra() {
        String s = p.getString("pending", null);
        p.edit().remove("pending").apply();
        return s;
    }

    // ---------- Tageswerte (Sicherung, falls Android ältere Ereignisse schon gelöscht hat) ----------

    public Map<String, Long> history(String day) {
        try {
            JSONObject all = new JSONObject(p.getString("history", "{}"));
            JSONObject d = all.optJSONObject(day);
            Map<String, Long> m = new HashMap<>();
            if (d == null) return m;
            for (Iterator<String> it = d.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, d.getLong(k)); }
            return m;
        } catch (JSONException e) { return new HashMap<>(); }
    }

    public void saveHistory(String day, Map<String, Long> ms) {
        try {
            JSONObject all = new JSONObject(p.getString("history", "{}"));
            JSONObject d = new JSONObject();
            for (Map.Entry<String, Long> e : ms.entrySet()) if (e.getValue() >= 1000) d.put(e.getKey(), e.getValue());
            all.put(day, d);
            List<String> keys = new ArrayList<>();
            for (Iterator<String> it = all.keys(); it.hasNext(); ) keys.add(it.next());
            Collections.sort(keys);
            for (int i = 0; i < keys.size() - KEEP_DAYS; i++) all.remove(keys.get(i));
            p.edit().putString("history", all.toString()).apply();
        } catch (JSONException ignored) {}
    }

    // ---------- JSON-Helfer ----------

    private static Map<String, Integer> readInts(String json) {
        Map<String, Integer> m = new HashMap<>();
        try {
            JSONObject o = new JSONObject(json);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, o.getInt(k)); }
        } catch (JSONException ignored) {}
        return m;
    }

    private static String writeInts(Map<String, Integer> m) {
        JSONObject o = new JSONObject();
        try { for (Map.Entry<String, Integer> e : m.entrySet()) o.put(e.getKey(), (int) e.getValue()); } catch (JSONException ignored) {}
        return o.toString();
    }
}
