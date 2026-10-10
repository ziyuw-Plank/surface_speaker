# SurfaceSpeaker v0.11 — More detail and optional bounded motor boost

- Shared 24 kHz DSP for live and local FIFO audio: two-stage low-pass mixing (~820 Hz nominal), ~52 Hz DC/bass roll-off, bounded slowly adaptive gain, soft-peak limiting, and small triangular 8-bit dither. This seeks to preserve more waveform shape rather than clipping everything into saturated square-like vibrations; mechanical response remains unmeasured.
- **Haptic gain** slider now applies to **native FIFO PCM**, including local 4-second preview and live capture. It previously had no influence on that path.
- Standard FF magnitude remains **48%**. A manually confirmed **experimental boost** allows up to **56%** request magnitude (not calibrated mechanical/electrical power). Signed 8-bit PCM peak limit 76 -> 92. No Vmax, driver policy, sysfs, or AVB modification. Opt-in is OFF by default.
- Live 60 ms FF_CUSTOM segments use a shorter 0.75 ms start/end taper (formerly 2 ms each), reducing repetitive envelope pumping; discrete kernel uploads can still have audible seams.
- System media volume tracking, dead-quiet behavior, native/root diagnostics, bounded queue, cooling pauses, stop control, and insets remain.

## Limitations

This does **not** prove higher acoustic fidelity, true output power, continuous hardware streaming, or imperceptible latency. Built-in LRA mechanics can attenuate most content outside resonance. No thermal sensing or physical calibration is present; avoid continuous boosted use and stop immediately for unusual heat/noise. Root remains necessary for Native FIFO. AudioPlaybackCapture permission is still required for third-party app audio.