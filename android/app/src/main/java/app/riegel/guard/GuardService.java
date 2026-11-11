package app.riegel.guard;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wacht dauerhaft im Vordergrund, solange mindestens eine App ein Tageslimit oder ein Zeitfenster hat.
 * Prüft bei eingeschaltetem Bildschirm etwa alle 500 ms, welche App vorne ist, rechnet alle 5 s
 * die Nutzung seit Mitternacht neu und legt über eine App, deren Zeit aufgebraucht ist oder die außerhalb
 * ihres Zeitfensters liegt, den Sperrbildschirm.
 */
public class GuardService extends Service {
    private static final String CH_RUN = "riegel_guard", CH_WARN = "riegel_warn";
    private static final int NID = 5711, NID_WARN = 5712;
    private static final long POLL_MS = 500, IDLE_MS = 2000, RECOMPUTE_MS = 5000, WARN_MS = 2 * 60_000;

    /** Läuft der Dienst gerade? (für die Anzeige in der App) */
    public static volatile boolean running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private GuardState state;
    private Set<String> never;
    private Map<String, Integer> limits;
    private Map<String, int[]> windows;

    private Usage.Result base;              // Nutzung seit Mitternacht, Stand baseAt
    private long baseAt, lastQuery;
    private String foreground, baseDay, notifText;

    private WindowManager wm;
    private View overlay;
    private TextView overlayTitle, overlayApp, overlayMore, overlayNote;
    private String overlayPkg;

    /** Startet den Dienst, wenn es Limits gibt und die Berechtigungen da sind; sonst stoppt er ihn. */
    public static boolean ensure(Context ctx) {
        boolean should = new GuardState(ctx).guardsAnything() && Apps.canGuard(ctx);
        if (should) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(ctx, new Intent(ctx, GuardService.class));
            } catch (Exception e) { return false; }   // z. B. Start aus dem Hintergrund verboten
        } else {
            ctx.stopService(new Intent(ctx, GuardService.class));
        }
        return should;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        state = new GuardState(this);
        never = Apps.neverBlocked(this);
        loadConfig();
        createChannels();
        // startForeground muss auch dann aufgerufen werden, wenn wir gleich wieder aufhören
        ServiceCompat.startForeground(this, NID, buildNotification(0),
            Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
        if ((limits.isEmpty() && windows.isEmpty()) || !Apps.canGuard(this)) { quit(); return START_NOT_STICKY; }
        base = null;                               // Limits können sich geändert haben: neu rechnen
        if (!running) {
            running = true;
            lastQuery = System.currentTimeMillis() - 10_000;
            handler.post(loop);
        }
        return START_STICKY;                       // läuft weiter, wenn Riegel weggewischt wird
    }

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {   // Bildschirm aus: nichts zu tun
                hideOverlay();
                handler.postDelayed(this, IDLE_MS);
                return;
            }
            if (!Apps.canGuard(GuardService.this)) { quit(); return; }
            String before = foreground;
            pollForeground(now);
            boolean switched = foreground != null && !foreground.equals(before) && guarded(foreground);
            if (base == null || switched || now - baseAt >= RECOMPUTE_MS || !GuardState.today().equals(baseDay)) recompute(now);
            check(now);
            handler.postDelayed(this, POLL_MS);
        }
    };

    // ---------- Messen ----------

    private UsageStatsManager usm() { return (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE); }

    /** Letzte App, die laut Nutzungsstatistik nach vorne kam (kleines Fenster, damit es schnell geht). */
    private void pollForeground(long now) {
        UsageStatsManager usm = usm();
        if (usm == null) return;
        UsageEvents events = usm.queryEvents(lastQuery - 2_000, now);   // Ereignisse kommen manchmal verspätet
        lastQuery = now;
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) foreground = e.getPackageName();
        }
    }

    /** Nutzung seit Mitternacht neu ausrechnen. Beim Tageswechsel wird gestern gesichert. */
    private void recompute(long now) {
        String today = GuardState.today();
        if (baseDay != null && !baseDay.equals(today)) {
            long midnight = midnight(now);
            state.saveHistory(baseDay, measure(this, midnight - 86_400_000L, midnight).ms);
        }
        base = measure(this, midnight(now), now);
        baseAt = now;
        baseDay = today;
        if (base.current != null) foreground = base.current;
        loadConfig();
        updateNotification();
    }

    private void loadConfig() {
        limits = state.limits();
        windows = state.windows();
        for (String n : never) { limits.remove(n); windows.remove(n); }
    }

    private boolean guarded(String pkg) { return limits.containsKey(pkg) || windows.containsKey(pkg); }

    /** Vordergrundzeit je App im Zeitraum [from, to]. Liest ab 3 h vorher, damit laufende Nutzung über die Grenze zählt. */
    public static Usage.Result measure(Context ctx, long from, long to) {
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        List<Usage.Ev> list = new ArrayList<>();
        if (usm != null) {
            UsageEvents events = usm.queryEvents(from - 3 * 3_600_000L, to);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                list.add(new Usage.Ev(e.getTimeStamp(), e.getEventType(), e.getPackageName(), e.getClassName() == null ? "" : e.getClassName()));
            }
        }
        return Usage.compute(list, from, to);
    }

    public static long midnight(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private long used(String pkg, long now) {
        long u = base.get(pkg);
        if (pkg.equals(base.current)) u += now - baseAt;     // läuft seit der letzten Rechnung weiter
        return u;
    }

    // ---------- Prüfen ----------

    private void check(long now) {
        String fg = foreground;
        if (fg == null || never.contains(fg)) { hideOverlay(); return; }
        int[] w = windows.get(fg);
        if (w != null) { checkWindow(fg, w, now); return; }
        if (!limits.containsKey(fg)) { hideOverlay(); return; }
        long allowed = state.allowedMin(fg) * 60_000L;
        long u = used(fg, now);
        if (u >= allowed) { showOverlay(fg, u); return; }
        hideOverlay();
        long left = allowed - u;
        if (left <= WARN_MS && !state.warned(fg)) {
            state.setWarned(fg);
            if (Apps.hasNotifications(this)) getSystemService(NotificationManager.class).notify(NID_WARN,
                new NotificationCompat.Builder(this, CH_WARN)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle(Texts.warn(label(fg), left))
                    .setContentText("Danach sperrt Riegel die App bis Mitternacht.")
                    .setAutoCancel(true)
                    .setTimeoutAfter(left)
                    .build());
        }
    }

    /** Zeitfenster: außerhalb ganz gesperrt (ohne „Mehr Zeit“), 2 min vor dem Ende eine Warnung. */
    private void checkWindow(String pkg, int[] w, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (!Window.in(minute, w[0], w[1])) { showWindowOverlay(pkg, w); return; }
        hideOverlay();
        long left = Window.msUntil(minute, c.get(Calendar.SECOND) * 1000L + c.get(Calendar.MILLISECOND), w[1]);
        if (left <= WARN_MS && !state.warned(pkg)) {
            state.setWarned(pkg);
            if (Apps.hasNotifications(this)) getSystemService(NotificationManager.class).notify(NID_WARN,
                new NotificationCompat.Builder(this, CH_WARN)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                    .setContentTitle(Window.warn(label(pkg), left, w[0]))
                    .setContentText("Danach sperrt Riegel die App bis " + Window.hm(w[0]) + ".")
                    .setAutoCancel(true)
                    .setTimeoutAfter(left)
                    .build());
        }
    }

    // ---------- Sperrbildschirm ----------

    /** Sperrbildschirm anlegen, falls noch nicht da. false, wenn das nicht geht (Berechtigung fehlt). */
    private boolean addOverlay() {
        if (!Apps.hasOverlay(this)) return false;
        if (overlay == null) {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlay = buildOverlay();
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
            lp.gravity = Gravity.CENTER;
            try { wm.addView(overlay, lp); } catch (Exception ex) { overlay = null; return false; }
        }
        return true;
    }

    private void showOverlay(String pkg, long usedMs) {
        if (!addOverlay()) return;
        overlayPkg = pkg;
        overlayTitle.setText("Zeit für heute aufgebraucht");
        overlayApp.setText(Texts.reached(label(pkg), usedMs, state.allowedMin(pkg)) + "\nMorgen ab 0:00 ist sie wieder frei.");
        overlayMore.setVisibility(View.VISIBLE);
        overlayNote.setVisibility(View.VISIBLE);
    }

    private void showWindowOverlay(String pkg, int[] w) {
        if (!addOverlay()) return;
        overlayPkg = pkg;
        overlayTitle.setText("Gerade gesperrt");
        overlayApp.setText(Window.blocked(label(pkg), w[0], w[1]) + "\nAb " + Window.hm(w[0]) + " ist sie wieder frei.");
        overlayMore.setVisibility(View.GONE);
        overlayNote.setVisibility(View.GONE);
    }

    private void hideOverlay() {
        if (overlay == null) return;
        try { wm.removeView(overlay); } catch (Exception ignored) {}
        overlay = null; overlayTitle = overlayApp = overlayMore = overlayNote = null; overlayPkg = null;
    }

    @SuppressLint("ClickableViewAccessibility")
    private View buildOverlay() {
        FrameLayout root = new FrameLayout(this) {
            // Zurück-Taste schluckt der Sperrbildschirm
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
                return super.dispatchKeyEvent(event);
            }
        };
        root.setBackgroundColor(0xFF111110);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(28);
        col.setPadding(pad, pad, pad, pad);
        root.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        TextView lock = text("🔒", 44, Color.WHITE, false);
        overlayTitle = text("", 28, Color.WHITE, true);
        overlayApp = text("", 16, 0xFFB9B3A8, false);
        col.addView(lock);
        col.addView(overlayTitle, margins(dp(10), 0));
        col.addView(overlayApp, margins(dp(14), dp(40)));

        TextView homeBtn = button("Zum Startbildschirm", 0xFFF07A3C, Color.WHITE, 0xFFF07A3C);
        homeBtn.setOnClickListener(v -> goHome());
        col.addView(homeBtn, margins(0, dp(12)));

        overlayMore = button("Mehr Zeit …", Color.TRANSPARENT, Color.WHITE, 0xFF4A4740);
        overlayMore.setOnClickListener(v -> askMore());
        col.addView(overlayMore, margins(0, 0));
        overlayNote = text("Mehr Zeit gibt es nur mit Code oder 5 Minuten Warten.", 13, 0xFF8A857B, false);
        col.addView(overlayNote, margins(dp(10), 0));
        return root;
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); } catch (Exception ignored) {}
        hideOverlay();
        foreground = null;
    }

    /** „Mehr Zeit …“: Riegel öffnen, dort kommt die Hürde für diese App. */
    private void askMore() {
        if (overlayPkg != null && limits.containsKey(overlayPkg)) state.setPendingExtra(overlayPkg);
        openRiegel();
    }

    private void openRiegel() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i == null) return;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(i); } catch (Exception ignored) {}
        hideOverlay();
        foreground = getPackageName();
    }

    private String label(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Exception e) { return "Diese App"; }
    }

    // ---------- Benachrichtigungen ----------

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel run = new NotificationChannel(CH_RUN, "Riegel wacht", NotificationManager.IMPORTANCE_LOW);
        run.setDescription("Dauerhafte Anzeige, damit Riegel im Hintergrund weiterläuft");
        run.setShowBadge(false);
        NotificationChannel warn = new NotificationChannel(CH_WARN, "Limit fast erreicht", NotificationManager.IMPORTANCE_HIGH);
        warn.setDescription("Meldet 2 Minuten vor dem Limit");
        nm.createNotificationChannel(run);
        nm.createNotificationChannel(warn);
    }

    private PendingIntent openIntent() {
        Intent i = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (i == null) i = new Intent();
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotification(long totalMs) {
        return new NotificationCompat.Builder(this, CH_RUN)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Riegel wacht")
            .setContentText(Texts.notification(totalMs, limits == null ? 0 : limits.size() + windows.size()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build();
    }

    private void updateNotification() {
        long total = 0;
        for (String pkg : limits.keySet()) total += base.get(pkg);
        for (String pkg : windows.keySet()) total += base.get(pkg);
        String t = Texts.notification(total, limits.size() + windows.size());
        if (t.equals(notifText)) return;
        notifText = t;
        getSystemService(NotificationManager.class).notify(NID, buildNotification(total));
    }

    private void quit() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        hideOverlay();
        super.onDestroy();
    }

    // ---------- kleine View-Helfer ----------

    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s, int bg, int fg, int stroke) {
        TextView b = text(s, 17, fg, true);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(dp(14));
        d.setStroke(dp(2), stroke);
        b.setBackground(d);
        b.setPadding(dp(16), dp(16), dp(16), dp(16));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout.LayoutParams margins(int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }
}
