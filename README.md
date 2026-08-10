# EZC Quest Stream

Easy, see?

EZC Quest Stream is a native Android app for Meta Quest headsets that provides a real live stream URL you can open from a Mac.

It was built to simplify Quest streaming by removing the need for Meta Horizon tools, Android Developer Hub workflows, and fragile recording-based pipelines.

## Why This Exists

- Live stream URL, not recorded files
- Stable service that can survive sleep/wake transitions
- One-tap flow in headset: Start, Allow, Stream
- Mac-friendly playback via browser, VLC, or mpv

## Download

```bash
git clone https://github.com/ilovespectra/ezc-quest-stream/tree/main
cd ezc-quest-stream
```

## Fast Start

```bash
bash setup.sh
bash install.sh
```

Then on Quest:

1. Open EZC Quest Stream.
2. Tap the button to start streaming.
3. Tap Allow on the capture prompt.

On Mac (auto-discovers Quest IP):

```bash
bash open-stream.sh
```

Or open directly in browser after starting the stream on Quest.

## Walkthrough

1. Enable Quest Developer Mode and USB debugging.
2. Connect Quest to Mac with USB.
3. Run `bash install.sh` to build and install the APK.
4. Launch app in headset and grant capture permission.
5. Open stream URL on Mac.

## Stream Endpoint

- Root page: `http://<QUEST_IP>:8080`
- MJPEG feed: `http://<QUEST_IP>:8080/stream`

## Project Layout

- `StreamApp/` Android app source
- `install.sh` build and install helper
- `setup.sh` dependency setup for macOS
- `open-stream.sh` quick viewer opener for macOS
- `INSTALL.md` detailed install and troubleshooting

## Notes

- Reinstalling the APK stops the running service. Launch and allow again after each reinstall.
- If Quest sleeps, stream may temporarily go black and then auto-resume on wake.
- Quest and Mac must be on the same network for direct LAN streaming.
