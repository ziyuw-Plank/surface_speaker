# SurfaceSpeaker v0.10 — Low-latency FIFO experiment (Xiaomi 14 Pro)

- Native 24-kHz FF_CUSTOM frames reduced from 500 ms to **60 ms**. This cuts the buffering floor; it **cannot guarantee imperceptible latency or gapless playback**. Android AudioPlaybackCapture and the hardware path add further delays.
- Persistent Root process, one-slot latest-frame handoff, native per-frame ACK, and **drop old frames** rather than buffering seconds behind the source. No multi-second pipe buildup. If the driver gets slow, playback may skip frames instead of drifting.
- PCM sample peak cap raised from ±64 to **±76** and magnitude ceiling from 40% to **48%**. Requests are bounded, not calibrated hardware power; begin with a low Tune ratio and check for heat. Root helper has stop marker and a time limit with cooling pauses.
- Fixed Android 16 status bar / display-cutout overlap and bottom navigation gesture inset using `WindowInsets`. Fixed bottom dock retained.
- Default shennong live output remains FIFO. Start Capture still uses MediaProjection audio-only consent to access playback from other apps; Root does **not** expose another app's future decoded PCM via a generic cache interface.
- An FF_CUSTOM upload contains an entire segment. This design does not write to an appendable PCM FIFO and does not promise perfect phase sync to the system speaker.
- Hardware API and real acoustics must be validated on the actual phone; CI only verifies binary builds. Do not use with abnormal motor heat/noise. No system partition changes or SELinux relaxation.