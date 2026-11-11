package app.riegel.guard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Gleiche Fälle wie tests/logic.test.js (inWindow, hm). */
public class WindowTest {
    private static final int FROM = 19 * 60 + 45, TO = 5 * 60;

    @Test public void ueberMitternacht() {
        assertFalse(Window.in(19 * 60 + 44, FROM, TO));
        assertTrue(Window.in(19 * 60 + 45, FROM, TO));
        assertTrue(Window.in(0, FROM, TO));
        assertTrue(Window.in(4 * 60 + 59, FROM, TO));
        assertFalse(Window.in(5 * 60, FROM, TO));
        assertFalse(Window.in(12 * 60, FROM, TO));
    }

    @Test public void amTag() {
        assertTrue(Window.in(10 * 60, 9 * 60, 17 * 60));
        assertFalse(Window.in(17 * 60, 9 * 60, 17 * 60));
        assertFalse(Window.in(600, 600, 600));
    }

    @Test public void texte() {
        assertEquals("5:00", Window.hm(300));
        assertEquals("19:45", Window.hm(1185));
        assertEquals("TikTok ist nur von 19:45 bis 5:00 frei.", Window.blocked("TikTok", FROM, TO));
        assertEquals("TikTok: noch 2 min, dann gesperrt bis 19:45", Window.warn("TikTok", 90_000, FROM));
    }

    @Test public void restzeit() {
        assertEquals(60_000L, Window.msUntil(299, 0, 300));
        assertEquals(30_000L, Window.msUntil(299, 30_000, 300));
        assertEquals(24 * 60 * 60_000L, Window.msUntil(300, 0, 300));
        assertEquals(10 * 60_000L, Window.msUntil(1435, 0, 5));
    }
}
