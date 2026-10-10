# SurfaceSpeaker 0.3 — Xiaomi 14 Pro haptic audio research app

This is a separate continuation of **SurfaceSpeaker Root Lab v0.2**. Existing source and APKs remain untouched.

## Scope

* Chinese or English UI based on system locale (no manual selection).
* Dark olive / lime UI with true FFT-driven, CAVA-inspired circular spectrum (72 logarithmic bands). Select music with Android's system document picker.
* Three built-in presets: Balanced, Deep Bass, Detail; a saved Custom preset; individual sliders for gain, bass weighting, noise gate, smoothing, pulse duration, speaker volume, and **frequency where PWLE is truly supported**.
* Capability-aware greyed-out frequency, waveform-envelope and native HapticGenerator controls. The UI does not assume that the Xiaomi 14 Pro has exposed API 36 frequency control even though the motor's marketing bandwidth is 50–500 Hz.
* Root `su` and Shizuku **UserService** permission paths for safe, read-only driver discovery. Can export reports. Shizuku uses its official Java SDK; no reliance on unsupported `rish` scripts.
* Android amplitude-waveform pulses and optional Android 16 PWLE, strictly capped. The MiHaptic/HE test sends only 120ms low-intensity vendor test from the previous experimental project.
* No network permission, background motor operation, unknown driver writes, kernel patches, remounts or SELinux changes.

## Known limitations — IMPORTANT

This version provides **music-reactive vibration / acoustic-resonance experimentation**, NOT verified PCM-to-motor fidelity or the high-quality surface loudspeaker output in the referenced video. Audio spectrum is obtained through `Visualizer` for app's MediaPlayer session. `RECORD_AUDIO` permission is used for the analyzer; it does not record microphone audio or upload content. Playback and reactive vibration are foreground-only.

Both privileged modes currently enable **read-only motor interface identification**, not direct RTP/FIFO PCM streaming. Whether a future version can output audible music through the ESA1016 actuator depends on verification of a stable vendor driver interface on the user's actual Xiaomi 14 Pro build. **Shizuku's shell UID is not equal to root**, and neither mode guarantees write permission to device nodes.

## Build

Requires Android SDK Platform 36, JDK 17+, Android Gradle Plugin 8.11.2, Gradle 8.13 and Maven Central (Shizuku SDK 13.1.5). In Android Studio: open root folder, sync, run **Build > Build APK(s)**, or use Gradle `:app:assembleDebug`.

APK output: `app/build/outputs/apk/debug/app-debug.apk`. No APK has been built or validated in the current offline build environment.

## Safe testing

Start with 0 speaker volume if you want to compare whether the sound really comes from the table. The motor is optimized for haptics rather than broadband acoustics. Begin with low gain; the vibration pulse engine limits intensity and periodically pauses to reduce sustained operation, but it does not have calibrated thermal feedback.

Inspect with Root or Shizuku, export the report and verify vendor RTP/FIFO support before considering more advanced hardware-specific operations.

## GitHub Actions alternate build

If Android Studio is unavailable, place this independent project in a **new dedicated repository** (not on top of unrelated work). The included `.github/workflows/build-apk.yml` builds on push to `main` or by manually selecting **Run workflow**. Download `SurfaceSpeaker-v0.3-debug-apk` from the run's Artifacts section. This avoids touching any of your existing repositories.
