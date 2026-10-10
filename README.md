# SurfaceSpeaker v0.6 — Xiaomi 14 Pro experimental FIFO haptics

A native Android prototype for `Xiaomi 14 Pro (shennong)` / HyperOS / Android 16 (API 36). This version preserves the bottom dock, circular spectrum, automatic Chinese / English interface, local player, and system-playback-capture option.

## New in v0.6

- **Media volume link (default ON)**: Android `STREAM_MUSIC` volume scales the vibration request, with 0 output when media volume is muted. A separate **motor strength ratio** slider (0–100%) applies an additional multiplier. Both settings are saved with the Custom preset; these figures are *requested actuator intensity*, not calibrated SPL or motor displacement.
- The generic `VibrationEffect` haptic envelope, fallback pulses, and slider feedback now follow this scale. The 3 experimental FIFO tones follow the same scale with an extra conservative hard cap.
- Device tab adds a read-only device validation button and explicit, user-confirmed **120 Hz / 200 Hz / 300 Hz** short-tone tests using an arm64 NDK helper and root. These tests send *50 ms* of signed 8-bit waveform samples at *24 kHz* through the Linux input `EVIOCSFF` `FF_PERIODIC`/`FF_CUSTOM` path, and stop/erase the effect. No arbitrary driver writes, no sysfs changes. Rate and magnitude are capped; a 2-second cooldown prevents rapid repetition.
- Probe verifies the device name `qcom-hv-haptics` and advertised FF capabilities. Root binary is executed from a unique temporary subdirectory under `/data/local/tmp` and removed after the call. If permissions or kernel ABI differ, the test aborts with an explicit error instead of silently falling back.

## Hardware assumptions and cautions

The custom FIFO upload structure matches **one public Qualcomm `qcom-hv-haptics.c` implementation**:

```c
struct custom_fifo_data { u32 idx; u32 length; u32 play_rate_hz; u8 *data; };
```

Your phone exposed `/dev/input/event6` with FF_CUSTOM and previously logged `custom data length 829 with play-rate 24000 Hz` / successful FIFO programming. **These observations do not independently validate the complete ABI for your exact Android 16 kernel build.** An explicit confirmation is required before each short test. The probe/test is restricted to the `shennong` device and 64-bit arm64 architecture.

**This is not a full real-time native music engine.** The FIFO tests only play short bounded tones; generic music still uses the Android amplitude-envelope path, while third-party audio capture still requires a `MediaProjection` consent dialog. Building a seamless continuous native PCM stream requires on-device protocol, buffering, timing, and frequency-response validation first. A 24 kHz FIFO *sample rate* is not the LRA's acoustic bandwidth.

## Security and privacy

No screen frames, microphone data, audio recordings, or network uploads. Third-party captured audio is processed locally in RAM only and requires user consent. No AVB / protected partition / SELinux modifications; root operations are restricted to a short-lived helper and verified input interface. Excessive vibration can heat a motor; stop immediately if hot or mechanically abnormal. Laboratory prototype only; do not enable repeated unattended native tests.

## Build

GitHub Actions installs Android SDK 36, NDK 26.3 and Gradle 8.13. The native executable is compiled for `aarch64-linux-android28` into the APK assets. Download the artifact **SurfaceSpeaker-v0.6-debug-apk**, extract `app-debug.apk`, and install it over v0.5 (application ID `dev.surfacespeaker.lab03`).