# Changelog

## 1.5.0

- **Faster file transfers:** a *Safe / Faster* choice in Settings and on the Send to PC
  screen. Faster sends files over 1 MB, both ways, on an encrypted connection of their own
  at the network's full speed, up to 16 GB a file; Safe keeps the link's own connection.
- **Voice and meeting console:** control Discord/Vesktop calls (mute, deafen, voice activity vs
  push-to-talk, speaking roster with cached avatars, per-user volume 0–200% and mute for me,
  join/leave notices), mute or switch the PC's microphone outside a call, adjust Discord's app
  volume and output device, and see active camera/mic/screen sensors (`Privacy`), with a
  landscape layout and a home summary card.
- **Focus and timers:** control the PC's pomodoro, countdown timers, stopwatch and ringing
  alarms from the phone in the shell's clock app design, with the wavy progress ring,
  cycle dots, stopwatch laps, one-tap saved presets, a duration keypad, optional vibration
  on phase changes and a landscape desk-stand layout that keeps the screen on.
- **Fixes from issue #5:** no crash when the link drops while the app is open (Tailscale
  off, the shell restarting) or when a shared file was already let go; the touchpad sends
  each touch's timing so the PC replays bursts at their real pace over Tailscale and slow
  Wi-Fi; second-screen sound plays on the phone and keeps playing on the PC (a switch on the
  PC sends it to the phone alone), with a buffer that adapts to the network; the shell can
  stop the phone ringing; the drawing pad warns when live draw is off or closed on the PC,
  with *Turn on* and *Reopen*; the webcam in device mode also shows up to PipeWire apps.

## 1.4.2

- **Game HUD on the phone:** the Performance screen draws the shell's game overlay HUD:
  the frame rate with its average, lows, frame-time graph and stutters, CPU and RAM, GPU
  and VRAM, network and battery, and chips for the game, resolution, driver, session and
  power profile. It follows the rows, style and palette set for the HUD on the PC, and
  fills the screen held sideways.
- **Lighter on battery and memory:** with the screen off the phone is no longer woken
  for workspaces, volume, toggles or PC copies (the latest arrives when the screen comes
  on); a reconnect no longer downloads the PC's wallpaper again; no connection attempts
  over mobile data when the PC cannot be reached from there; the PC's media notification
  is posted only when what it shows changes; the media widgets' clock is counted by the
  launcher and their seek line steps about a hundred times per track instead of every
  second; the weather widgets share one alarm that does not wake the phone; the PC's
  wallpaper behind the home card and other screen-only images are let go when the app
  leaves the screen.
- **PC wallpaper through the phone's wallpaper app:** *Use the PC's wallpaper* now opens
  the phone's own wallpaper dialog (One UI's *Set as wallpaper*), so the system and other
  apps see the change; from the background a notification opens it.

## 1.3.0

- **Stelnet Tools:** the app is now Stelnet Tools (application id `net.stelnet.tools`) and the
  connection is called Stelnet Link; the protocol is unchanged.
- **New icon:** a phone back with a square camera island.
- **Updates** now come from the releases of `stelnetxcis-create/stelos-tools`.
- Screenshots and wallpapers from the PC are saved in Pictures/Stelnet Tools.
- **Several PCs:** pair more than one PC without unpairing any. They all stay connected;
  the home's title is the controlled PC's name, and its arrow switches to another one or
  pairs a new one. The phone's notifications, calls and media reach every PC.
- **Away from home over Tailscale:** with *Away from home* on in the PC's Settings → Stelnet
  Tools, the app reaches the PC from any network: a *Connect over Tailscale* button when
  the PC is away, or *Tailscale away from home* to turn it on and off by itself.
- **Linked PCs and a universal clipboard:** *Link A and B* in the PC list introduces two
  PCs to each other once; then they talk directly, without the phone, and what you copy
  on one reaches the other. Each PC's shelf shows on the others (the file comes over when
  dragged or opened), *Send to <PC>* sends files, a web page playing continues on the other
  PC, PCs can link with a 6-digit code without the phone, and *Wake* asks a connected PC to
  wake one that is off, so it works from away over Tailscale.
- **Phone calendar on the PC:** every calendar the phone syncs (Google, Samsung, Outlook,
  local) in the shell's Timetable, widgets and calendar panels, without khal or vdirsyncer
  on the PC, and deduplicated when both are used. Choose which calendars leave the phone;
  with *Edit calendar* allowed and *Let the PC edit events* on, the Timetable creates,
  moves, edits and deletes phone events too.

## 1.2.0

- **Shell widgets on the home screen:** Poster, Deck, Halo and Cover media, Bloom clock,
  Month Tall, Date Type, Date Shapes, Date Banner, Weather Strip and Weather Monolith,
  ported from the shell at its proportions. They work without the PC: the media ones
  follow any app's player, the weather ones fetch on their own. Optional calendar and
  location access in Settings → Home screen widgets.
- **Updates in the app:** a card on the home, a row in Settings and one quiet notification
  when a new version is out, with the changelog of every release since yours and a direct
  download from GitHub.
- **Volume keys for the PC:** an option (Audio and Settings) for the phone's volume buttons
  to change the PC's volume while the phone itself plays nothing.
- **Bluetooth batteries on the PC:** the battery of the earbuds, watch or headset connected
  to the phone shows next to the phone's own in the shell.
- **Wireless microphone without a tap:** with *Display over other apps* allowed, the PC
  turns the phone's microphone on by itself, even with the phone locked.
- **Edit the home:** reorder, remove and add back the home's cards (Settings → Edit home
  screen).
- **Fixed:** the PC's sound during a Wi-Fi second-screen session (needs the shell's
  `ii_link` helper rebuilt: *Settings → Stelnet Tools* flags an outdated helper).

## 1.0.0

First public release.

- **Link:** pairing by QR code or `iilink://` link, a pinned TLS WebSocket to the shell's
  `ii_link` helper on the LAN or Tailscale, kept alive in the background with a ping a minute.
- **Home:** a dashboard of the PC (lock, what plays, volume with output and input pickers,
  live activity, tools, controls, load, the phone as the PC's microphone, the PC's clipboard).
- **Media Mode:** a landscape full-screen player with cover-tinted backdrop and synced lyrics.
- **Tools:** touchpad, deck, draw, present, second screen (extend or mirror over USB),
  webcam, microphone, file sharing both ways, SFTP browsing of the phone, screenshots and
  gallery, calls, clipboard, Wake-on-LAN, routines and Modes triggers, phone status and
  data (alarm, contacts, screen time).
- **Live notifications relay** for the shell, including Android 16 Live Updates.
- Material 3 Expressive with Material You colour, in English and Portuguese (Brazil).
