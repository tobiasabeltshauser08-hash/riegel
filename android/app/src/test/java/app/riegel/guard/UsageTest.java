package app.riegel.guard;

import static app.riegel.guard.Usage.*;
import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class UsageTest {
    private static final String IG = "com.instagram.android", HOME = "launcher", TT = "tiktok";
    private static Ev ev(long ts, int type, String pkg) { return new Ev(ts, type, pkg, "Main"); }
    private static Ev ev(long ts, int type, String pkg, String cls) { return new Ev(ts, type, pkg, cls); }

    @Test public void zaehltBisZurNaechstenApp() {
        List<Ev> e = Arrays.asList(ev(100, RESUMED, IG), ev(400, RESUMED, HOME), ev(500, RESUMED, TT), ev(900, PAUSED, TT));
        Result r = compute(e, 0, 1000);
        assertEquals(300, r.get(IG));
        assertEquals(100, r.get(HOME));
        assertEquals(400, r.get(TT));
        assertNull(r.current);
    }

    @Test public void laufendeAppZaehltBisJetzt() {
        Result r = compute(Arrays.asList(ev(100, RESUMED, IG)), 0, 1000);
        assertEquals(900, r.get(IG));
        assertEquals(IG, r.current);
    }

    @Test public void bildschirmAusBeendet() {
        List<Ev> e = Arrays.asList(ev(100, RESUMED, IG), ev(300, SCREEN_NON_INTERACTIVE, "android"), ev(800, KEYGUARD_SHOWN, "android"));
        Result r = compute(e, 0, 1000);
        assertEquals(200, r.get(IG));
        assertNull(r.current);
    }

    @Test public void wechselZwischenActivitiesDerselbenAppUnterbrichtNicht() {
        // B kommt vor dem PAUSED von A (so meldet Android es oft)
        List<Ev> e = Arrays.asList(ev(100, RESUMED, IG, "A"), ev(200, RESUMED, IG, "B"), ev(210, PAUSED, IG, "A"), ev(600, PAUSED, IG, "B"));
        Result r = compute(e, 0, 1000);
        assertEquals(500, r.get(IG));
    }

    @Test public void schneidetAufDenZeitraumZu() {
        // seit 22 Uhr gestern offen, gemessen ab Mitternacht (from = 1000)
        List<Ev> e = Arrays.asList(ev(200, RESUMED, IG), ev(1500, RESUMED, HOME));
        Result r = compute(e, 1000, 3000);
        assertEquals(500, r.get(IG));
        assertEquals(1500, r.get(HOME));
    }

    @Test public void ereignisseNachDemEndeZaehlenNicht() {
        List<Ev> e = Arrays.asList(ev(100, RESUMED, IG), ev(5000, RESUMED, HOME));
        Result r = compute(e, 0, 1000);
        assertEquals(900, r.get(IG));
        assertEquals(0, r.get(HOME));
    }

    @Test public void pauseEinerAnderenAppStoertNicht() {
        List<Ev> e = Arrays.asList(ev(100, RESUMED, IG), ev(150, PAUSED, HOME), ev(300, ACTIVITY_STOPPED, HOME));
        assertEquals(900, compute(e, 0, 1000).get(IG));
    }

    @Test public void leer() {
        Result r = compute(Arrays.<Ev>asList(), 0, 1000);
        assertTrue(r.ms.isEmpty());
        assertNull(r.current);
    }
}
