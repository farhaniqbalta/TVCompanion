# Ambilight capture bottleneck: YouTube stutters while screen capture runs

Investigation date: 2026-11-22. Branch: `exp/ambilight-capture-research` (experimental, not for merge). Raw data and commands: [ambilight-capture-experiments.md](ambilight-capture-experiments.md).

Evidence labels: **MEASURED** (observed on the device), **SOURCE-CONFIRMED** (AOSP source or documentation), **INFERRED** (consistent with evidence, not directly shown), **HYPOTHESIS** (awaiting validation), **UNKNOWN**.

## 1. Executive summary

With ambilight capture running, 1080p YouTube drops from 30 fps to about 11 fps on the SWTV-22AE-FHD (MT5867).

A Perfetto trace shows the cause directly. When SurfaceFlinger composites our capture (virtual) display on the GPU, it calls the MStar vendor HAL `IMmDisp::mmDetileFromHtoGB`. That call takes **75 ms on average (max 95 ms)** per composition. It is **96% CPU time on a single `mmdisp` binder thread**. SurfaceFlinger waits for it, so the whole display presents only 102 times in 10 s, compared with 293 without capture (**MEASURED**).

YouTube's video buffers are 1.83 KiB handles, not pixel data (**MEASURED**). The decoded picture stays in decoder memory in a tiled layout that the display hardware scans out directly. To make it readable by the GPU, the vendor detiles the full frame on the CPU into a regular YV12 GraphicBuffer (**INFERRED** from the call name, buffer sizes and timing).

The parts TVCompanion controls make no measurable difference:

- capture output size (80×45, 160×90, 320×180);
- capture output format (YUV is not even honoured: SurfaceFlinger still renders RGBA);
- our LED processing.

All of these leave YouTube at about 11.5 fps (**MEASURED**). The cost is paid per composition of the source frame.

**Decision:** keep the current capture pipeline. Stop optimizing the app side. The only lever available to a normal app is the source resolution, i.e. the YouTube quality setting: 480p gives 30 fps, 720p about 21 fps. A real fix needs vendor work: a faster or GPU detile, or HWC virtual-display composition. See §10. **Vendor VSS lead (2026-11-22), closed:** the vendor video-snapshot service `IMtkVssService` is reachable from a plain sideloaded app that targets SDK 27. It initialises and connects to the 1080p video, but none of its frame-delivery paths work on this firmware: the encoder-based ones are unsupported, and the direct one needs a memory address inside the vendor process (§6.1).

## 2. Device and baseline measurements

| Item | Value | Label |
|---|---|---|
| Model / SoC | SWTV-22AE-FHD / MediaTek-MStar mt5867 | MEASURED (`getprop`) |
| Android | 14 (SDK 34), build UKNV.260911.001 | MEASURED |
| Panel / UI plane | 1920×1080 panel (`sys.display-size`); 1280×720 UI plane and HWC config | MEASURED |
| GPU | ARM Mali-G31, OpenGL ES 3.2 (r25p1) | MEASURED (`dumpsys SurfaceFlinger`) |
| Gralloc | `android.hardware.graphics.allocator/mapper@4.0-impl-arm` | MEASURED (`lshal`) |
| HWC | `hwcomposer.mt5867.so`, composer@2.4 service, "Mstar HAL" | MEASURED |

YouTube playback rate (FPS of the video layer from `dumpsys SurfaceFlinger --latency`), same video:

| Condition | Video FPS | Label |
|---|---|---|
| 1080p, ambilight OFF | 30.0 | MEASURED |
| 1080p, ambilight ON | 9–11.8 (several runs) | MEASURED |
| 720p, ambilight ON | ~21 | MEASURED |
| 480p (644×480), ambilight ON | 30.0 | MEASURED |

## 3. Current graphics pipeline

```text
YouTube MediaCodec (OMX.MS.AV1.Decoder)
  └─► SurfaceView BLAST layer: 1.83 KiB handle, format 0x13, decoded picture stays in decoder memory
        ├─► physical display: HWC DEVICE composition (scan-out, no GPU)          ← cheap
        └─► "Ambilight" virtual display (MediaProjection, AUTO_MIRROR)
              SurfaceFlinger client (GPU) composition, RenderEngine/Skia
                └─► IMmDisp::mmDetileFromHtoGB (sync HIDL call, ~75 ms CPU at 1080p)
                      └─► full-frame YV12 GraphicBuffer ("externalbuffer-allocator", 3037.5 KiB)
                            └─► GPU samples it into ImageReader RGBA_8888 160×90
                                  └─► TVCompanion edge sampling → UDP/DDP → WLED
```

- The virtual display composition is `usesDeviceComposition=false usesClientComposition=true` (**MEASURED**).
- HWC lists only the primary display. Its capabilities are `SidebandStream` and `Unknown`, and the SurfaceFlinger build config reports `MAX_VIRT_DISPLAY_DIM=0` (**MEASURED**).
- AOSP: "If the HWC doesn't support virtual display composition, SurfaceFlinger creates the handle and composites the display" (**SOURCE-CONFIRMED**, implement-hwc).

## 4. Source video format investigation (R1)

| Observation | Label |
|---|---|
| SurfaceFlinger layer: `activeBuffer=[1920x1080:…,Unknown 0x000013]`, `defaultPixelFormat=Unknown 0x000013` | MEASURED |
| Gralloc import of the same layer: `req fmt:842094169` (= `0x32315659`, **YV12**), `usage 0x42400900`, `compressed: false`, **size 1.83 KiB** at 854×480 and at 1920×1080 | MEASURED |
| `0x13` (19) is not defined in AOSP `PixelFormat.aidl` or `graphics-base-v1.0.h`. `0x10`, `0x11`, `0x14` are defined; `0x12`, `0x13`, `0x15`, `0x17–0x1F` are not | SOURCE-CONFIRMED |
| Usage `0x42400900` = GPU_TEXTURE (1<<8) + COMPOSER_OVERLAY (1<<11) + VIDEO_DECODER (1<<22) + GPU_CUBE_MAP (1<<25) + bit 30 (vendor mask `0xF0000000`) | SOURCE-CONFIRMED (bit meanings); the use of bit 25 here is UNKNOWN |
| Two `externalbuffer-allocator` buffers with real pixel size: 3037.5 KiB at 1920×1080 (= 1920×1080×1.5, YV12) and ~610 KiB at 854×480. Usage includes a CPU-write field value (0x20). They remain allocated after capture stops | MEASURED |
| A real 1080p YV12 frame is 3037.5 KiB, so a 1.83 KiB buffer cannot hold pixels | MEASURED (arithmetic on measured sizes) |

**Conclusion.** `0x13` is a vendor-defined value used by the MStar stack, not an AOSP format.

- The YouTube layer buffer is a small descriptor. The real frame sits in decoder memory in a tiled layout. The display pipeline can scan it out (HWC DEVICE), but the GPU cannot sample it directly (**INFERRED**: call name `mmDetile…`, 1.83 KiB size, and every GPU composition triggering a detile).
- Its declared GPU_TEXTURE usage does not make it cheap to sample. Sampling goes through the detile (**MEASURED**, trace).

For contrast:

- The same AV1 decoder driven by the stock `MediaPlayer` in our harness produced a **real** 3037.5 KiB, format `0x15` buffer (**MEASURED**, one frame before the decoder errored; see §7 E).
- YouTube therefore uses a different, vendor-specific output path. Which API YouTube uses to reach it is **UNKNOWN** (tunneled playback is a candidate; not verified).

## 5. MediaProjection and ImageReader analysis (R2)

- MediaProjection → `createVirtualDisplay(AUTO_MIRROR)` → SurfaceFlinger mirrors the layer stack into the ImageReader surface (**MEASURED**: "(Mirror)" layers on the "Ambilight" display in `dumpsys SurfaceFlinger`).
- **Output format:** an ImageReader configured `YUV_420_888` receives RGBA. ImageReader rejects it: "The producer output buffer format 0x1 doesn't match the ImageReader's configured buffer format 0x23". Zero frames are accepted, and YouTube still drops to ~11.5 fps (**MEASURED**). The reason SurfaceFlinger renders RGBA anyway is **INFERRED**: GPU composition of a virtual display without HWC support, with the build config showing `FORCE_HWC_FOR_RBG_TO_YUV=0`. Either way, the capture output format does not change the processing path (**MEASURED**).
- **Output size:** 80×45, 160×90 and 320×180 all give ~11.5 fps (**MEASURED**). The cost is on the source side.
- **Source vs output format:** the expensive step converts the **source** video buffer (tiled → YV12 GraphicBuffer) before the GPU can read it. The output format (RGBA ImageReader) is irrelevant to that step (**MEASURED** via trace and Experiments A/B).
- **Projection alive, surface detached:** 30 fps and mmdisp ~0% (**MEASURED**). Holding the MediaProjection costs nothing. Composing into it costs everything.
- API surface at SDK 34 (`javap` on `platforms/android-34/android.jar`):
  - `MediaProjection` exposes only `createVirtualDisplay(String, int, int, int, int, Surface, VirtualDisplay.Callback, Handler)`, so there is no `VirtualDisplayConfig` overload (**SOURCE-CONFIRMED**).
  - `VirtualDisplayConfig.Builder.setRequestedRefreshRate(float)` exists, but only through `DisplayManager.createVirtualDisplay(VirtualDisplayConfig…)`. That method does not take a projection token, and mirroring other apps' content without one needs system-level permission (**INFERRED**).
  - Even a lower composition rate would not remove the per-composition detile (**MEASURED** in §8 plus the earlier throttle runs).

## 6. Hardware Composer investigation (R3, Experiment D)

| Path | Availability on this device | Label |
|---|---|---|
| HWC virtual-display composition | Not offered: HWC lists only the primary display; capabilities `SidebandStream`/`Unknown`; SurfaceFlinger composites the virtual display on the GPU | MEASURED |
| Hardware readback / blit / downscale via public SDK | None found in the SDK 34 surface; MediaProjection's only capture primitive is a SurfaceFlinger-composited virtual display | SOURCE-CONFIRMED (`android.jar` `javap`) / INFERRED (no other path) |
| NDK (AHardwareBuffer, AImageReader) | Same SurfaceFlinger path as the SDK; does not change composition | INFERRED |
| Privileged / system APIs (e.g. `SurfaceControl` screenshot, `captureDisplay`) | Not in the public SDK; system-only | INFERRED (absent from public `android.jar`), not attempted |
| Vendor HALs: `mstar.hardware.mmdisp@1.0::IMmDisp`, `vendor.mediatek.graphics.externalbuffer@1.0`, `vendor.mediatek.api.mtktvapi@1.0::*` | Exist (`lshal`); `lshal debug` fails; untrusted apps cannot reach vendor HIDL services under SELinux | MEASURED (exist / debug fails); access restriction INFERRED (standard Treble policy), not attempted |
| Vendor driver library `/vendor/lib/libmtal.so` (MTAL) | Loadable by apps (listed in `/vendor/etc/public.libraries.txt`; 32-bit only). It is a thin ioctl wrapper over `/dev/mtal` (NEEDED: libc, liblog, libutils, libc++, libm, libdl). It exports pixel-readback functions (`MTVDO_DI_GetDramPixels`, `MTVDO_EnableDIDramDump`, `MTOSD_PLA_Capture`). `/dev/mtal` is `crw-rw---- root root`, SELinux type `mtal_device`; policy grants it only to vendor/system domains (`mt_dtv-svc`, `hal_tv_ashidlservice_default`, `surfaceflinger`, `system_server`, `system_app`, `mediaserver`, ...), never to app domains. Opening it as the app UID (u0_a135, via `run-as`) fails: `Permission denied`. Bundling a copy of the library changes nothing, because the block is on the device node | MEASURED (symbols, deps, permissions, policy, open test); function behaviour UNKNOWN (not called) |
| Vendor video snapshot service `vendor.mediatek.tv.mtkmtalservice@1.0::IMtkVssService` (VSS) | Running (process `vendor.mediatek.tv.mtkmtalservice@1.0-service`, user `system`, domain `hal_tv_mtkmtalservice_default`). SELinux grants `hwservice_manager find` + `binder call` to `system_app`, `platform_app`, `priv_app`, `mediaextractor` and **`untrusted_app_27`** (and lets the service call back into `untrusted_app_27`). A targetSdk 27 app reaches it; a targetSdk 34 app is denied. See §6.1 | MEASURED (probe on device) |
| Vendor colour-statistics / TV "ambient light" feature | None found in service lists | MEASURED (absence in `service list`/`lshal` grep) |
| Root / firmware changes | Out of scope (constraint §6 of the brief) | — |

### 6.1 IMtkVssService probe (Experiment G, 2026-11-22)

A throwaway probe APK (built without Gradle; removed from the tree after the research, last version in commit `2fc80ec`) calls the service through reflection on the hidden `android.os.HwBinder` / `HwParcel` classes. It was built twice, once targeting SDK 27 and once SDK 34.

| Step | targetSdk 27 (`untrusted_app_27`) | targetSdk 34 (`untrusted_app`) | Label |
|---|---|---|---|
| `HwBinder.getService(IMtkVssService, "default")` | `HwRemoteBinder` returned | `NoSuchElementException`; logcat `avc: denied { find } … tcontext=hal_tv_mtkmtalservice_hwservice` | MEASURED |
| HIDL base `ping` / `interfaceDescriptor` | OK / `vendor.mediatek.tv.mtkmtalservice@1.0::IMtkVssService` | — | MEASURED |
| Hidden-API reflection on `HwBinder`/`HwParcel`/`HwBlob` | Allowed, no warning logged | Allowed up to the SELinux denial | MEASURED |
| `MTVSS_GetVssVideoInform` (transaction 18) | Returns `ret=-9` and a 24-byte `HIDL_MTVSS_VSS_INFO_T`, all zero | — | MEASURED (call works); meaning of -9 INFERRED (no `MTVSS_Init` called yet) |
| `MTVSS_GetConfig` (transaction 6) | Reply did not parse as `int32 + one buffer` at any size 4–1024 | — | MEASURED; layout UNKNOWN |

**Interface (from the proxy library `/vendor/lib/vendor.mediatek.tv.mtkmtalservice@1.0.so`).** Transaction codes follow `.hal` declaration order, which equals the address order of the generated `BpHw`/`BnHw` functions. The code passed to `transact` was confirmed in the disassembly for `GetVssVideoInform` (0x12).

| Code | Method | Code | Method |
|---|---|---|---|
| 1 | `Hidl_MTAL_Init()` | 11 | `MTVSS_Init()` |
| 2 | `MTVSS_RegCbFunc(cb)` | 12 | `MTVSS_SetColorFormat(u32)` |
| 3 | `MTVSS_SetFrameCallback(cb)` | 13 | `MTVSS_SetDumpFormat(u32)` |
| 4 | `MTVSS_TriggerNextframe420()` | 14 | `MTVDO_SetMonOutOutputWindow(u32, u32)` |
| 5 | `MTVSS_Init_v(VIDEO_INFO_T)` | 15 | `MTVSS_ConnectEx(u32)` |
| 6 | `MTVSS_GetConfig() → VIDEO_INFO_T` | 16 | `MTVSS_DisConnectEx(u32)` |
| 7 | `MTVSS_StartCapture()` | 17 | `MTVSS_SetTrigger()` |
| 8 | `MTVSS_StopCapture()` | 18 | `MTVSS_GetVssVideoInform() → VSS_INFO_T` |
| 9 | `MTVDO_GetTimingInfo(u32) → TIMING_INFO_T` | 19 | `MTVSS_GetframeBuffer(u16 ×4, u32)` |
| 10 | `MTVSS_GetframeBuffer420(hidl_memory)` | 20 | `MTVSS_GetHimemeorybuffer() → hidl_memory` |

Codes 1–17 and 19–20 are INFERRED from function order (the proxy disassembly is consistent with it). Only 18 is confirmed on device.

**Stateful calls (same day, YouTube 1080p playing in the foreground, probe run as a no-UI broadcast receiver).** The service logs each MTAL call under tag `MTAL` (pid 525).

| Call sequence | Result | Label |
|---|---|---|
| `init`, `info` before MTAL init | both `-9`; service log `MTVSS_Init() fail, ret=-9` | MEASURED |
| `Hidl_MTAL_Init` (1) → `MTVSS_Init` (11) → `info` | `0`, `0`, `0`; service log `open /dev/mtal_system ok` | MEASURED |
| `ConnectEx(0)` (15) → `info` | `0`; info words become `0x02D00500 0 0x00010000 0x04380780 0x6000 0x00010000`, i.e. **1280×720** and **1920×1080** as u16 width/height pairs | MEASURED (values); field meaning INFERRED (capture size and video source size) |
| `StartCapture` (7) | `-2`; service log `un-supported mtal ioctl: MTAL_IOCTYPE_MTVENC … NR 0x1c` | MEASURED |
| `TriggerNextframe420` (4) | `-2`; same unsupported `MTVENC` ioctl (NR 0x1f) | MEASURED |
| `GetHimemeorybuffer` (20) | `ret=0`, empty `hidl_memory` (size 0, no handle) | MEASURED |
| `SetTrigger` (17) | `0` (26 ms) | MEASURED |
| `GetframeBuffer(0,0,1280,720,0)` (19) | `-1` | MEASURED |
| `GetConfig` (6) after init | reply still does not parse as `int32 + one buffer` | MEASURED; layout UNKNOWN |
| `GetframeBuffer420(hidl_memory)` (10) with an app-allocated 1,382,400-byte ashmem buffer | Transaction rejected (`FAILED_TRANSACTION`); `avc: denied { use } scontext=hal_tv_mtkmtalservice_default tcontext=untrusted_app_27 tclass=fd`. Policy grants this fd use to no app domain | MEASURED |
| `GetframeBuffer420` (10) with a buffer from `android.hidl.allocator@1.0::IAllocator/ashmem` (policy lets the service use `hal_allocator` fds; apps are `hal_allocator_client`) | Call reaches the service; `ret=-2`; service log `un-supported mtal ioctl: MTAL_IOCTYPE_MTVENC … NR 0x21`, `[VSSService]_MTVSS_GetframeBuffer420() m_ret=-2`. Same after `SetTrigger` | MEASURED |
| `DisConnectEx(0)` (16) | `0` (cleanup) | MEASURED |

The YouTube activity stayed in the foreground throughout. No crash of the vendor service was seen.

**Why `GetframeBuffer` cannot work remotely.** In `libmtal.so`, `MTVSS_GetFrameBuffer` (0x73d48, Thumb) takes four ints plus a fifth argument that is a destination pointer. It returns `-1` without calling the driver when that pointer is 0. Otherwise it passes it to ioctl `0xc0141f05`, which writes the frame there. Over HIDL that fifth argument is a raw `u32`, so it would be an address inside the vendor service's process. A remote app cannot supply a valid one, and guessing would make the kernel write into arbitrary vendor memory. This was not attempted. SOURCE-CONFIRMED (disassembly).

**Second attempt: the client-buffer path.** `GetframeBuffer420` takes a `hidl_memory` from the caller, which could avoid the pointer problem. It fails twice over:
- SELinux forbids the service from using a file descriptor that came from `untrusted_app_27`, so the call never reaches the service (MEASURED).
- In `libmtal.so`, `_MTVSS_GetframeBuffer420` and `_MTVSS_GetframeAddress420` both use ioctl `0xc0042921`, and `MTVSS_Init_v` uses `0x4040291a`. All are ioctl type `0x29`, the `MTVENC` type the kernel rejects (SOURCE-CONFIRMED).
- Passing a buffer from the system HIDL allocator gets past the SELinux check. The service then fails on exactly that unsupported `MTVENC` ioctl (MEASURED). This confirms that the kernel, not permissions, is the final block.

**Conclusion.** VSS is reachable, initialisable and sees the video, but on this firmware it delivers no frames to an app. The shared-memory paths (`StartCapture`, `TriggerNextframe420` → `GetHimemeorybuffer`/`GetframeBuffer420`) depend on a video-encoder (`MTVENC`) driver function this kernel lacks, and SELinux additionally blocks an app-supplied buffer. The direct path needs an in-process pointer. **Closed as a capture route** (MEASURED + SOURCE-CONFIRMED). The DRM question did not arise, because no frame was obtained.

**Product constraints if it works:** the capture would have to live in a separate helper APK targeting SDK 27. It is an undocumented vendor interface that firmware updates could change. It must not be used if it exposes DRM-protected video.

## 7. Experimental methodology

One experiment build (branch `exp/ambilight-capture-research`), installed on the TV with the owner's go-ahead:

- `MediaProjectionFrameSource` polls `files/capture_exp` (written over `adb shell run-as`). That lets one projection session switch between: production path; `rgba W H` / `yuv W H` (acquire and close only, LED processing bypassed); and `off` (surface detached).
  - Switching uses `VirtualDisplay.resize` + `setSurface`. One consent tap covers all configs.
- Debug-only `VideoHarnessActivity` (SurfaceView vs TextureView, stock `MediaPlayer`, local 1080p clip).
- Measurement per config:
  - 6 s settle, then 3 × 2.2 s windows of `dumpsys SurfaceFlinger --latency <YouTube BLAST layer>` (FPS and maximum frame gap);
  - `mmdisp` CPU from `top`;
  - SurfaceFlinger "GPU missed frame count";
  - app-side window log (frames acquired, format, maximum gap).
- Perfetto: 10 s traces, ambilight ON vs OFF, YouTube 1080p. Data sources: sched, atrace `gfx view hal video input`, all apps, FrameTimeline. Analysed with `trace_processor`.
- For Experiments A–C and F, YouTube content was started by the owner. In an early test the agent launched the video with `adb am start`. The SurfaceFlinger dump from that run shows the YouTube layer mirrored into the capture display (`… (Mirror)`), and it measured 8–10 fps, so that content was captured. One earlier ~50 fps sample remains unexplained, most likely startup or buffering.

## 8. Results

### Experiments A, B, C (single projection session, YouTube 1080p)

| Config | YouTube fps (3 windows) | Max gap ms | mmdisp CPU | Label |
|---|---|---|---|---|
| Production (RGBA 160×90 + LED processing) | 11.5 / 11.5 / 11.6 | 101 | 82% | MEASURED |
| C: RGBA 160×90, LED bypassed | 11.7 / 11.5 / 11.4 | 117 | 82% | MEASURED |
| B: RGBA 80×45 | 11.5 / 11.5 / 11.6 | 117 | 87% | MEASURED |
| B: RGBA 320×180 | 11.7 / 11.7 / 11.3 | 117 | 82% | MEASURED |
| A: YUV_420_888 160×90 (0 frames accepted, see §5) | 11.4 / 11.6 / 11.4 | 117 | 92% | MEASURED |
| Projection held, surface detached | 30.0 / 30.0 / 30.0 | 34 | 0% | MEASURED |
| Production again | 11.3 / 11.4 / 11.8 | 101 | 82% | MEASURED |

### Experiment F (Perfetto, 10 s each, YouTube 1080p)

| Metric | Ambilight ON | Ambilight OFF | Label |
|---|---|---|---|
| SurfaceFlinger `present` count | 102 | 293 | MEASURED |
| `present` avg / max | 86.2 / 106.6 ms | 2.4 / 3.5 ms | MEASURED |
| `present for Ambilight` avg | 82.3 ms | — | MEASURED |
| `REThreaded::drawLayers` (Ambilight) avg | 78.8 ms | — | MEASURED |
| `HIDL::IMmDisp::mmDetileFromHtoGB::client` (in surfaceflinger) count / avg / max | 102 / 75.2 / 95.3 ms | — | MEASURED |
| Same call, server side in mmdisp, running-on-CPU share | 71.8 of 74.7 ms (96%), thread `HwBinder:513_1` | — | MEASURED |
| mmdisp total CPU in 10 s | 7456 ms | 121 ms | MEASURED |
| `IMmDisp::mmDispFlip` (normal scan-out path) | — | 0.27 ms avg | MEASURED |
| FrameTimeline (surfaceflinger) | 108 × "SurfaceFlinger GPU Deadline Missed" | 300 × "None" | MEASURED |

### Experiment E (SurfaceView vs TextureView harness)

Blocked:

- `OMX.MS.AVC.Decoder` and `OMX.MS.AV1.Decoder` fail with `ERROR(0x80001000)` under the stock `MediaPlayer` on the public 1080p Big Buck Bunny test clips, in **both** SurfaceView and TextureView (`setPortMode on output to DynamicANWBuffer failed w/ err -1010`). This happened with no other decoder client active.
- The AV1 run delivered one real-pixel frame (3037.5 KiB, format `0x15`) before stalling (**MEASURED**).
- What's needed to unblock: a clip and container the MStar OMX components accept through `MediaPlayer`/`MediaCodec` (e.g. an encoding known to play in the stock TV player), or a Media3 build. Media3 was not added, to avoid a new dependency.

## 9. Confirmed findings vs hypotheses

| Statement | Label |
|---|---|
| The stall is a synchronous vendor call `IMmDisp::mmDetileFromHtoGB` inside SurfaceFlinger's GPU composition of the capture display, ~75 ms at 1080p, almost all CPU time on one thread | MEASURED |
| SurfaceFlinger's whole present loop is held back by it (102 vs 293 presents / 10 s; GPU-deadline jank) | MEASURED |
| Capture output size, output format and app-side LED processing do not matter | MEASURED |
| YouTube's layer buffers are pixel-less descriptors in vendor format 0x13; pixels stay in decoder memory | MEASURED (size, format) / INFERRED (location of pixels) |
| The detile output is the YV12 `externalbuffer-allocator` GraphicBuffer | INFERRED (matching size, format, usage) |
| Cost scales with source pixel count (1080p ~9–11 fps, 720p ~21, 480p 30) | MEASURED (fps); per-call time at 720p/480p not traced |
| Chromecast is cheaper because mediashell renders RGBA itself and the mirror samples an ordinary buffer | INFERRED (RGBA 1279×719 layer measured; mediashell's internal path not traced; no ambilight-off baseline) |
| YouTube reaches the descriptor path through tunneled playback | HYPOTHESIS |
| A TextureView/GPU render path in the player would avoid the stall | UNKNOWN (Experiment E blocked; the detile might then run once per frame in the app instead) |

## 10. Candidate solutions and trade-offs

| Option | Effect (evidence) | Cost / risk |
|---|---|---|
| Keep pipeline; use YouTube quality 480p or 720p while ambilight runs | 480p: 30 fps; 720p: ~21 fps (MEASURED). YouTube keeps the chosen quality (owner confirmed) | Softer picture on a 1080p panel |
| Capture-rate cap (surface detach/reattach) | 2 Hz at 1080p: ~23.5 fps but each capture still hitches ~117 ms; 10 Hz at 720p: ~22 fps with system_server ~45% (MEASURED, earlier session). Owner judged it laggy | Rejected |
| Change capture output size or format | No effect (MEASURED A/B) | Rejected |
| Native-format capture (consume the tiled/descriptor frame without conversion) | Only possible where no GPU composition happens: HWC virtual-display composition (not offered, MEASURED) or a vendor capture HAL. `libmtal`/`/dev/mtal` is root-only (MEASURED). `IMtkVssService` is reachable from a targetSdk 27 app but delivers no frames on this firmware (MEASURED, §6.1) | Blocked for a normal app |
| Privileged capture APIs | Same SurfaceFlinger GPU path or need a platform signature | Violates constraints; no expected gain |
| Vendor fix: multithreaded or GPU detile, or HWC virtual-display / writeback support | Would attack the measured 75 ms call directly | Needs the TV vendor (MStar/MediaTek firmware) |
| Pause capture while high-resolution video plays | Avoids the stall | Owner rejected: no ambilight during YouTube |

## 11. Research Provenance and Contributions

This was a human-directed, AI-assisted engineering investigation. The project owner set the problem, the constraints and the main hypotheses, ran the on-device playback conditions, and made the go/no-go calls. Claude Code (the agent) carried out the instrumentation, experiment code, trace analysis, reverse engineering and the agent-run experiments described in §6–§8. The owner did not write or run the experiment code. Where a hypothesis was first suggested by the agent, or was reached by both parties independently, this is stated. Evidence labels (MEASURED, SOURCE-CONFIRMED, INFERRED, HYPOTHESIS, UNKNOWN) are used as in the rest of this report.

### Project owner: problem definition, hypotheses, experimentation and decisions

**Problem identification and scoping**
- Identified the defect: YouTube's native app plays with ambilight enabled, but its frame rate drops significantly.
- Compared against Chromecast and AirPlay, which play normally with capture running. This moved the investigation from a general "capture is slow" framing to a YouTube-specific video-path problem.
- Provided the research brief that set Experiments A–F, the evidence standards and the constraints. The constraints were rootless and unprivileged production deployment, no DRM bypass, no modification of YouTube or vendor partitions, and no regression of the existing pipeline.

**Technical hypotheses**
- *Rendering-path difference:* proposed that YouTube's direct video path, compared with Chromecast's app-rendered path, explains the asymmetry, and studied the flow separately ("via media frame instead of via IMmDisp").
- *Pixel-format translation as the cost:* reached independently in a separate study. The agent had proposed a per-frame mmdisp conversion in the CLI session, and the owner framed it as format translation. Both lines converged on the same mechanism (see the status list below).
- *Native-format capture:* proposed, in a separate study, consuming YouTube's native buffer format directly to avoid the conversion.
- *Alternative hardware capture routes:* proposed looking beyond MediaProjection tuning. Named `/vendor/lib/libmtal.so` and, by the owner's account from a separate investigation of the MStar VSS capture backend, `IMtkVssService` as candidate rootless capture paths. Also raised repackaging the vendor library as a possible workaround.

**On-device experimentation and evaluation**
- Ran every playback condition on the TV: YouTube at 1080p, 720p and 480p, ambilight on and off, each experiment build, and continuous playback during the VSS probe runs.
- Evaluated perceived playback quality, which is not captured by fps counters. Found the throttled builds "still very laggy" even where the counters improved (for example ~23.5 fps at a 2 Hz capture cap).
- Verified that YouTube keeps the selected quality across videos, the precondition for the resolution workaround.
- Flagged that agent-launched (`adb am start`) content might not be captured. The dump later showed it was captured (§7), but all later measurements used owner-started content as a result.

**Engineering decisions**
- Rejected capture-rate throttling on playback quality, and rejected pausing capture during video because ambilight must stay active during YouTube (§10).
- Accepted the outcome of the `libmtal.so` check and declined to pursue repackaging the vendor library for production.
- Authorised the stepwise VSS probing, from read-only calls to stateful ones. After the frame paths were shown to be unsupported, removed the probe from the tree and kept the findings as documentation only.

### Claude Code (AI agent): implementation, measurement and analysis

- **Instrumentation and measurement:** SurfaceFlinger and latency measurements. Identified `mstar.hardware.mmdisp` as the busy service and tied its CPU load to capture activity. Found the 1.83 KiB descriptor buffers and YV12 `externalbuffer-allocator` buffers in the gralloc dump.
- **Agent-originated experiments:** proposed and built the capture-rate throttle builds, later rejected by the owner. Proposed the resolution-dependence test after a 480p AV1 video played smoothly.
- **Experiment implementation:** the experiment build (config switcher, video harness). Ran Experiments A, B, C and F; attempted E.
- **Trace analysis:** Perfetto capture and SQL analysis that identified `IMmDisp::mmDetileFromHtoGB` as the stall (§8).
- **Vendor-path reverse engineering:**
  - `libmtal.so`: exports, dependencies, device-node references, `/dev/mtal` permissions and SELinux policy, and the app-UID open test (§6).
  - `IMtkVssService`: VINTF and SELinux analysis (the `untrusted_app_27` grant), the targetSdk 27/34 probes, transaction order recovered from the proxy library, the read-only and stateful call sequences, the client-buffer and allocator-buffer attempts, and disassembly of `MTVSS_GetFrameBuffer` and the 420-path ioctls (§6.1).
- **Source research:** AOSP sources and documentation (§12).

### Status of the owner's hypotheses

- *Pixel-format translation is the bottleneck:* **validated (MEASURED).** The trace shows a synchronous conversion of YouTube's vendor-format frame (`mmDetileFromHtoGB`, ~75 ms at 1080p) whenever the capture display is GPU-composited. The measured operation is specifically a detile from the decoder's tiled layout into a YV12 GraphicBuffer. Origin: agent-suggested in the CLI session, independently reached by the owner.
- *Native-format capture:* **reasoning confirmed, not achievable from a normal app on this firmware.** Avoiding the conversion is exactly what would fix the stall. HWC offers no virtual-display composition (MEASURED). `libmtal`/`/dev/mtal` is root-only (MEASURED). `IMtkVssService` is callable from a targetSdk 27 app, but its frame paths depend on unsupported `MTVENC` ioctls or an in-process pointer (MEASURED + SOURCE-CONFIRMED, §6.1). It remains the right direction for a vendor-level fix.
- *Alternative capture APIs:* surveyed in §6. Public SDK and NDK paths all go through the same SurfaceFlinger GPU composition (SOURCE-CONFIRMED / INFERRED). Privileged and vendor paths are out of scope or inaccessible.
- *Direct vendor capture through `libmtal.so`:* **ruled out for an unprivileged app (MEASURED).** The library loads, but file permissions and SELinux close `/dev/mtal` to app UIDs. A repackaged copy hits the same wall. Access would need root, a policy change or platform signing, all outside the constraints.
- *Rendering-path difference (Chromecast vs YouTube):* **consistent with the evidence, not proven (INFERRED).** Chromecast's layer is an ordinary RGBA buffer, but the decisive harness experiment (E) is blocked.

## 12. References (accessed 2026-11-22)

- AOSP `PixelFormat.aidl`: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/graphics/common/aidl/android/hardware/graphics/common/PixelFormat.aidl
- AOSP `BufferUsage.aidl`: https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/graphics/common/aidl/android/hardware/graphics/common/BufferUsage.aidl
- AOSP `graphics-base-v1.0.h`: https://raw.githubusercontent.com/aosp-mirror/platform_system_core/main/libsystem/include/system/graphics-base-v1.0.h
- Implementing Hardware Composer HAL (virtual display composition): https://source.android.com/docs/core/graphics/implement-hwc
- SurfaceFlinger and WindowManager / HWC: https://source.android.com/docs/core/graphics/arch-sf-hwc
- Android graphics architecture overview: https://source.android.com/docs/core/graphics
- Perfetto documentation (trace config, trace_processor): https://perfetto.dev/docs/

The AOSP HWC pages say nothing about sideband streams. The meaning of `SidebandStream` in this device's HWC capability list was not researched further.

## 13. Recommended next steps

1. **Product:** keep the current capture pipeline. Document in the Ambilight help text that high-resolution YouTube stutters during capture on this TV, and that 480p (smooth) or 720p (mild stutter) works.
2. **Optional measurement:** trace `mmDetileFromHtoGB` at 720p and 480p to replace the fps-derived scaling with direct per-call times.
3. **Optional, Experiment E:** unblock with a decoder-friendly clip to test whether a GPU render path moves or removes the cost.
4. **Vendor route:** if a vendor contact exists, the evidence to share is the single-threaded CPU detile (`mmDetileFromHtoGB`, ~75 ms per 1080p frame) and the missing HWC virtual-display support.
5. Remove the experiment build from the TV (reinstall `main`) and keep the branch unmerged as a record.
6. **VSS (Experiment G): closed.** No frame path works on this firmware. If a vendor contact exists, ask whether `MTVENC` VSS support (the `StartCapture`/`TriggerNextframe420` ioctls) can be enabled. That would turn VSS into a proper capture API.
