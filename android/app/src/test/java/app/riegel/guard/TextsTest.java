package app.riegel.guard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Gleiche Fälle wie tests/logic.test.js (dur, leftText). */
public class TextsTest {
    @Test public void dauer() {
        assertEquals("0 min", Texts.dur(0));
        assertEquals("0 min", Texts.dur(-5));
        assertEquals("<1 min", Texts.dur(30_000));
        assertEquals("12 min", Texts.dur(12 * 60_000 + 59_000));
        assertEquals("1 h", Texts.dur(60 * 60_000));
        assertEquals("1 h 5 min", Texts.dur(65 * 60_000));
    }

    @Test public void rest() {
        assertEquals("1 min", Texts.left(1));
        assertEquals("1 min", Texts.left(60_000));
        assertEquals("2 min", Texts.left(60_001));
        assertEquals("0 min", Texts.left(0));
        assertEquals("2 h 5 min", Texts.left(125 * 60_000));
    }

    @Test public void meldungen() {
        assertEquals("Social Media heute: 34 min · 4 Apps begrenzt", Texts.notification(34 * 60_000, 4));
        assertEquals("Social Media heute: 0 min · 1 App begrenzt", Texts.notification(0, 1));
        assertEquals("Instagram: 20 von 20 min genutzt.", Texts.reached("Instagram", 20 * 60_000 + 5_000, 20));
        assertEquals("Instagram: 20 von 30 min genutzt.", Texts.reached("Instagram", 20 * 60_000, 30));
        assertEquals("Instagram: noch 2 min für heute", Texts.warn("Instagram", 90_000));
    }
}
