# Linux beta in OpenDisplay vX.Y.Z

Status: **draft, not released.** Written 2026-10-20 against `linux-plan`,
reworked for the unified release stream (one version and one GitHub release
for every platform, SENDER-PLAN §8.1): the Linux beta ships in the first app
release cut after `linux-plan` merges, `vX.Y.Z` below. The `build-linux` job
of `release.yml` (it calls `linux-release.yml`) appends a short "Linux
(beta)" section to that release's notes: install lines for the tarballs,
the Arch package and the Flatpak, the linked FFmpeg/x264 versions and the
source checksum for the PKGBUILD. The text below is the long form; it
replaces that section by hand once the release exists (`gh release view
vX.Y.Z --json body --jq .body > notes.md`, edit, `gh release edit vX.Y.Z
--notes-file notes.md`; keep the `<!-- linux-release:start/end -->` markers
so a re-run of the job replaces it again). Before publishing, re-check:
Android (first live session works, 2026-10-20; the functional checklist of
touch, scroll, rotation, lock and quit is pending, so it is still "expected
to work" below) and the CI state of the smoke jobs.

---

## Linux (beta)

`od-sender` extends a Linux desktop onto an OpenDisplay receiver: an iPhone,
iPad, Android device or spare Mac running the OpenDisplay app. It creates a
virtual display on your compositor sized for the receiver, captures it,
encodes H.264 and streams it over WiFi; taps and two-finger scrolls on the
receiver come back as pointer input, and rotating the device rebuilds the
display. Same protocol as the Mac sender, so the existing receivers work
unchanged. No account, no server, no telemetry.

This is a **beta**: it is tested on one desktop, and the others are marked
experimental. Reports help a lot (see the end).

### Where it runs

| | |
|---|---|
| **Tested** | Omarchy / Hyprland 0.56 on aarch64 (Asahi Linux, M1 Pro), software x264. Receivers: iPhone, Mac receiver app (Intel iMac) |
| **Expected to work** | Hyprland 0.55+ on any distro with an FFmpeg built with libx264 (otherwise the bundled OpenH264, slower); x86_64; iPad and Android (Google Play beta) as receivers. A first Android session (Pixel 10a, WiFi, 2424x1080) streamed at 53 fps with 33 ms end-to-end median latency (45 ms 95th percentile) |
| **Experimental** | **Sway 1.10+**, **KDE Plasma 6.8+** (6.7 and older only with the packaged `/usr/bin/od-sender`), **GNOME 50+**. Each passes an automated test on a headless copy of the compositor in CI; none has run on a real desktop session yet |
| **Your own output** | niri, COSMIC, river, labwc, Wayfire and similar: `od-sender run --output <name>` streams an output you created, if the compositor offers the screen-capture and virtual-pointer protocols (`od-sender doctor` shows them) |
| **Not supported** | X11 sessions, Hyprland older than 0.55, Flatpak, USB cable, HEVC, Apple Pencil pressure (mapped to a plain pointer), audio, mirroring |

### Install

* **Arch Linux / Omarchy:** build the package from this release's source
  (it links the system FFmpeg):
  ```sh
  git clone --depth 1 --branch vX.Y.Z https://github.com/peetzweg/opendisplay.git
  cd opendisplay/linux/packaging/arch/opendisplay-sender
  ./from-checkout.sh -si    # makepkg -si on this checkout
  ```
* **Other distributions:** `od-sender-x86_64-unknown-linux-gnu.tar.gz` and
  `od-sender-aarch64-unknown-linux-gnu.tar.gz` below; they need glibc 2.38+
  (x86_64) or 2.39+ (aarch64) and PipeWire (`libpipewire-0.3`), not FFmpeg.
  Unpack and run `./install.sh`: it installs `od-sender` into
  `~/.local/bin` with a desktop entry and a `systemd --user` unit pointing
  at it (`sudo ./install.sh --system` for `/usr/local`; `--uninstall`
  removes them).
* **Receiver:** `flatpak install --user od-receiver-x86_64.flatpak` (or
  `-aarch64`) on any distribution incl. SteamOS; on Arch,
  `linux/packaging/arch/opendisplay-receiver` builds the same way as the
  sender.
* **From source:** see [linux/README.md](https://github.com/peetzweg/opendisplay/blob/main/linux/README.md#from-source).

Then open the OpenDisplay app on the receiver, on the same network, and run:

```sh
od-sender doctor          # what od-sender will use on this desktop
od-sender run             # first receiver found; or: od-sender run --to iPhone
```

`systemctl --user enable --now od-sender@iPhone.service` starts it on login.

### What's in it

* Detects Hyprland, Sway, KDE Plasma or GNOME and creates a virtual display
  sized from the receiver's panel (`--backend` to override, `--output` to use
  one of yours); `--position`, `--scale` (`passthrough`, `mac` or a number),
  `--max-fps`, `--bitrate-mbps`, `--encoder`.
* Encoder picked at run time: V4L2 hardware where a driver exists, else x264,
  else the bundled OpenH264. Up to 60 fps, like the Mac sender; higher only
  when the receiver's decoder advertises it (`videoCaps`).
* Touch and scroll from the receiver; rotation rebuilds the display after a
  short settle; the receiver locking or quitting is handled.
* Reconnects after WiFi drops with backoff; recreates the display if it is
  removed from under it; only one sender per receiver.
* Stops cleanly on Ctrl+C, `SIGTERM` and `SIGHUP`, removing its display;
  `od-sender clean` removes one left by a crash on Hyprland and Sway (KDE
  Plasma and GNOME clean up by themselves).
* `~/.config/opendisplay/sender.toml` for defaults and per-receiver
  overrides; `od-sender list` shows receivers on the network.
* On Hyprland before 0.57, screen capture uses wlr-screencopy, which survives
  displays disappearing mid-capture (Hyprland 0.56's other capture path can
  crash there); `--capture` overrides.

### Known issues

* **The cursor is part of the video.** It moves at video latency, and on a
  static screen it can lag until something else changes. The separate
  cursor channel is off on Hyprland (its cursor capture cannot deliver the
  cursor image yet).
* **Software encoding.** No VA-API yet; x264 on the CPU. A 5K iMac streamed at
  4096x2304 shows about 55-70 ms end to end against ~20 ms for an iPhone.
* **KDE Plasma:** `--position auto-left` or `auto-up` shift your other screens
  while the display exists (Plasma allows no negative positions); they move
  back when it goes, also after a crash (KWin 6.8 does that itself;
  `od-sender clean` is the fallback).
* **Fractional scales are rounded by the desktop.** Hyprland only accepts
  scales that give whole logical pixels, so a Pixel's 2.625 becomes 2.6667;
  GNOME snaps to the scales it offers for the size (below). `od-sender`
  accepts the scale the desktop chose.
* **GNOME:** scale snaps to what GNOME offers for the size (an iPhone's 3
  becomes 2); setting scale or position applies a temporary display
  configuration that keeps your other monitors as they are.
* **Sway, KDE Plasma, GNOME** have not run on a real desktop session yet.

### Reporting a problem

[Open an issue](https://github.com/peetzweg/opendisplay/issues/new/choose)
with the output of `od-sender doctor`, a log from
`RUST_LOG=debug od-sender run --to <receiver> 2>&1 | tee od-sender.log`, your
distro, desktop and version, CPU architecture, and the receiver device and
app version. A report that an "expected to work" or experimental setup
*works* is just as useful. Tracking issue:
[#299](https://github.com/peetzweg/opendisplay/issues/299).
