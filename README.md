# NomadLouie Desktop — Android client

A native Android client for [desktop.nomadlouie.com](https://desktop.nomadlouie.com) (LinuxServer.io
Webtop / Selkies). It speaks the Selkies WebSocket streaming protocol directly instead of running a
browser, which gives it real advantages over Chrome on a phone or tablet:

- **Fullscreen, letterbox-correct H.264/HEVC rendering** via hardware `MediaCodec` — no browser
  chrome, no canvas scaling artifacts, no battery-hungry software compositing.
- **Trackpad mouse mode**: one finger moves a relative pointer, tap = left click, two-finger drag =
  scroll, two-finger tap = right click — precision pointing that touchscreen-as-mouse can't match.
- **Direct touch mode** when you want touchscreen behavior (tap to click, drag to drag,
  long-press = right click).
- **Hardware keyboard passthrough** with proper X11 keysym mapping (including modifiers held
  across a lost keyup via the `kh` heartbeat).
- **On-screen sticky modifiers** (Ctrl / Alt / Shift / Super) for desktop shortcuts.
- **Resolution matching**: the remote desktop is resized to your device's exact screen, so no
  black bars and no scrolling.
- **Clipboard in both directions**: server clipboard lands in your Android clipboard; the
  "Send clipboard" button pushes your Android clipboard into the desktop; "Paste" types it into
  the focused window.
- **Live stats overlay** (fps / bitrate) and audio playback via Opus.

## Protocol

Implemented from the Selkies source ([selkies-project/selkies](https://github.com/selkies-project/selkies)):

- One WebSocket at `wss://<host>/api/websockets` carries everything, with HTTP Basic auth on the
  handshake (the Webtop container's nginx gate).
- Control is plain text verbs (`SETTINGS,{json}`, `START_VIDEO`, `START_AUDIO`,
  `CLIENT_FRAME_ACK <id> <heldMs>`, `LOST_FRAME <id>`, `r,WxH,primary`, `kd,`/`ku,`/`kh,`/`kr`,
  `m,`/`m2,` pointer verbs, `cws`/`cwd`/`cwe` clipboard upload, `cr`/`REQUEST_CLIPBOARD` reads).
- Media is binary frames typed by the first byte: `0x04` full video frames (codec + keyframe in
  the type byte, 12-byte header, Annex-B payload), `0x03` JPEG stripes (fallback encoder),
  `0x01` Opus audio with RED redundancy, `0x05` gzip-wrapped control text, `0x07` padding.

## Building

Open the project in Android Studio (or run `./gradlew assembleDebug`). Requires JDK 17.
The APK installs on Android 8.0+ (API 26).

```
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Launch the app; the server defaults to `https://desktop.nomadlouie.com`.
2. Enter the Webtop username/password (the same credentials the browser login asks for).
   "Remember credentials" stores them encrypted on the device.
3. Connect. The desktop resizes to your screen. Use the toolbar:
   - **Keyboard** — open the soft keyboard to type into the desktop.
   - **Trackpad** — switch between trackpad and direct-touch pointer modes.
   - **Ctrl** — tap for sticky Ctrl; long-press cycles Ctrl → Alt → Shift → Super (a dot marks it armed).
   - **Paste** — type the Android clipboard into the desktop.
   - **Send clipboard** — copy the Android clipboard into the desktop clipboard.
   - **Fit resolution** — resize the remote desktop to the device screen.
   - **Disconnect**.

## Notes / limitations

- The video codec follows the server's encoder (x264 H.264 by default on Webtop). HEVC, VP8,
  VP9, and AV1 are mapped to MediaCodec on devices that can decode them; the JPEG stripe
  fallback is composited in software.
- Audio uses the device Opus decoder (present on Android 10+; gracefully disabled otherwise).
- Microphone and webcam forwarding are not implemented.
- Only the primary display is supported (no `#display2` surfaces, no viewer roles).
