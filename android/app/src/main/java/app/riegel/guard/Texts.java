package app.riegel.guard;

/** Texte für Benachrichtigung und Sperrbildschirm. Gleiche Regeln wie js/logic.js (beide getestet). */
public final class Texts {
    private Texts() {}

    /** Dauer abgerundet: „0 min“, „<1 min“, „12 min“, „1 h“, „1 h 5 min“. */
    public static String dur(long ms) {
        if (ms <= 0) return "0 min";
        if (ms < 60_000) return "<1 min";
        long min = ms / 60_000;
        if (min < 60) return min + " min";
        long h = min / 60, m = min % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    /** Restzeit aufgerundet: „2 min“, „1 h 5 min“. */
    public static String left(long ms) {
        if (ms <= 0) return "0 min";
        long min = (ms + 59_999) / 60_000;
        if (min < 60) return min + " min";
        long h = min / 60, m = min % 60;
        return m == 0 ? h + " h" : h + " h " + m + " min";
    }

    public static String apps(int n) { return n == 1 ? "1 App" : n + " Apps"; }

    /** „Social Media heute: 34 min · 4 Apps begrenzt“ */
    public static String notification(long totalMs, int apps) {
        return "Social Media heute: " + dur(totalMs) + " · " + apps(apps) + " begrenzt";
    }

    /** „Instagram: 20 von 20 min genutzt.“ */
    public static String reached(String label, long usedMs, int allowedMin) {
        return label + ": " + Math.min(usedMs / 60_000, allowedMin) + " von " + allowedMin + " min genutzt.";
    }

    /** „Instagram: noch 2 min für heute“ */
    public static String warn(String label, long leftMs) {
        return label + ": noch " + left(leftMs) + " für heute";
    }
}
