# SurfaceSpeaker 0.4 — Android haptic audio prototype

An experimental native Android application for Xiaomi 14 Pro (`shennong`, Android 16) and other Android 12+ phones.

## v0.4 changes

- Four separate pages with a **fixed bottom dock**: Home, Music, Tune, Device. Chinese / English follows the system locale.
- Home shows a live circular spectrum, audio input strength, **number of vibration commands issued**, permissions and activation status. Commands sent are not a confirmation of mechanical movement.
- A real-time **system playback capture** route using Android MediaProjection + a foreground service with an audio-only `AudioPlaybackCaptureConfiguration`. Audio is processed on-device in memory (PCM to 72 log-frequency bars); it is not stored or transmitted. Android shows a consent dialog; the recording/foreground notification is visible. Captures only media/game apps which permit capture. Some DRM/protected apps will be silent.
- Local file picker now immediately attempts playback after selecting the track, displays the document's display name, and gives meaningful error feedback.
- Default presets and fine tuning, with a short motor preview when releasing sliders. PWM/PWLE frequency and HapticGenerator remain greyed out if unavailable.
- Audio-energy mapping improved for stronger yet capped 25–65ms pulses; 95ms minimum separation, periodic half-second pauses every 8 seconds. Not a high-fidelity haptic PCM output.
- Root probe now invokes `su` via `/system/bin/sh -c` for compatibility; it reports success only after UID 0 has been confirmed. Shizuku remains read-only.

## Limitations / known issues

- No direct writing to `/dev/qcom_haptic` or sysfs; vendor protocol is not yet safely verified. Root and Shizuku do **not** grant native PCM-to-motor functionality in this version.
- The system AudioPlaybackCapture API excludes protected, non-opted-in, and certain app audio by design.
- No physical Xiaomi 14 Pro hardware validation was possible in CI. Granting root to an older package ID does not authorize `dev.surfacespeaker.lab03`; grant it for this exact application in the KernelSU manager.

## Install

Use the `Build SurfaceSpeaker APK` GitHub Actions workflow, download its `SurfaceSpeaker-v0.4-debug-apk` artifact ZIP, unzip, then install `app-debug.apk`. Debug application ID: `dev.surfacespeaker.lab03` (upgrades v0.3).

## Safety

No AVB, SELinux, kernel partition, or system partition changes. Never sends raw arbitrary IOCTLs to unverified drivers. Long continuous motor drive can cause heat; this prototype caps intensity and pulse lengths.