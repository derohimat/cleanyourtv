package com.gathin.tvhome;

import java.time.LocalDate;
import java.time.chrono.HijrahDate;
import java.time.temporal.ChronoField;

/**
 * Hijri date for the optional line under the date on Home (config.xml, hijri_date), from java.time's
 * Umm al-Qura calendar (Android 8 and up, no extra data). Countries that start the month by sighting the
 * new moon, Indonesia among them, are often a day behind it, which config.xml hijri_offset corrects.
 */
final class Hijri {

    /** Month names in Indonesian spelling (KBBI), like the prayer names: the calendar line is for Indonesian homes. */
    static final String[] MONTHS = {
            "Muharram", "Safar", "Rabiul Awal", "Rabiul Akhir", "Jumadil Awal", "Jumadil Akhir",
            "Rajab", "Syakban", "Ramadan", "Syawal", "Zulkaidah", "Zulhijah"};

    private Hijri() {
    }

    /** {year, month 1 to 12, day} for a date shifted by {@code offsetDays}, or null outside the calendar's range. */
    static int[] of(LocalDate date, int offsetDays) {
        try {
            HijrahDate h = HijrahDate.from(date.plusDays(offsetDays));
            return new int[]{h.get(ChronoField.YEAR), h.get(ChronoField.MONTH_OF_YEAR), h.get(ChronoField.DAY_OF_MONTH)};
        } catch (RuntimeException e) {
            return null;   // past the end of the Umm al-Qura table, or a firmware without it: no line
        }
    }
}
