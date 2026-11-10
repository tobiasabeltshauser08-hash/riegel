package app.riegel.guard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rechnet aus Nutzungs-Ereignissen (UsageEvents), wie lange jede App im Vordergrund war.
 * Ohne Android-Klassen, damit es sich mit JUnit testen lässt (UsageTest).
 *
 * Eine App zählt ab ihrem ersten ACTIVITY_RESUMED, bis eine andere App nach vorne kommt,
 * alle ihre Activities pausiert sind oder der Bildschirm ausgeht.
 */
public final class Usage {
    private Usage() {}

    // gleiche Werte wie UsageEvents.Event.*
    public static final int RESUMED = 1, PAUSED = 2, SCREEN_NON_INTERACTIVE = 16, KEYGUARD_SHOWN = 17,
        ACTIVITY_STOPPED = 23, DEVICE_SHUTDOWN = 26;

    public static final class Ev {
        public final long ts; public final int type; public final String pkg, cls;
        public Ev(long ts, int type, String pkg, String cls) { this.ts = ts; this.type = type; this.pkg = pkg; this.cls = cls; }
    }

    public static final class Result {
        /** Millisekunden je Paket im Zeitraum. */
        public final Map<String, Long> ms = new HashMap<>();
        /** App, die am Ende des Zeitraums noch vorne ist (oder null). */
        public String current;

        public long get(String pkg) { Long v = ms.get(pkg); return v == null ? 0 : v; }
    }

    /** Ereignisse müssen zeitlich sortiert sein; sie dürfen vor from beginnen (laufende Nutzung über Mitternacht). */
    public static Result compute(Iterable<Ev> events, long from, long to) {
        Result r = new Result();
        String cur = null;
        long start = 0;
        Set<String> resumed = new HashSet<>();   // „paket/klasse“ der gerade aktiven Activities von cur
        for (Ev e : events) {
            if (e.ts > to) break;
            switch (e.type) {
                case RESUMED:
                    if (!e.pkg.equals(cur)) {
                        if (cur != null) add(r, cur, start, e.ts, from, to);
                        cur = e.pkg; start = e.ts; resumed.clear();
                    }
                    resumed.add(e.pkg + "/" + e.cls);
                    break;
                case PAUSED:
                case ACTIVITY_STOPPED:
                    if (e.pkg.equals(cur)) {
                        resumed.remove(e.pkg + "/" + e.cls);
                        if (resumed.isEmpty()) { add(r, cur, start, e.ts, from, to); cur = null; }
                    }
                    break;
                case SCREEN_NON_INTERACTIVE:
                case KEYGUARD_SHOWN:
                case DEVICE_SHUTDOWN:
                    if (cur != null) add(r, cur, start, e.ts, from, to);
                    cur = null; resumed.clear();
                    break;
                default:
            }
        }
        if (cur != null) { add(r, cur, start, to, from, to); r.current = cur; }
        return r;
    }

    private static void add(Result r, String pkg, long a, long b, long from, long to) {
        long s = Math.max(a, from), e = Math.min(b, to);
        if (e > s) r.ms.put(pkg, r.get(pkg) + (e - s));
    }
}
