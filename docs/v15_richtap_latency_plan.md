# v15 草案：无缝 RichTap 流 + 低延迟（未在真机验证）

## 1. v13 FIFO 路径为何仍不完美（源码依据：qcom-hv-haptics.c）
- 每 48/60/96 ms 一个独立 FF_CUSTOM effect：上传 → `haptics_load_custom_effect()`（停止当前播放、TOGGLE_EN 清 FIFO、设 vmax、关 auto-res）→ 播放 → EV_FF stop → EVIOCRMFF。
  每段边界都有一次 停机/重启（硬件 hboost 就绪、FIFO 重新预填），即 ~20 Hz 的"段边界嗡声/颗粒感"，这是 v13 振颤的主要来源之一，DSP 无法消除。
- helper 是串行的：一段放完（忙等）才读下一段，加上上传开销 → 播放时长 > 段长，管道中积压，延迟漂移（最多 1 帧积压后丢帧 → 丢帧也会造成可听断点）。
- 8-bit、24 kHz、`magnitude` 线性缩放 vmax（fifo_vmax_mv），低电平量化粗；在 strength 低时有效分辨率只剩几 bit。
- FF_CUSTOM 下 brake=NULL、auto_res_disable=true（驱动已固定），所以刹车/自谐振不是干扰源；但每段 stop 时驱动的停止流程本身会产生瞬态。

## 2. 推荐路径：/dev/qcom_haptic RichTap 共享环形缓冲 STREAM_MODE（真正连续）
- 16 槽 × 1024 B（24 B 头 + ≤1000 B s8 PCM），mmap 16 KiB；ioctl 0x520A 开始流，0x5212 停止。
- 内核在 FIFO-empty IRQ（阈值约 288 样本 = 12 ms）里按顺序搬运 VALID 槽到硬件 FIFO，只要环不空就**一次启动、连续播放**，无段边界。
- 用户之前的 v0.14/0.15 尝试被回退：根因（读源码推断）是**先写数据后调 STREAM_MODE**，而该 ioctl 在调度工作后执行 `richtap_clean_buf(0xFF)` 把刚写的槽清空 → 内核 "first length invalid" → 流立即结束 → helper 超时回退。另外 4×960 样本队列 + MAX_AMP 36 导致又慢又弱。
- 草案 `app/src/main/cpp/richtap_stream_v2.c` 修正：先 ioctl 再写；每次重启游标归 0；10 ms 小槽；限制领先量；输入空隙用静音槽保活，绝不让环干涸（避免内核 underrun→erase→重启）；SCHED_FIFO；空闲 1.5 s 后干净停止。
- 注意：公开源码里每次 IRQ 都 `dev_info("update fifo len")`，会刷 dmesg（约 80 次/秒），可能是之前"性能退化"的一部分，用户态无法去掉（需改内核或调低 printk 级别：`echo 4 > /proc/sys/kernel/printk` 仅影响控制台）。

## 3. 延迟预算（估算，未实测）
| 环节 | v13（48 ms 档） | v15 草案 |
|---|---|---|
| AudioRecord 读块（512 样本 @48k） | ~10.7 ms | ~10.7 ms（可降到 240 样本 ≈5 ms） |
| AudioRecord 内部缓冲（max(minBuf, 2048 样本)） | 0–43 ms 波动 | 同（建议改为 minBuf） |
| 段积累 | 48 ms | 10 ms（槽长） |
| 管道积压/排队 | 0–48 ms（最多 1 帧） | ≤ 领先量 3 槽 = 30 ms（可设 2 = 20 ms） |
| 每段上传/停启/hboost | ~5–15 ms（每段都有） | 只在启动时一次 |
| 硬件 FIFO 预填 | 整段（48 ms）进 FIFO 后播放 | 内核只搬已就绪槽，稳态 ≈ 领先量 |
| DSP（IIR，最小相位，无前瞻） | ~1–2 ms 群延迟 | 同 |
| **合计（不含机械与系统混音器）** | **约 65–165 ms** | **约 25–60 ms** |
还需加上 Android 播放捕获本身的混音延迟（通常 20–40 ms，无法从 App 控制）。

## 4. 风险与未验证项
- ioctl 编号、槽布局、状态值来自公开的 Motorola SM8550 内核源码（同为 PM8550B / HAP525_HV），**必须**和用户机上 qcom-hv-haptics.ko 对照（见探测命令）。
- `SETTING_GAIN` 按 lower_mv 缩放电压；默认 64/128，切勿调到驱动保护之上。
- 静音保活会让 hboost 持续工作（耗电/发热）；空闲超时后停止。
