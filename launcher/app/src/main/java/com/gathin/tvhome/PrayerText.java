package com.gathin.tvhome;

import android.content.Context;
import android.text.format.DateFormat;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Locale;

/** Words and numbers for the prayer line on Home and the reminder card, in the launcher's language. */
final class PrayerText {

    private PrayerText() {
    }

    /** A minute of the day as the TV shows its clock: "18:56", or "6:56 PM" on a 12-hour TV. */
    static String clock(Context c, int minuteOfDay) {
        int m = Math.floorMod(minuteOfDay, 1440);
        String pattern = DateFormat.is24HourFormat(c) ? "HH:mm" : "h:mm a";
        return LocalTime.of(m / 60, m % 60).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()));
    }

    /** "1 h 12 min", "1 h" or "12 min". */
    static String duration(Context c, int minutes) {
        int h = minutes / 60, m = minutes % 60;
        if (h > 0 && m > 0) return c.getString(R.string.prayer_hours_minutes, h, m);
        return h > 0 ? c.getString(R.string.prayer_hours, h) : c.getString(R.string.prayer_minutes, m);
    }

    /** The UTC offset in minutes at this moment, from the TV's time zone. */
    static int utcOffsetMinutes(Calendar now) {
        return now.getTimeZone().getOffset(now.getTimeInMillis()) / 60_000;
    }

    static int minuteOfDay(Calendar now) {
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    }
}
