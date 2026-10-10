# SurfaceSpeaker v0.15 — Auto RichTap realtime and resilient capture

- Real-time capture on verified `shennong` defaults to **RichTap first**, with automatic fallback to known FF_CUSTOM if mmap/STREAM_MODE fails or stalls, then to Android compatibility haptics if Root itself is unavailable. The app reports the actual engine and fault instead of stopping instantly or hiding errors.
- Fix Android MediaProjection onStop callback overwriting the real shutdown reason.
- Remove the RichTap-only fixed 20 s test cap. Output is still byte-limited (MAX_AMP=36), and extended loud input triggers gradual energy-budget derating. This is **not** a measured motor thermal limit, and it does not remove kernel/PMIC safety protections.
- Keep manual `RichTap` off switch under Tune to troubleshoot. Standalone device probe and 350 ms tone remain available.
- End-to-end continuity and thermal safety require device measurements; CI cannot confirm motor behavior.

DO NOT USE unattended or at full power while the device is warm. Stop immediately if unusual sounds or overheating occur. Do not bypass AVB/SELinux, alter Vmax, or send unverified raw IOCTL data.