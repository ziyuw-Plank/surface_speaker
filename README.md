# SurfaceSpeaker v0.9 — Native FIFO live capture experiment

Native ARM64 root haptic FIFO engine for Xiaomi 14 Pro / shennong (Android 16).

- Default route is Native FIFO on shennong, for local and live playback capture. The route selector in Music also controls live output. Android system MediaProjection consent remains required to capture other apps (audio only; no screen image captured).
- Live capture retains a rooted helper process and writes signed 8-bit, 24-kHz, band-limited PCM over its stdin. The helper submits **individual 500-ms** FF_CUSTOM effects. This is **buffered real-time**, *not gapless continuous PCM*; transitions may click/stutter and latency may be noticeable. It does not silently revert to Android Haptics if root fails.
- A single Root helper process (rather than spawning per block) reduces control overhead. In-app diagnostics show native block count and native errors.
- Strength: maximum FF magnitude raised cautiously from 25 to 40%; audio sample cap raised from ±48 to ±64 (signed 8-bit). Output is still media-volume-linked and the user's motor-ratio setting is preserved. These are driver request limits, not calibrated mechanical output. Start with low ratios and increase gradually.
- Realtime native mode is automatically bounded to 60 s maximum per capture session, with 1 s cooldown every 15 s; silence at zero media volume does not excite the motor. For prolonged listening, switch to Android compatibility or wait for a vendor-documented streaming ABI.
- Root and EVIOCSFF may fail on individual HyperOS releases; the capture page reports exact errors. No system-partition writes, SELinux policy modification, or experimental ioctl to /dev/qcom_haptic.
- The native FIFO device driver copies whole waveforms per upload, so this is **not a fully continuous streaming interface**. Research of vendor RichTap mmap/RTP interface would be required to eliminate seams. An arbitrary kernel may handle FF_CUSTOM differently; protocol is device-limited and the physical outcome is not verified in CI.

Build via GitHub Actions `Build SurfaceSpeaker APK`, download `SurfaceSpeaker-v0.9-debug-apk`.