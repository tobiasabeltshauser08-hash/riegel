package app.riegel.guard;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;


import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Schnittstelle zur Web-App (js/native.js): Berechtigungen, Liste der Apps, Limits, Extra-Zeit, Nutzungsstatistik.
 * Aufruf aus JS über Capacitor.nativePromise("Guard", methode, optionen).
 */
@CapacitorPlugin(
    name = "Guard",
    permissions = { @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS }) }
)
public class GuardPlugin extends Plugin {

    @PluginMethod
    public void permissions(PluginCall call) { call.resolve(permissionStatus()); }

    private JSObject permissionStatus() {
        JSObject r = new JSObject();
        r.put("usage", Apps.hasUsageAccess(getContext()));
        r.put("overlay", Apps.hasOverlay(getContext()));
        r.put("notifications", Apps.hasNotifications(getContext()));
        r.put("battery", Apps.ignoresBatteryOptimizations(getContext()));
        return r;
    }

    /** Laufzeit-Berechtigung über den System-Dialog, Sonderrechte über die passende Systemseite. */
    @PluginMethod
    public void request(PluginCall call) {
        String which = call.getString("which", "");
        String pkg = getContext().getPackageName();
        switch (which) {
            case "notifications":
                if (Apps.hasNotifications(getContext())) { call.resolve(permissionStatus()); return; }
                requestPermissionForAlias("notifications", call, "permissionResult");
                return;
            case "usage":
                open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS), call);
                return;
            case "overlay":
                open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + pkg)), call);
                return;
            case "battery":
                open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)), call);
                return;
            case "app":
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)), call);
                return;
            default:
                call.reject("Unbekannte Berechtigung: " + which);
        }
    }

    @PermissionCallback
    private void permissionResult(PluginCall call) { call.resolve(permissionStatus()); }

    private void open(Intent i, PluginCall call) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(i);
        } catch (Exception e) {
            // manche Hersteller kennen die genaue Seite nicht: dann die App-Infos öffnen
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(fallback);
        }
        call.resolve(permissionStatus());
    }

    /** Alle Apps mit Symbol auf dem Startbildschirm: { pkg, label, icon (data:-URL), never }. */
    @PluginMethod
    public void apps(PluginCall call) {
        new Thread(() -> {
            PackageManager pm = getContext().getPackageManager();
            Set<String> never = Apps.neverBlocked(getContext());
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            Set<String> seen = new HashSet<>();
            JSArray list = new JSArray();
            for (ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
                String pkg = r.activityInfo.packageName;
                if (!seen.add(pkg)) continue;
                JSObject a = new JSObject();
                a.put("pkg", pkg);
                a.put("label", r.loadLabel(pm).toString());
                a.put("never", never.contains(pkg));
                a.put("icon", iconData(r.loadIcon(pm)));
                list.put(a);
            }
            // Bekannte Social-Media-Apps, die der Launcher nicht gemeldet hat (z. B. bei manchen Herstellern)
            JSArray known = call.getArray("known", new JSArray());
            for (int i = 0; i < known.length(); i++) {
                String pkg = known.optString(i, "");
                if (pkg.isEmpty() || seen.contains(pkg)) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    if (!ai.enabled) continue;
                    seen.add(pkg);
                    JSObject a = new JSObject();
                    a.put("pkg", pkg);
                    a.put("label", pm.getApplicationLabel(ai).toString());
                    a.put("never", never.contains(pkg));
                    a.put("icon", iconData(pm.getApplicationIcon(ai)));
                    list.put(a);
                } catch (PackageManager.NameNotFoundException ignored) {}
            }
            JSObject res = new JSObject();
            res.put("apps", list);
            call.resolve(res);
        }).start();
    }

    private String iconData(Drawable d) {
        try {
            int size = 72;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    /** { limits: {pkg: min}, windows: {pkg: [von, bis]}, extras: {pkg: min} (nur heute), running }. Startet den Wächter, falls er laufen sollte. */
    @PluginMethod
    public void config(PluginCall call) {
        GuardState s = new GuardState(getContext());
        boolean should = GuardService.ensure(getContext());
        JSObject r = new JSObject();
        r.put("limits", toJs(s.limits()));
        JSObject w = new JSObject();
        for (Map.Entry<String, int[]> e : s.windows().entrySet()) w.put(e.getKey(), new JSArray().put(e.getValue()[0]).put(e.getValue()[1]));
        r.put("windows", w);
        r.put("extras", toJs(s.extras()));
        r.put("running", should);
        call.resolve(r);
    }

    /**
     * Limits speichern ({ limits: {pkg: min}, windows: {pkg: [von, bis]} }) und den Wächter starten bzw. stoppen.
     * Eine App hat entweder ein Tageslimit oder ein Zeitfenster; fehlt „windows“, bleiben die Fenster wie sie sind.
     */
    @PluginMethod
    public void setLimits(PluginCall call) {
        JSObject in = call.getObject("limits", new JSObject());
        Set<String> never = Apps.neverBlocked(getContext());
        Map<String, Integer> m = new HashMap<>();
        for (Iterator<String> it = in.keys(); it.hasNext(); ) {
            String pkg = it.next();
            int min = in.optInt(pkg, 0);
            if (min > 0 && !never.contains(pkg)) m.put(pkg, min);
        }
        GuardState st = new GuardState(getContext());
        st.setLimits(m);
        JSObject win = call.getObject("windows", null);
        if (win != null) {
            Map<String, int[]> w = new HashMap<>();
            for (Iterator<String> it = win.keys(); it.hasNext(); ) {
                String pkg = it.next();
                org.json.JSONArray a = win.optJSONArray(pkg);
                if (a == null || a.length() != 2 || never.contains(pkg) || m.containsKey(pkg)) continue;
                int from = a.optInt(0, -1), to = a.optInt(1, -1);
                if (from >= 0 && from < 1440 && to >= 0 && to < 1440 && from != to) w.put(pkg, new int[] { from, to });
            }
            st.setWindows(w);
        }
        JSObject r = new JSObject();
        r.put("running", GuardService.ensure(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void addExtra(PluginCall call) {
        String pkg = call.getString("pkg");
        int minutes = call.getInt("minutes", 0);
        if (pkg == null || minutes <= 0 || minutes > 60) { call.reject("Ungültige Extra-Zeit"); return; }
        new GuardState(getContext()).addExtra(pkg, minutes);
        call.resolve();
    }

    @PluginMethod
    public void pendingExtra(PluginCall call) {
        JSObject r = new JSObject();
        r.put("pkg", new GuardState(getContext()).takePendingExtra());
        call.resolve(r);
    }

    /**
     * Nutzung der letzten { days } Tage (ältester zuerst, heute zuletzt): [{ date, apps: {pkg: ms} }].
     * Abgeschlossene Tage werden gesichert; hat Android die Ereignisse eines Tages schon gelöscht, kommt die Sicherung.
     */
    @PluginMethod
    public void usage(PluginCall call) {
        int days = Math.max(1, Math.min(30, call.getInt("days", 7)));
        new Thread(() -> {
            GuardState s = new GuardState(getContext());
            long now = System.currentTimeMillis();
            long todayStart = GuardService.midnight(now);
            JSArray out = new JSArray();
            boolean access = Apps.hasUsageAccess(getContext());
            for (int i = days - 1; i >= 0; i--) {
                // über Kalender statt 24 h rechnen, wegen Sommer-/Winterzeit
                long from = GuardService.midnight(todayStart - i * 86_400_000L + 3_600_000L * 12);
                long to = i == 0 ? now : GuardService.midnight(from + 86_400_000L + 3_600_000L * 12);
                String day = GuardState.day(from);
                Map<String, Long> ms = access ? GuardService.measure(getContext(), from, to).ms : new HashMap<>();
                Map<String, Long> saved = s.history(day);
                if (i > 0) {
                    if (sum(ms) < sum(saved)) ms = saved; else if (!ms.isEmpty()) s.saveHistory(day, ms);
                }
                JSObject apps = new JSObject();
                for (Map.Entry<String, Long> e : ms.entrySet()) if (e.getValue() >= 1000) apps.put(e.getKey(), (long) e.getValue());
                JSObject d = new JSObject();
                d.put("date", day);
                d.put("apps", apps);
                out.put(d);
            }
            JSObject r = new JSObject();
            r.put("days", out);
            call.resolve(r);
        }).start();
    }

    private static long sum(Map<String, Long> m) { long s = 0; for (long v : m.values()) s += v; return s; }

    private static JSObject toJs(Map<String, Integer> m) {
        JSObject o = new JSObject();
        for (Map.Entry<String, Integer> e : m.entrySet()) o.put(e.getKey(), (int) e.getValue());
        return o;
    }
}
