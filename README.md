# Android Remote Dashboard

Thin Android client for [`RemoteWebViewServer`](https://github.com/strange-v/RemoteWebViewServer), intended as a faster alternative to rendering Home Assistant Lovelace locally in WebView on low-power Android panels.

This project is the Android-side equivalent of the lightweight `RemoteWebViewServer` clients used in other ecosystems such as ESPHome, but aimed at Android devices instead of microcontrollers.

It was built with AI solely to solve my own NSPanel Pro problem, but it is published in the hope that it can help others with similar wall-panel setups as well.

## Requirements

- A running [`RemoteWebViewServer`](https://github.com/strange-v/RemoteWebViewServer) instance in front of Home Assistant.
- A Home Assistant dashboard URL that `RemoteWebViewServer` can open and keep authenticated.
- An Android device with network access to the `RemoteWebViewServer` WebSocket endpoint.

This repository is only the Android client. You still need the server-side `RemoteWebViewServer` project running separately, typically alongside Home Assistant.

## Features

- full-screen native Android client for `RemoteWebViewServer`
- tiled frame rendering for JPEG and PNG updates
- touch forwarding back to the remote browser session
- full-screen configuration screen with immediately persisted settings
- startup status screen with remote connection state and optional MQTT state
- optional debug overlay with remote session, MQTT, URL, and frame stats
- idle mode with black overlay and configurable timeout
- optional stronger idle dimming using Android system brightness permission
- MQTT command listener for wake control on `remotedashboard/<baseTopic>/command`
- foreground MQTT service so wake handling can continue when the activity is no longer in the foreground

## Configuration options

### Connection

- `RemoteWebViewServer` WebSocket URL
- device ID
- initial Home Assistant/dashboard URL
- JPEG quality
- tile size
- minimum frame interval

### MQTT

- enable/disable MQTT
- broker host or IP
- broker port
- SSL on/off
- username
- password
- base topic
- client ID

Command topic format:

```text
remotedashboard/<baseTopic>/command
```

Currently supported payloads:

```json
{"wake": true}
```

```json
{"wake": true, "wakeTime": 180}
```

```json
{"wake": false}
```

### Display / Idle

- screen-off timeout in seconds
- enhanced idle dimming toggle
- normal on-screen brightness slider

### Interface

- debug overlay visible on/off
- config button corner: left or right

## Build notes

- Requires JDK 17 for the Android Gradle Plugin.
- Requires a local Android SDK installation with a recent Android platform; this project currently builds with `compileSdk = 36`.
- The project does not commit `local.properties`; use your local SDK path.

Example build command:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew assembleDebug
```

## First-run flow

1. Launch the app.
2. Open settings with the circular button in the lower corner.
3. Enter the `RemoteWebViewServer` WebSocket URL, for example `ws://server-ip:8081`.
4. Enter the NSPanel device ID and initial Home Assistant URL.
5. Close settings. The app applies the changed configuration and reconnects if needed.
6. If you enable MQTT wake or enhanced idle dimming, also configure the MQTT and display/idle sections.

## Tested device

Tested only on a SONOFF NSPanel Pro. Other Android devices may work, but that is not yet a supported claim.

## Known limitations

- no RAW565 decoding yet
- no audio-related remote commands yet
- wake and dimming behavior are tuned for the NSPanel Pro and may need adjustment on other Android devices
- no dedicated native home/back shortcuts yet
- session authentication relies on the remote browser session handled by the server
