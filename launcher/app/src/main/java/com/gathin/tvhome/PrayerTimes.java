package com.gathin.tvhome;

import java.time.LocalDate;

/**
 * Daily prayer times for the optional prayer line on Home and PrayerReminderService
 * (config.xml, prayer_times).
 *
 * Pure maths, no Android types and no clock of its own: the caller passes the date, the place and the
 * UTC offset, so the times can be tested on the JVM. The sun's position comes from the usual low-precision
 * formulas (declination and equation of time from the Julian day, good to well under a minute), and each
 * time is the moment the sun reaches a given angle below the horizon, refined in a second pass.
 *
 * The rules are those of Indonesia's Ministry of Religious Affairs (Kemenag RI): Subuh at 20° below the
 * horizon, Isya at 18°, Ashar when a shadow is its object's length plus the noon shadow; then the ihtiyat,
 * a small safety margin (2 minutes, 3 for Dzuhur, 3 earlier for Terbit), rounded up to the minute, and
 * Imsak 10 minutes before Subuh. Sunrise and sunset dip further with the observer's elevation. With these,
 * Kemenag's published tables come out within a minute, most days to the minute, for Kota Bandung at 1150 m and
 * Kota Jakarta at 25 m: the elevations their sunrise and sunset imply over all of 2026 (see PrayerTimesTest).
 */
final class PrayerTimes {

    static final int IMSAK = 0, SUBUH = 1, TERBIT = 2, DZUHUR = 3, ASHAR = 4, MAGHRIB = 5, ISYA = 6;
    /** The five obligatory prayers: the ones the countdown and the reminder follow. */
    static final int[] FARD = {SUBUH, DZUHUR, ASHAR, MAGHRIB, ISYA};
    /** As Kemenag's schedules print them, whatever the TV's language: the methods here are Indonesian ones. */
    static final String[] NAMES = {"Imsak", "Subuh", "Terbit", "Dzuhur", "Ashar", "Maghrib", "Isya"};

    private static final double ISYA_ANGLE = 18;
    /** Refraction and the sun's semi-diameter at the horizon, in degrees. */
    private static final double HORIZON = 0.8333;

    /** Kemenag puts Subuh at 20° below the horizon, Muhammadiyah (since 2021) at 18°. */
    enum Method {
        KEMENAG(20), MUHAMMADIYAH(18);

        final double subuhAngle;

        Method(double subuhAngle) {
            this.subuhAngle = subuhAngle;
        }

        /** config.xml prayer_method: "muhammadiyah", anything else is Kemenag. */
        static Method parse(String name) {
            return "muhammadiyah".equalsIgnoreCase(name.trim()) ? MUHAMMADIYAH : KEMENAG;
        }
    }

    private PrayerTimes() {
    }

    /**
     * Minutes since local midnight for {@link #IMSAK} to {@link #ISYA}, or null where the sun never reaches
     * one of the angles (far from the equator in summer).
     */
    static int[] compute(LocalDate date, double lat, double lon, double elevation, int utcOffsetMinutes,
                         Method method) {
        double jd = julianDay(date.getYear(), date.getMonthValue(), date.getDayOfMonth()) - lon / 360;
        double horizon = HORIZON + 0.0347 * Math.sqrt(Math.max(0, elevation));
        // Hours of solar time: Subuh, sunrise, noon, Ashar, sunset, Isya. A first guess, then two passes
        // that take the sun's position at the time found the pass before.
        double[] t = {5, 6, 12, 13, 18, 18};
        for (int pass = 0; pass < 2; pass++) {
            t = new double[]{
                    noon(jd, t[0]) - hourAngle(jd, t[0], lat, method.subuhAngle),
                    noon(jd, t[1]) - hourAngle(jd, t[1], lat, horizon),
                    noon(jd, t[2]),
                    noon(jd, t[3]) + hourAngle(jd, t[3], lat, asharDepression(jd, t[3], lat)),
                    noon(jd, t[4]) + hourAngle(jd, t[4], lat, horizon),
                    noon(jd, t[5]) + hourAngle(jd, t[5], lat, ISYA_ANGLE)};
        }
        double shift = utcOffsetMinutes / 60.0 - lon / 15;
        for (double hours : t) if (Double.isNaN(hours)) return null;
        int subuh = minutes(t[0] + shift, 2);
        return new int[]{
                subuh - 10,
                subuh,
                minutes(t[1] + shift, -3),
                minutes(t[2] + shift, 3),
                minutes(t[3] + shift, 2),
                minutes(t[4] + shift, 2),
                minutes(t[5] + shift, 2)};
    }

    /**
     * The next of the five prayers after {@code minuteNow}, as an index into {@code times}, or -1 once Isya
     * has begun (the next one is tomorrow's Subuh). A prayer whose minute it is now counts as begun.
     */
    static int next(int[] times, int minuteNow) {
        for (int i : FARD) if (times[i] > minuteNow) return i;
        return -1;
    }

    /** Local hours plus a margin in minutes, rounded up to the whole minute. */
    private static int minutes(double hours, int ihtiyat) {
        return (int) Math.ceil(hours * 60 + ihtiyat - 1e-9);
    }

    /** Solar noon in hours of solar time, from the equation of time at time {@code t}. */
    private static double noon(double jd, double t) {
        return fixHours(12 - sun(jd + t / 24)[1]);
    }

    /** Hours between noon and the moment the sun is {@code depression} degrees below the horizon. */
    private static double hourAngle(double jd, double t, double lat, double depression) {
        double decl = sun(jd + t / 24)[0];
        double cos = (-sin(depression) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl));
        return Math.toDegrees(Math.acos(cos)) / 15;   // NaN when |cos| > 1: the sun never gets there
    }

    /** Ashar: a shadow as long as its object plus the noon shadow (shadow factor 1), as a negative depression. */
    private static double asharDepression(double jd, double t, double lat) {
        double decl = sun(jd + t / 24)[0];
        return -Math.toDegrees(Math.atan(1 / (1 + Math.tan(Math.toRadians(Math.abs(lat - decl))))));
    }

    /** {declination in degrees, equation of time in hours} for a Julian day. */
    private static double[] sun(double jd) {
        double d = jd - 2451545.0;
        double g = fixDegrees(357.529 + 0.98560028 * d);
        double q = fixDegrees(280.459 + 0.98564736 * d);
        double l = fixDegrees(q + 1.915 * sin(g) + 0.020 * sin(2 * g));
        double e = 23.439 - 0.00000036 * d;
        double ra = fixHours(Math.toDegrees(Math.atan2(cos(e) * sin(l), cos(l))) / 15);
        double decl = Math.toDegrees(Math.asin(sin(e) * sin(l)));
        double eqt = q / 15 - ra;
        eqt -= 24 * Math.floor((eqt + 12) / 24);   // into -12..12
        return new double[]{decl, eqt};
    }

    /** Julian day at 0h UT of a Gregorian date. */
    static double julianDay(int year, int month, int day) {
        if (month <= 2) {
            year -= 1;
            month += 12;
        }
        int a = year / 100;
        int b = 2 - a + a / 4;
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    private static double sin(double degrees) {
        return Math.sin(Math.toRadians(degrees));
    }

    private static double cos(double degrees) {
        return Math.cos(Math.toRadians(degrees));
    }

    private static double fixDegrees(double a) {
        return a - 360 * Math.floor(a / 360);
    }

    private static double fixHours(double h) {
        return h - 24 * Math.floor(h / 24);
    }
}
