# SurfaceSpeaker v0.12 — Persistent live FIFO & waveform detail

## Fixed: Capture ended automatically

The previous native live capture forcibly exited after **1,000 × 60 ms = 60 seconds**, and imposed a **one-second silence every 250 frames (15 seconds)**. Both prototype limits have been removed. Live capture continues until the user stops, Android revokes MediaProjection, or the audio/driver process fails. No scheduled silence is inserted. For long, loud playback, a conservative sustained-signal estimate can progressively reduce the requested magnitude; this is **not hardware temperature sensing**. Stop if the device heats up or sounds abnormal.

## Improved FIFO fidelity

- Removed repeated 0.75-ms fade-to-zero from both ends of *every* 60-ms live segment; preserve PCM sample/phase continuity at the application boundary. The driver still uses discrete FF_CUSTOM effects, so hardware restart gaps can remain.
- Stateful DSP now has a wider roughly **52–1250 Hz** signal band and an adjustable **FIFO waveform detail** spectral shelf. The physical LRA may not reproduce the full frequency range. Gain/limiting and 8-bit dither remain capped.
- Same detail setting applies to live capture and four-second local PCM preview.
- Native failures are surfaced in the status message instead of overwritten by a generic "Capture stopped" event.

## Constraints

On Xiaomi shennong arm64 only, with KernelSU/root; Android MediaProjection consent required for third-party audio. This is not an unlocked driver or guaranteed gapless PCM streamer. No changes to Vmax, SELinux, AVB, system partition, or vendor files. Safety relies on conservative signal limits and the device's existing firmware protections, **not measured motor temperature/power**. Do not run loud continuous output unattended. CI tests DSP/code compilation, not actual vibration response.