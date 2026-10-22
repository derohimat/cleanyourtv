# Backlog

Branch `compose-launcher-revamp` (4 commits, **not pushed**). The Jetpack
Compose Home (`ComposeHomeActivity`) is the daily-driver HOME; the View
launcher (`MainActivity`) stays as a launchable fallback.

## Compose migration — feature ports still pending

Still only in the View launcher (`MainActivity` + helpers), not yet in
`ComposeHomeActivity`:

- **Input switcher** — the "Sumber" button opens system Settings for now;
  port the real `TvInputManager` input list (`showInputs()` in MainActivity).
- **Live network label** — "Jaringan" is static; show the real SSID / Ethernet
  and the VPN-off badge (`updateWifiLabel()`, `VpnBadge`).
- **Reorder tiles** on Home (`toggleReorder()`, `Order.java`).
- **App library** editor — add/remove apps from Home (`AppLibraryDialog`,
  `LibraryApps`, `docs/app-library.md`).
- **Module cards** (`CardFeature`, `Cards`, `docs/specs/card.md`).
- **Festival skins** (`Festival`, `DiyaDrawable`, `MarigoldDrawable`,
  `BellDrawable`, `PetalShowerView`).
- **Sky backdrop** (`SkyView`, `sky_intensity`).
- **Stats line** (`show_stats`: CPU / free memory / free storage).
- **Shamsi date** (`shamsi_date`).
- **fill_dock** — fill empty dock slots with the first grid apps.
- **reduced_motion / advanced_effects** flags.
- **Remote-key services** wiring (`InputKeyService`, `HomeKeyService`:
  `settings_key_*`, `input_key_*`, `app_keys`).
- **Live TV / source panel** (`live_tv_package`, `source_panel_service`).

## Compose polish ideas

- Restore focus to the **last** dock tile (not just the first) on return /
  after closing the grid.
- Rotate the wallpaper on a **timer**, not only by the hour on resume.
- Grid: remember scroll position and last-focused tile.
- Pressing Home while already on the Compose home should reset to the dock
  (handle `onNewIntent`).

## Cleanup / ops (from the earlier debloat session)

- Write `examples/sony-bravia-vu3/README.md`: restore script usage, Private
  DNS undo (`settings put global private_dns_mode opportunistic`), how to
  change the dock and the prayer location, and that the AndroidX Wi-Fi
  network was deleted (re-join in Settings).
- **Close network ADB** when done: turn off USB + Network/ADB debugging,
  revoke authorisations, reboot, and verify `nc -vz <ip> 5555` fails.
- TV IP is DHCP-assigned (was `192.168.68.71`, now `192.168.68.61`); any docs
  referencing the old IP should note this.
- **Bstation** is enabled again on the TV — decide whether to re-disable it.

## Release

- When ready: `git push` the branch and open a PR.
- To make the **View launcher** HOME again: move the `HOME` / `DEFAULT`
  intent-filter back to `MainActivity` in `AndroidManifest.xml` and
  `cmd package set-home-activity … /.MainActivity`.
