# SurfaceSpeaker v0.14 — RichTap mmap stream prototype

A carefully bounded **experimental** ARM64 RichTap implementation for Xiaomi 14 Pro `shennong`, based on static inspection of the exact uploaded Android 16 binaries (`libaacvibrator.so` and `qcom-hv-haptics.ko`). The proven FF_CUSTOM route remains the default.

## New
- Independent root-native executable (`richtap_stream.c`) using `/dev/qcom_haptic`, a 16 KiB MAP_SHARED buffer, 16 × 1024 byte slots with producer state 0x55 / consumed state 0xFF and `STREAM_MODE` 0x520A, `STOP_MODE` 0x5212.
- **Read-only device probe** in Device tab (does not mmap or send ioctl).
- **Explicit short tone experiment:** 0.35 seconds, 200 Hz, 12% sample scaling, absolute PCM ±36; watchdog, buffer-full timeout, guaranteed stop on normal exit/signals.
- **Optional RichTap live mode:** manually opted-in on Tune page; reset to OFF every app launch. A native process accepts playback capture blocks, maintains a bounded ring producer and sends NEXT acknowledgements. A **20-second experiment limit** applies only to the unverified RichTap route; ordinary FF_CUSTOM capture remains user-controlled and unlimited.
- RichTap uses the existing media-volume-following strength settings but further caps sample scaling. No driver voltage, sysfs, AVB, or SELinux alterations.

## Critical limitations
- ABI and sample rate have been inferred from binary disassembly, but **not validated on the real motor**. 24 kHz is the current test assumption, not a measurement of physical actuator bandwidth. Unexpected errors must be reported; do not repeatedly retry a failing stream.
- The userspace ring and mmap registration are distinct from hardware PMIC FIFO. This is an experimental interface; actual continuity and latency must be measured on device.
- Do not test unattended, with multiple vibration apps, or at elevated temperatures. Test read-only probe first, then short tone. Only enable RichTap Capture after verifying the short tone and safe stop.
- The old FF_CUSTOM engine remains available and is used by default on application launch. In the experimental RichTap mode, stops after 20 seconds are intentional safeguards (unlike the removed v0.11 FF_CUSTOM cutoff).