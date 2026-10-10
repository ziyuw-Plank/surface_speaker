# SurfaceSpeaker v0.13 — lower-lag / cleaner native FIFO (experimental)

Built for Xiaomi 14 Pro / `shennong` with KernelSU on Android 16. Outputs to the existing root-only Qualcomm `FF_CUSTOM` FIFO helper.

## What changed

- Adjustable **48 ms (Fast, default), 60 ms (Balanced) and 96 ms (Smoother)** FF_CUSTOM block length. This changes the *input accumulation time*, **not** measured end-to-end latency. A shorter frame may cause more noticeable mechanical restarts; the smoother setting may reduce tremor at the cost of lag. Settings take effect on the next capture.
- New **low-frequency rumble suppression** and **high-frequency stability** tuning controls. Stateful double high-pass and tuneable low-pass replace the previous extra-wide 52-1250 Hz profile. Default filters are conservative until the LRA frequency response can be measured.
- Less aggressive slow AGC with smoothly limited peaks, aimed at reducing low-tone crackle and high-tone pumping. Native signal amplitude safety ceiling remains unchanged; boost is still opt-in.
- Capture FFT visualization now runs only about once every 85 ms, avoiding redundant CPU work in the hot audio thread.
- The native session reports per-segment processing time (measured in the helper, not measured mechanical or end-to-end audio latency). Maximum backlog remains one frame; late frames are discarded.
- No automatic capture duration limits, no periodic one-second pauses.

## Technical limits

Android MediaProjection captures only eligible application playback. `FF_CUSTOM` still uploads **separate effects** and the Qualcomm driver restarts at each block. This update does **not** make the input FIFO appendable, achieve phase-synchronous speaker audio, or guarantee an imperceptible delay. Its 8-bit PCM path and unmeasured motor resonance remain hard fidelity limits.

Never override Vmax, SELinux, protected partitions or driver protection. Avoid prolonged high-strength output or unattended use; stop immediately if overheating or abnormal sound occurs. GitHub Actions validates JVM DSP tests and APK builds, not the motor's acoustic properties.