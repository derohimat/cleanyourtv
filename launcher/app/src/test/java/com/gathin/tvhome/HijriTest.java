package com.gathin.tvhome;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

/** Plain JVM tests for the Hijri date: Umm al-Qura dates, the day offset and the calendar's range. */
public class HijriTest {

    @Test
    public void ummAlQuraDates() {
        assertArrayEquals(new int[]{1448, 5, 9}, Hijri.of(LocalDate.of(2026, 10, 20), 0));
        assertArrayEquals(new int[]{1447, 9, 1}, Hijri.of(LocalDate.of(2026, 2, 18), 0));   // 1 Ramadan
        assertArrayEquals(new int[]{1448, 1, 1}, Hijri.of(LocalDate.of(2026, 6, 16), 0));   // 1 Muharram
    }

    @Test
    public void theOffsetShiftsByWholeDays() {
        // Indonesia (sighting) was a day behind Umm al-Qura: 8 Jumadil Awal 1448 on 2026-10-20.
        assertArrayEquals(new int[]{1448, 5, 8}, Hijri.of(LocalDate.of(2026, 10, 20), -1));
        assertArrayEquals(new int[]{1447, 8, 29}, Hijri.of(LocalDate.of(2026, 2, 18), -1));
        assertArrayEquals(new int[]{1448, 5, 10}, Hijri.of(LocalDate.of(2026, 10, 20), 1));
    }

    @Test
    public void datesOutsideTheTableGiveNoLine() {
        assertNull(Hijri.of(LocalDate.of(2200, 1, 1), 0));
    }
}
