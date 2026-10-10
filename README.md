# SurfaceSpeaker v0.7 — Experimental continuous hardware FIFO audio

Native Android (Xiaomi 14 Pro / shennong / Android 16) continuation of v0.6. The old app ID is retained for upgrade. Chinese and English follow the system locale.

## Why it sounded like repeated short pulses

The previous versions used Android's vibration waveform updates for music, and the native `test` command sent a 50 ms effect and stopped. The Qualcomm `qcom-hv-haptics` driver can instead receive a *long effect* once and refill its 2048-byte hardware FIFO automatically through interrupts. This app now tests precisely that difference.

## New in v0.7

- Device page: explicit opt-in **2 s continuous 200 Hz sine test** (native Root FF_CUSTOM).
- Music page: explicit opt-in **4 s selected-file continuous motor audio preview**. Android MediaExtractor/MediaCodec decode the first 4 s locally; mono PCM is resampled to 24kHz and filtered ~60–480 Hz. The C helper issues **one** `EVIOCSFF` per clip and lets the kernel refill the FIFO. The app player is stopped during preview to avoid simultaneous pulse output.
- Input byte amplitudes capped at ±24/127, with startup/stop ramp. Root effect magnitude capped at **25% of full-scale** and scaled by media volume and the user's vibration ratio. Zero media volume disables preview.
- Automatically stops after the bounded preview. A **Stop native FIFO now** button creates a stop marker checked every 25 ms by the root helper (subject to app-private path visibility under KSU SELinux). No repeats, unknown IOCTLs, or SELinux/AVB/sysfs changes.
- The existing Android envelope engine remains the standard mode for playback or system audio capture.

## Limits and precautions

- This is *not* full-song gapless PCM streaming or a guaranteed bone-conduction speaker. It first validates uninterrupted, single-effect output; extending playback requires confirming kernel allocation, latency, thermal behavior and the driver's ABI on the current build.
- Driver ABI inferred from public `qcom-hv-haptics` source; this Xiaomi kernel may differ. CI cannot test physical motor response. Stop if vibration is abnormal, excessive, or the phone warms up.
- For other apps' live playback, Android audio-only playback capture still requires MediaProjection consent, and native FIFO preview is currently limited to a locally selected file.
- Kernel advertises FF_CUSTOM on `/dev/input/event6` in the user's tested build, but the tool rediscoveres the event dynamically by name, never assumes event6.

## Build

CI compiles the arm64 helper with Android NDK 26, packages it under `app/src/main/assets/native_fifo_arm64`, and assembles the Android debug APK. Download artifact `SurfaceSpeaker-v0.7-debug-apk` from GitHub Actions.