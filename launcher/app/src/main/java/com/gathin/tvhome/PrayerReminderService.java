package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import java.time.LocalDate;
import java.util.Calendar;

/**
 * A card at the top right, over any app, when one of the five prayer times begins (config.xml prayer_times),
 * with a short chime (prayer_sound). An accessibility service because that is what may draw over other apps
 * without the "display over other apps" permission, and because the system keeps it running: Home's own
 * minute tick stops whenever another app is in front. It reads no events and filters no keys, and the card
 * takes neither focus nor keys, so the app underneath keeps the remote and keeps playing.
 * Opt-in: PRAYER_SERVICE=1 build-and-install.sh (or -PrayerService on Windows) enables it.
 *
 * For a test from a computer, {@code adb shell am broadcast -a com.gathin.tvhome.PRAYER_TEST} shows the card
 * for the next prayer at once; only senders holding DUMP (the adb shell and the system) can send it.
 */
public class PrayerReminderService extends AccessibilityService {

    static final String ACTION_TEST = "com.gathin.tvhome.PRAYER_TEST";
    private static final long SHOW_MS = 30_000L;
    /** Longest single wait. The wait runs on uptime, which stops while the TV sleeps; waking re-checks anyway. */
    private static final long MAX_WAIT_MS = 30 * 60_000L;
    /** A prayer that began longer ago than this when the check runs (the TV was asleep) gets no card. */
    private static final int LATE_MINUTES = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = this::check;
    private final Runnable hide = this::hideCard;
    private Config.Prayer prayer;
    private View card;
    /** The prayer last shown, as minutes since the epoch day began, so each prayer gets one card. */
    private long lastShown = -1;
    private boolean receiving;

    /** The clock or the time zone changed, the date turned over, or the TV woke up: work the wait out again. */
    private final BroadcastReceiver clockChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            check();
        }
    };

    private final BroadcastReceiver test = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Calendar now = Calendar.getInstance();
            int[] times = today(now);
            if (times == null) return;
            int next = PrayerTimes.next(times, PrayerText.minuteOfDay(now));
            if (next < 0) next = PrayerTimes.SUBUH;
            show(next, times[next]);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // From config.xml or a theme's config.json; theme.sh rebinds this service after a push.
        prayer = Config.load(this).prayer;
        if (prayer == null) return;   // off, or no valid location: nothing to remind of
        IntentFilter clock = new IntentFilter();
        clock.addAction(Intent.ACTION_TIME_CHANGED);
        clock.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        clock.addAction(Intent.ACTION_DATE_CHANGED);
        clock.addAction(Intent.ACTION_SCREEN_ON);
        IntentFilter testFilter = new IntentFilter(ACTION_TEST);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(clockChanged, clock, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(clockChanged, clock);
        }
        // Exported, but only to senders holding DUMP: the adb shell and the system.
        registerReceiver(test, testFilter, android.Manifest.permission.DUMP, handler, Context.RECEIVER_EXPORTED);
        receiving = true;
        check();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiving) {
            unregisterReceiver(clockChanged);
            unregisterReceiver(test);
            receiving = false;
        }
        removeCard();
        super.onDestroy();
    }

    private int[] today(Calendar now) {
        return PrayerTimes.compute(LocalDate.now(), prayer.latitude, prayer.longitude, prayer.elevation,
                PrayerText.utcOffsetMinutes(now), prayer.method);
    }

    /** Shows the card for a prayer that has just begun, then waits for the next one. */
    private void check() {
        handler.removeCallbacks(check);
        Calendar now = Calendar.getInstance();
        int[] times = today(now);
        if (times == null) {
            handler.postDelayed(check, MAX_WAIT_MS);
            return;
        }
        int minute = PrayerText.minuteOfDay(now);
        long day = LocalDate.now().toEpochDay() * 1440;
        for (int i : PrayerTimes.FARD) {
            int late = minute - times[i];
            if (late < 0 || late > LATE_MINUTES || day + times[i] == lastShown) continue;
            lastShown = day + times[i];
            PowerManager power = getSystemService(PowerManager.class);
            if (power == null || power.isInteractive()) show(i, times[i]);
            break;
        }
        // Wake at the start of the next prayer's minute; after Isya, the date change brings tomorrow's Subuh.
        int next = PrayerTimes.next(times, minute);
        long wait = MAX_WAIT_MS;
        if (next >= 0) {
            Calendar at = (Calendar) now.clone();
            at.set(Calendar.HOUR_OF_DAY, times[next] / 60);
            at.set(Calendar.MINUTE, times[next] % 60);
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            wait = Math.min(wait, at.getTimeInMillis() - now.getTimeInMillis() + 500);
        }
        handler.postDelayed(check, Math.max(1_000L, wait));
    }

    private void show(int index, int minuteOfDay) {
        removeCard();
        WindowManager wm = getSystemService(WindowManager.class);
        if (wm == null) return;
        View view = LayoutInflater.from(this).inflate(R.layout.prayer_card, null);
        String name = PrayerTimes.NAMES[index];
        String time = PrayerText.clock(this, minuteOfDay);
        ((TextView) view.findViewById(R.id.prayerCardTitle)).setText(getString(R.string.prayer_reminder_title, name));
        ((TextView) view.findViewById(R.id.prayerCardDetail)).setText(prayer.city.isEmpty() ? time
                : time + " · " + prayer.city);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        float density = getResources().getDisplayMetrics().density;
        // Where the quick settings panel opens: under the Settings pill on Home, top right over a video.
        lp.x = (int) (56 * density);
        lp.y = (int) (105 * density);
        try {
            wm.addView(view, lp);
        } catch (RuntimeException e) {
            return;   // the window was refused (service unbinding): no card this time
        }
        card = view;
        view.setAlpha(0f);
        view.setTranslationY(-12 * density);
        view.animate().alpha(1f).translationY(0f).setDuration(300).start();
        handler.postDelayed(hide, SHOW_MS);
        if (prayer.sound) new Thread(() -> chime(this), "prayer-chime").start();
    }

    private void hideCard() {
        View view = card;
        if (view == null) return;
        view.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            if (card == view) removeCard();
        }).start();
    }

    private void removeCard() {
        handler.removeCallbacks(hide);
        View view = card;
        card = null;
        if (view == null) return;
        view.animate().cancel();
        WindowManager wm = getSystemService(WindowManager.class);
        try {
            if (wm != null) wm.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * A soft two-note chime of about 1.3 s, synthesized here so the APK carries no sound file. Whatever plays
     * underneath ducks for it (transient focus) and comes back at once.
     */
    private static void chime(Context context) {
        final int rate = 44100;
        short[] pcm = new short[(int) (rate * 1.3)];
        double[][] notes = {{0.0, 784.0}, {0.32, 1046.5}};   // G5, then C6
        for (double[] note : notes) {
            int start = (int) (note[0] * rate);
            for (int i = start; i < pcm.length; i++) {
                double t = (double) (i - start) / rate;
                double env = Math.min(1, t / 0.012) * Math.exp(-t * 4.5);   // quick attack, bell-like fade
                double s = Math.sin(2 * Math.PI * note[1] * t) + 0.25 * Math.sin(4 * Math.PI * note[1] * t);
                pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, pcm[i] + s * env * 7000));
            }
        }
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        AudioManager audio = context.getSystemService(AudioManager.class);
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs).build();
        AudioTrack track = null;
        try {
            if (audio != null) audio.requestAudioFocus(focus);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length * 2)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
            Thread.sleep(1400);
        } catch (RuntimeException | InterruptedException ignored) {
            // no sound this time; the card still shows
        } finally {
            if (track != null) track.release();
            if (audio != null) audio.abandonAudioFocusRequest(focus);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
