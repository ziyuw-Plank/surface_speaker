# SurfaceSpeaker v0.8 — Native FIFO preferred for local playback

Android 16 / Xiaomi 14 Pro (shennong), experimental.

## Changes from v0.7

- **Native FIFO is the default local-audio output choice** on shennong. On Music, select a file and then tap **Play FIFO / 播放 FIFO**. It runs the existing **first-four-seconds** bounded FF_CUSTOM PCM preview through Root. It does not pretend to be gapless or full-song playback.
- Music screen has an explicit green **Output: Native FIFO (default)** toggle. Tap it to switch to Android compatibility vibration and speaker. The selected mode persists on restart.
- Music also has **Check FIFO / Root access** and an on-page selectable full command/error report. No silent fallback if Root or the device node fails.
- Fixed native 50 ms test incorrectly rejected by a 100 ms minimum-size guard.
- Audio strength compensation: retain mute and user volume link, use a sublinear magnitude curve with bounded <=22% native effect magnitude and <=48/127 raw sample amplitude. Higher than v0.7 but still conservative and not calibrated to actual motor displacement. Automatic fade and max four-second duration remain.
- Native tool reports EVIOCSFF upload and event start separately, plus a few kernel haptics log lines when readable. Neither return success nor kernel logs independently prove useful sound.

## Important

- **System live audio capture still uses Android Vibrator**. FF_CUSTOM is now preferred for **local files only**. Shizuku diagnostic remains read-only. Device requires Root for native.
- This is still a bounded offline preview, **not** continuous cross-app audio, nor a guarantee of speaker-level quality.
- The device name is discovered dynamically, not hardcoded to /dev/input/event6.
- Do not run native and ordinary vibration simultaneously; avoid prolonged or repeated tests; stop if excess heat or unusual noises arise. No SELinux or AVB changes.

See CI workflow for native ARM64 Android NDK compilation and debug APK artifact.