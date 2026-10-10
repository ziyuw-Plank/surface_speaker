# SurfaceSpeaker v0.5 — Adaptive motor envelope

Experimental native Android application targeting Xiaomi 14 Pro / HyperOS 3 (API 36).

## Why Android shows "Cast screen"
Stock Android requires a MediaProjection consent token for capturing another application's permitted playback. The shared screen consent dialog is supplied by Android. SurfaceSpeaker captures **audio only**; it does not read screen frames, use a virtual display, save audio or upload audio. Root-only capture without this dialog is not implemented because the vendor audio routing protocol remains unverified.

## Changes since v0.4
- Capture button changes to green **Capturing / 捕获中** while running, and an additional pending/consent state is displayed.
- Converts allowed captured PCM to a ~35-290 Hz band-limited amplitude envelope with ~10 ms control points, bundled in adjustable 40-80 ms waveform updates. Replaces the earlier 95 ms on/off pulse train for devices supporting amplitude control.
- Local playback uses Visualizer waveform capture to drive the envelope; FFT continues to drive the circular CAVA-inspired display.
- Retains bottom dock, automatic Chinese/English localization, presets, parameter controls and diagnostic routes.

## Limitations
- This reproduces **amplitude dynamics**, not full-bandwidth PCM waveforms or the sound quality of a mechanical surface speaker. No direct `/dev/qcom_haptic` writes or arbitrary vendor IOCTLs.
- Android playback capture permission is required for third-party app audio; source apps can deny playback capture.
- Root/Shizuku probes are read-only. Xiaomi MiHaptic HE continuous playback remains a separate unverified experimental path.
- CI confirms compilation, not mechanical performance. Intensity is capped and has a short periodic cooldown.

Download debug APK from GitHub Actions build artifact `SurfaceSpeaker-v0.5-debug-apk`.
