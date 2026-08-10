# Install Guide

This repo ships a native Quest app that exposes a live MJPEG URL on your local network.

## 1) Prepare your Mac

```bash
bash setup.sh
```

## 2) Connect and authorize Quest

1. Connect Quest to Mac with USB.
2. In headset, approve USB debugging when prompted.
3. Keep Developer Mode enabled on Quest.

## 3) Build and install the app

```bash
bash install.sh
```

## 4) Start streaming

1. Open EZC Quest Stream in the headset.
2. Tap Start Stream.
3. Tap Allow on the capture permission prompt.

## 5) Open stream on Mac

```bash
bash open-stream.sh <QUEST_IP>
```

Direct endpoints:

- http://<QUEST_IP>:8080
- http://<QUEST_IP>:8080/stream

## Troubleshooting

- If install fails: run `adb devices` and verify your headset serial is listed.
- If URL is unreachable: confirm Quest and Mac are on the same Wi-Fi/LAN.
- If you reinstalled APK: relaunch app and allow capture again (service restarts on reinstall).
- If stream goes black after sleep: wake headset; stream auto-resumes.
