# Sony BRAVIA VU3 4K — Setup & Operations

**Device:** Sony BRAVIA VU3 4K · Realtek RTD2885M  
**Firmware:** Google TV 14 (API 34), build `UKR1.240726.001`  
(same firmware as the VU31 — see `examples/sony-bravia-vu31`)

---

## Finding the TV

The IP is **DHCP-assigned** and may change after a power cycle or router restart.

```bash
# Scan for the TV on the local network (macOS / Linux with mDNS):
dns-sd -B _googlecast._tcp local
# Look for a record whose name starts with BRAVIA-VU3-…
# The address in the last column is the current IP.
```

> [!NOTE]
> **Deep standby drops network ADB.** After the TV sleeps, wake it with the remote first,
> then `adb connect <ip>:5555`. If port 5555 is unreachable after waking, toggle the
> remote's power button once more and retry — the TV may still be finishing its wake sequence.

---

## ADB Setup

USB debugging alone opens port 5555 on this TV. The first `adb connect` shows an
**"Allow USB debugging?"** prompt on screen; approve it, then it connects freely.

```bash
adb connect 192.168.68.53   # replace with the current DHCP IP (previously .71, .61, now .53)
adb shell   # smoke-test
```

---

## Debloat

Apps are **disabled, never uninstalled**. Re-enabling one is always safe.

```bash
# Apply (disables the listed apps):
TV_HOST=<tv ip> tools/disable.sh examples/sony-bravia-vu3/debloat.txt

# Undo (re-enables everything; pass the stock launcher package too):
TV_HOST=<tv ip> tools/restore.sh examples/sony-bravia-vu3/debloat.txt \
    com.google.android.apps.tv.launcherx
```

### Known deviations from the debloat list

| Package | Status | Note |
|---|---|---|
| `com.bstar.intl.tv` (Bstation) | **Enabled** | Re-enabled manually after the debloat was applied; cause unconfirmed. Re-disable with `adb shell pm disable-user --user 0 com.bstar.intl.tv` if desired. |
| `com.google.android.apps.tv.launcherx` | **Disabled** | Handled by `build-and-install.sh` after Home moves to the Compose launcher — not in the debloat list itself. |

### Deleted Wi-Fi network "AndroidX"

Disabling `com.google.android.tungsten.setupwraith` (the setup wizard) also deletes the
Wi-Fi network it created (`SSID "AndroidX"`). This TV is on Ethernet, so the deletion was
intentional (`FORCE_SETUPWRAITH=1` was passed to `disable.sh`).

---

## Compose Launcher (daily driver)

`ComposeHomeActivity` is the live HOME on this TV. It renders:
- **Header**: Islamic greeting, live 24 h clock + WIB label, Gregorian + Hijri date
- **Prayer rail**: countdown to next prayer + 7 cells (next prayer in gold)
- **Verse**: rotating Quran / hadith quote (Arabic + Indonesian + source)
- **Dock**: real app banners, D-pad focus with gold ring + lift animation
- **Top buttons** (press Up from dock): Sumber · Jaringan · Setelan
- **All-apps grid** (press Down from dock): Back closes it

### Install / reinstall

```bash
# Build release APK and push to the TV; re-enables PrayerReminderService:
PRAYER_SERVICE=1 TV_HOST=<tv ip> launcher/build-and-install.sh

# PRAYER_SERVICE=1 is required on every reinstall — Android clears
# accessibility services on each install.
```

### View launcher fallback

`MainActivity` (the original View/XML launcher) stays installed as a launchable fallback.
It has no HOME intent-filter and cannot be reached from the Compose grid (the launcher's
own package is hidden). Start it over ADB only:

```bash
adb shell am start -n com.gathin.tvhome/.MainActivity
```

### Rolling back to the View launcher

1. In `AndroidManifest.xml`: move `HOME`+`DEFAULT` intent-filter back to `MainActivity`;
   remove it from `ComposeHomeActivity`.
2. In `build-and-install.sh` and `.ps1`: revert the `set-home-activity` and `am start` lines
   to `.MainActivity`.
3. Rebuild and reinstall:
   ```bash
   TV_HOST=<tv ip> launcher/build-and-install.sh
   adb shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity
   ```

---

## Theme — `masjid-malam`

The active theme pushes seven night-mosque wallpapers (rotating hourly), Bandung prayer
times, and a Quran / hadith quote file.

```bash
# Regenerate quotes.json (gitignored — texts belong to their sources):
python3 tools/quotes.py themes/masjid-malam/quotes.txt

# Push theme to the TV (wallpapers + config.json + quotes.json):
TV_HOST=<tv ip> tools/theme.sh themes/masjid-malam
```

### Changing the dock apps or prayer location

Edit `themes/masjid-malam/config.json` (or `launcher/app/src/main/res/values/config.xml`
for built-in defaults), then re-push the theme or rebuild the APK — no new install needed
for a theme-only change:

```bash
TV_HOST=<tv ip> tools/theme.sh themes/masjid-malam
```

Key fields in `config.json`:

| Key | Current value | Meaning |
|---|---|---|
| `greeting_text` | `Assalamu'alaikum` | Fixed greeting (replaces time-of-day) |
| `greeting_name` | `Deni` | Name appended to greeting |
| `clock_zone_label` | `WIB` | Zone label after the clock |
| `prayer_latitude` | `-6.9175` | Bandung latitude |
| `prayer_longitude` | `107.6191` | Bandung longitude |
| `prayer_elevation` | `1150` | Metres; matches Kemenag's Kota Bandung table |
| `wallpaper_rotation_minutes` | `60` | Wallpaper swaps once an hour |
| `wallpaper_dim` | `45` | 45 % black overlay over wallpapers |

---

## Private DNS (AdGuard)

Private DNS is set to `dns.adguard-dns.com` for network-level ad-blocking.

```bash
# Check current setting:
adb shell settings get global private_dns_mode          # should print "hostname"
adb shell settings get global private_dns_specifier     # should print "dns.adguard-dns.com"

# Undo (revert to automatic / no Private DNS):
adb shell settings put global private_dns_mode opportunistic
```

---

## Network ADB Security

> [!WARNING]
> **Port 5555 is still open.** Network ADB gives any device on the LAN full shell access
> to the TV. Close it only after confirming the Compose launcher survives a reboot
> (ADB is the only recovery path if Home fails to come up after the close).

**Safe close-down procedure:**

1. **Reboot** the TV with ADB still enabled:
   ```bash
   adb shell reboot
   ```
2. **Confirm** the Compose launcher comes up as HOME and `PrayerReminderService` rebinds:
   ```bash
   adb connect <new ip>   # DHCP may have given a new address — use dns-sd to find it
   adb shell cmd package resolve-activity --brief \
       -a android.intent.action.MAIN -c android.intent.category.HOME
   # Must print: com.gathin.tvhome/.ComposeHomeActivity
   adb shell settings get secure enabled_accessibility_services
   # Must contain: com.gathin.tvhome/.PrayerReminderService
   ```
3. **Only then** close ADB on the TV:
   - Go to **Settings → System → About → Developer options**
   - Disable **USB debugging** and **Network/ADB debugging** (or "Wireless debugging")
   - Tap **Revoke ADB authorizations**
   - Reboot the TV
4. **Verify** from the Mac:
   ```bash
   nc -vz <tv ip> 5555   # must time out / say "Connection refused"
   ```
