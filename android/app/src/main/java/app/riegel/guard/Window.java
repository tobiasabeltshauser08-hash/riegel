package app.riegel.guard;

/**
 * Zeitfenster: App nur von … bis … frei, sonst ganz gesperrt. Minuten nach Mitternacht;
 * bis < von heißt über Mitternacht (19:45–5:00). Gleiche Regeln wie js/logic.js (beide getestet).
 */
public final class Window {
    private Window() {}

    public static boolean in(int minute, int from, int to) {
        return from < to ? minute >= from && minute < to : from > to && (minute >= from || minute < to);
    }

    /** „5:00“, „19:45“ */
    public static String hm(int min) { return (min / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", min % 60); }

    /** Millisekunden bis zur nächsten Minute „target“ (0 < Ergebnis ≤ 24 h). */
    public static long msUntil(int minute, long msInMinute, int target) {
        int diff = ((target - minute) % 1440 + 1440) % 1440;
        if (diff == 0) diff = 1440;
        return diff * 60_000L - msInMinute;
    }

    /** „TikTok ist nur von 19:45 bis 5:00 frei.“ */
    public static String blocked(String label, int from, int to) {
        return label + " ist nur von " + hm(from) + " bis " + hm(to) + " frei.";
    }

    /** „TikTok: noch 2 min, dann gesperrt bis 19:45“ */
    public static String warn(String label, long leftMs, int from) {
        return label + ": noch " + Texts.left(leftMs) + ", dann gesperrt bis " + hm(from);
    }
}
