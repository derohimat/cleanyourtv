package com.gathin.tvhome;

import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the prayer times: Kemenag's published tables, Imsak, the next prayer, the methods. */
public class PrayerTimesTest {

    private static final double BANDUNG_LAT = -6.9175, BANDUNG_LON = 107.6191, BANDUNG_ELEVATION = 1150;
    private static final double JAKARTA_LAT = -6.1754, JAKARTA_LON = 106.8272, JAKARTA_ELEVATION = 25;
    private static final int WIB = 7 * 60;

    /**
     * Imsak, Subuh, Terbit, Dzuhur, Ashar, Maghrib, Isya from Kemenag's schedule (Bimas Islam), as served by
     * api.myquran.com/v2/sholat/jadwal/1219 (Kota Bandung) and /1301 (Kota Jakarta), fetched 2026-10-20.
     */
    private static void assertKemenag(String expected, double lat, double lon, double elevation, LocalDate date) {
        int[] times = PrayerTimes.compute(date, lat, lon, elevation, WIB, PrayerTimes.Method.KEMENAG);
        String[] want = expected.split(" ");
        for (int i = 0; i < want.length; i++) {
            int minute = Integer.parseInt(want[i].substring(0, 2)) * 60 + Integer.parseInt(want[i].substring(3));
            int off = times[i] - minute;
            assertTrue(date + " " + want[i] + " came out " + times[i] / 60 + ":" + times[i] % 60,
                    Math.abs(off) <= 1);
        }
    }

    @Test
    public void bandungMatchesKemenagWithinAMinute() {
        assertKemenag("04:00 04:10 05:19 11:38 14:45 17:50 18:56",
                BANDUNG_LAT, BANDUNG_LON, BANDUNG_ELEVATION, LocalDate.of(2026, 10, 20));
        assertKemenag("04:28 04:38 05:52 11:55 15:15 17:51 19:01",
                BANDUNG_LAT, BANDUNG_LON, BANDUNG_ELEVATION, LocalDate.of(2026, 6, 21));
        assertKemenag("03:58 04:08 05:24 11:51 15:17 18:11 19:22",
                BANDUNG_LAT, BANDUNG_LON, BANDUNG_ELEVATION, LocalDate.of(2026, 12, 21));
        // Terbit here was 2 minutes late with a 2-minute margin; fitted over all of 2026, it is 3.
        assertKemenag("03:59 04:09 05:18 11:38 14:46 17:50 18:56",
                BANDUNG_LAT, BANDUNG_LON, BANDUNG_ELEVATION, LocalDate.of(2026, 10, 21));
    }

    @Test
    public void jakartaMatchesKemenagWithinAMinute() {
        assertKemenag("04:03 04:13 05:27 11:41 14:49 17:49 18:58",
                JAKARTA_LAT, JAKARTA_LON, JAKARTA_ELEVATION, LocalDate.of(2026, 10, 20));
        assertKemenag("04:30 04:40 05:58 11:58 15:19 17:51 19:05",
                JAKARTA_LAT, JAKARTA_LON, JAKARTA_ELEVATION, LocalDate.of(2026, 6, 21));
    }

    @Test
    public void imsakIsTenMinutesBeforeSubuhAndTheDayRunsInOrder() {
        int[] t = PrayerTimes.compute(LocalDate.of(2026, 10, 20), BANDUNG_LAT, BANDUNG_LON, BANDUNG_ELEVATION, WIB,
                PrayerTimes.Method.KEMENAG);
        assertEquals(t[PrayerTimes.SUBUH] - 10, t[PrayerTimes.IMSAK]);
        for (int i = 1; i < t.length; i++) assertTrue(t[i - 1] < t[i]);
    }

    @Test
    public void elevationMovesOnlySunriseAndSunset() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        int[] low = PrayerTimes.compute(date, BANDUNG_LAT, BANDUNG_LON, 0, WIB, PrayerTimes.Method.KEMENAG);
        int[] high = PrayerTimes.compute(date, BANDUNG_LAT, BANDUNG_LON, 1000, WIB, PrayerTimes.Method.KEMENAG);
        assertTrue(high[PrayerTimes.TERBIT] < low[PrayerTimes.TERBIT]);
        assertTrue(high[PrayerTimes.MAGHRIB] > low[PrayerTimes.MAGHRIB]);
        assertEquals(low[PrayerTimes.SUBUH], high[PrayerTimes.SUBUH]);
        assertEquals(low[PrayerTimes.DZUHUR], high[PrayerTimes.DZUHUR]);
        assertEquals(low[PrayerTimes.ISYA], high[PrayerTimes.ISYA]);
    }

    @Test
    public void muhammadiyahSubuhIsAboutEightMinutesLater() {
        LocalDate date = LocalDate.of(2026, 10, 20);
        int[] kemenag = PrayerTimes.compute(date, BANDUNG_LAT, BANDUNG_LON, BANDUNG_ELEVATION, WIB,
                PrayerTimes.Method.KEMENAG);
        int[] muhammadiyah = PrayerTimes.compute(date, BANDUNG_LAT, BANDUNG_LON, BANDUNG_ELEVATION, WIB,
                PrayerTimes.Method.MUHAMMADIYAH);
        int later = muhammadiyah[PrayerTimes.SUBUH] - kemenag[PrayerTimes.SUBUH];
        assertTrue("Subuh " + later + " min later", later >= 7 && later <= 10);
        assertEquals(kemenag[PrayerTimes.ISYA], muhammadiyah[PrayerTimes.ISYA]);
        assertEquals(PrayerTimes.Method.MUHAMMADIYAH, PrayerTimes.Method.parse(" Muhammadiyah "));
        assertEquals(PrayerTimes.Method.KEMENAG, PrayerTimes.Method.parse("anything"));
    }

    @Test
    public void nextPrayerSkipsImsakAndTerbitAndEndsAfterIsya() {
        int[] t = PrayerTimes.compute(LocalDate.of(2026, 10, 20), BANDUNG_LAT, BANDUNG_LON, BANDUNG_ELEVATION, WIB,
                PrayerTimes.Method.KEMENAG);
        assertEquals(PrayerTimes.SUBUH, PrayerTimes.next(t, 0));
        assertEquals(PrayerTimes.SUBUH, PrayerTimes.next(t, t[PrayerTimes.IMSAK]));
        assertEquals(PrayerTimes.DZUHUR, PrayerTimes.next(t, t[PrayerTimes.SUBUH]));     // Subuh has begun
        assertEquals(PrayerTimes.DZUHUR, PrayerTimes.next(t, t[PrayerTimes.TERBIT]));
        assertEquals(PrayerTimes.ISYA, PrayerTimes.next(t, t[PrayerTimes.ISYA] - 1));
        assertEquals(-1, PrayerTimes.next(t, t[PrayerTimes.ISYA]));
    }

    @Test
    public void noTimesWhereTheSunNeverGetsLowEnough() {
        // Midsummer near the Arctic circle: the sun never sinks 18° below the horizon.
        assertNull(PrayerTimes.compute(LocalDate.of(2026, 6, 21), 64.1, -21.9, 0, 0, PrayerTimes.Method.KEMENAG));
    }
}
