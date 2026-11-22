# Ambilight capture experiments: commands, configs and raw data

Companion to [ambilight-capture-bottleneck.md](ambilight-capture-bottleneck.md). All runs were on 2026-11-22.

## Test environment

- TV: SWTV-22AE-FHD, mt5867, Android 14 (SDK 34), Mali-G31, reached over Wi-Fi with `adb connect <tv-ip>:5555`.
- App: TVCompanion debug build from branch `exp/ambilight-capture-research` (versionName `0.8.1-phase3-boot-preview`).
- Content:
  - YouTube for Android TV (`com.google.android.youtube.tv`), playing the same AV1 video at the quality set by the owner. Content was always started by the owner on the TV, never with `adb am start`.
  - Public test clips for the harness: Big Buck Bunny 1080p, 10 s, H.264 and AV1, from test-videos.co.uk.
- Host tools: adb, Perfetto `trace_processor` (from https://get.perfetto.dev/trace_processor), JDK 17 for the Gradle build.

## Experimental configurations

The experiment build adds a switcher to `MediaProjectionFrameSource`. It polls `files/capture_exp` every 500 ms:

| Spec written to `files/capture_exp` | Behaviour |
|---|---|
| (empty / missing) | Production path: RGBA 160×90 ImageReader, edge sampling, UDP/DDP to WLED |
| `rgba W H` | New RGBA ImageReader W×H; `VirtualDisplay.resize(W,H)` + `setSurface`; images acquired and closed only (LED processing bypassed) |
| `yuv W H` | Same with `ImageFormat.YUV_420_888` |
| `off` | `setSurface(null)`: projection and virtual display kept, nothing composited |

Each window logs `AmbilightExp: window spec=… frames=… fps=… format=… gapMaxMs=…`.

Debug-only harness:

```sh
adb shell am start -n id.poncol.tvcompanion/.app.VideoHarnessActivity --es view surface|texture --es file <clip in app files/>
```

## Reproducible commands

Switch a config inside a running session:

```sh
adb shell "run-as id.poncol.tvcompanion sh -c 'echo \"rgba 160 90\" > files/capture_exp'"
```

Video-layer FPS and largest frame gap (repeat after `--latency-clear`):

```sh
L=$(adb shell dumpsys SurfaceFlinger --list | grep -m1 '^SurfaceView\[com.google.android.youtube.tv.*(BLAST)')
adb shell dumpsys SurfaceFlinger --latency-clear; sleep 2.2
adb shell "dumpsys SurfaceFlinger --latency '$L'" | awk 'NR>1 && $2>0 && $2<9e18 {t[n++]=$2}
  END{span=(t[n-1]-t[0])/1e9; mx=0; for(i=1;i<n;i++){d=(t[i]-t[i-1])/1e6; if(d>mx)mx=d}; printf "%.1f fps, max gap %.0f ms\n",(n-1)/span,mx}'
```

Vendor CPU and GPU-missed counter:

```sh
adb shell 'top -b -n1 -d1' | awk '/mmdisp/{print $9}'
adb shell dumpsys SurfaceFlinger | grep '^GPU missed'
```

Buffer descriptors (sizes, formats, usage):

```sh
adb shell dumpsys SurfaceFlinger | grep -E '^\+ name:(SurfaceView\[com.google.android.youtube|externalbuffer)'
```

Full cycle script used for Experiments A/B/C (`cycle.sh <layer-regex> <out>`):

```sh
setspec(){ adb shell "run-as id.poncol.tvcompanion sh -c 'echo \"$1\" > files/capture_exp'"; }
for spec in "" "rgba 160 90" "rgba 80 45" "rgba 320 180" "yuv 160 90" "off" ""; do
  setspec "$spec"; sleep 6   # settle after each switch
  # three 2.2 s latency windows + mmdisp CPU + GPU missed (commands above)
done
setspec ""
```

## Trace collection (Experiment F)

Config `trace.cfg`:

```
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_wakeup" ftrace_events: "sched/sched_waking"
  ftrace_events: "power/cpu_frequency" ftrace_events: "gpu_mem/gpu_mem_total"
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "hal" atrace_categories: "video" atrace_categories: "input"
  atrace_apps: "*"
} } }
data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
duration_ms: 10000
```

Record and pull:

```sh
adb shell perfetto --txt -c - -o /data/misc/perfetto-traces/on_1080.pftrace < trace.cfg
adb pull /data/misc/perfetto-traces/on_1080.pftrace .
```

Queries:

```sql
-- top compositor/vendor slices
select p.name proc, s.name slice, count(*) n, round(sum(s.dur)/1e6,1) total_ms,
       round(avg(s.dur)/1e6,2) avg_ms, round(max(s.dur)/1e6,1) max_ms
from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) join process p using(upid)
where p.name like '%surfaceflinger%' or p.name like '%mmdisp%' or p.name like '%composer%'
group by 1,2 order by total_ms desc limit 25;

-- detile: wall time vs running-on-CPU time of the executing thread
with d as (select s.ts, s.dur, t.utid from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid)
           where s.name like 'HIDL::IMmDisp::mmDetileFromHtoGB::server')
select count(*), avg(d.dur)/1e6 wall_ms,
  avg((select coalesce(sum(min(ss.ts+ss.dur, d.ts+d.dur) - max(ss.ts, d.ts)),0) from sched_slice ss
       where ss.utid=d.utid and ss.ts < d.ts+d.dur and ss.ts+ss.dur > d.ts))/1e6 running_ms
from d;

-- FrameTimeline jank
select p.name, a.jank_type, count(*) from actual_frame_timeline_slice a join process p using(upid)
where p.name like '%youtube%' or p.name like '%surfaceflinger%' group by 1,2;
```

The trace files themselves (18–21 MB) were not committed.

## Raw measurement tables

### Run 1: YouTube 1080p (crashed at the YUV step)

```
[prod+LED]     fps/maxgapMs: 11.2/101 11.1/116 10.9/117  mmdisp=73.3% gpuMissed=113416
[rgba 160 90]  fps/maxgapMs: 11.2/117 11.1/116 11.6/116  mmdisp=82.5% gpuMissed=113595
[yuv 160 90]   fps/maxgapMs: 30.0/34 30.0/34 30.0/34  mmdisp=2.5% gpuMissed=113607   <- app crashed, capture gone
(remaining rows: no capture, valid only as an "off" baseline)
App log: window spec='' frames=215 in 23454 ms (9.2/s); window spec='rgba 160 90' frames=180 in 16156 ms (11.1/s)
Crash: UnsupportedOperationException: The producer output buffer format 0x1 doesn't match the ImageReader's configured buffer format 0x23.
```

### Run 2: YouTube 1080p (YUV acquire guarded)

```
[prod+LED]     fps/maxgapMs: 11.5/101 11.5/100 11.6/100  mmdisp=81.8% gpuMissed=113867
[rgba 160 90]  fps/maxgapMs: 11.7/101 11.5/100 11.4/117  mmdisp=82.0% gpuMissed=114052
[rgba 80 45]   fps/maxgapMs: 11.5/117 11.5/100 11.6/117  mmdisp=86.8% gpuMissed=114235
[rgba 320 180] fps/maxgapMs: 11.7/117 11.7/117 11.3/117  mmdisp=82.5% gpuMissed=114421
[yuv 160 90]   fps/maxgapMs: 11.4/116 11.6/100 11.4/117  mmdisp=92.3% gpuMissed=114607
[off]          fps/maxgapMs: 30.0/34 30.0/34 30.0/34  mmdisp=0.0% gpuMissed=114616
[prod+LED]     fps/maxgapMs: 11.3/100 11.4/101 11.8/101  mmdisp=82.5% gpuMissed=114793
App log: window spec='yuv 160 90' frames=0 (format 0x1 rejected every frame); window spec='off' frames=0
```

### Perfetto top slices (10 s)

ON (excerpt):

```
surfaceflinger  present                                        102  avg 86.22 ms  max 106.6
surfaceflinger  present for Ambilight (…)                      102  avg 82.34 ms  max 102.3
surfaceflinger  REThreaded::drawLayers                         102  avg 78.83 ms  max  99.0
surfaceflinger  HIDL::IMmDisp::mmDetileFromHtoGB::client       102  avg 75.20 ms  max  95.3
mmdisp          HIDL::IMmDisp::mmDetileFromHtoGB::server       102  avg 74.72 ms  max  94.9
mmdisp          unlockAsync                                    102  avg  4.77 ms
composer@2.4    Wait for MI OSD apply                          102  avg  8.54 ms
detile running-on-CPU: 71.8 ms of 74.7 ms wall (thread HwBinder:513_1)
mmdisp CPU total: 7456 ms;  FrameTimeline SF: 108 × "SurfaceFlinger GPU Deadline Missed"
```

OFF (excerpt):

```
composer@2.4    Wait for MI OSD apply                          293  avg 13.26 ms
surfaceflinger  present                                        293  avg  2.40 ms  max 3.5
mmdisp          HIDL::IMmDisp::mmDispFlip::server              293  avg  0.27 ms
mmdisp CPU total: 121 ms;  FrameTimeline SF: 300 × "None"
```

### Gralloc buffers (YouTube 1080p, capture ON)

```
9 × SurfaceView[com.google.android.youtube.tv/…](BLAST Consumer) | size:1.83KiB   | 1920x1080 | usage 0x42400900          | req fmt 842094169 (YV12)
1 × externalbuffer-allocator                                     | size:3037.50KiB | 1920x1080 | usage 0x4000000002402920  | req fmt 842094169
1 × externalbuffer-allocator                                     | size:3037.50KiB | 1920x1080 | usage 0x400000002402920   | req fmt 842094169
reference: FramebufferSurface 1280x720 RGBA = 3657 KiB; ImageReader 160x90 RGBA = 56.25 KiB
```

### Experiment E (harness)

```
OMX.MS.AVC.Decoder / OMX.MS.AV1.Decoder via MediaPlayer, SurfaceView and TextureView:
  ACodec: setPortMode on output to DynamicANWBuffer failed w/ err -1010
  ACodec: ERROR(0x80001000) -> NuPlayer shuts down video decoder
AV1/SurfaceView delivered 1 frame: buffer 1920x1080, 3037.50 KiB, format 0x15 (numFramesTotal=1)
```

## Interpretation criteria

- A config **matters** if the YouTube fps median moves by more than 1 fps from the production row, or mmdisp CPU moves by more than 15 points, consistently across all three windows.
- A **synchronous compositor stall** is claimed only from trace evidence: a vendor slice nested in SurfaceFlinger's composition, with the present count dropping in step. Low fps alone isn't enough.
- **CPU vs hardware wait:** the share of a slice's wall time during which its thread was `Running` in `sched_slice`.

## Experiment G: IMtkVssService probe

```sh
research/vss-probe/build.sh                 # builds out/vssprobe27.apk and out/vssprobe34.apk
TARGETS=27 research/vss-probe/build.sh      # SDK 27 only
adb install -r -t research/vss-probe/out/vssprobe27.apk
adb logcat -c; adb shell am start -W -n id.poncol.vssprobe27/id.poncol.vssprobe.Main
adb logcat -d -s 'VssProbe:*'
adb shell 'ps -A -o LABEL,NAME | grep vssprobe'   # confirms untrusted_app_27 vs untrusted_app
```

Raw output (2026-11-22):

```
targetSdk 27  (u:r:untrusted_app_27)
  getService -> android.os.HwRemoteBinder@b2e677f0
  ping ok
  descriptor -> vendor.mediatek.tv.mtkmtalservice@1.0::IMtkVssService
  GetVssVideoInform ret=-9 size=24 words: 00000000 00000000 00000000 00000000 00000000 00000000
  GetConfig no struct size in 4..1024 matched
targetSdk 34  (u:r:untrusted_app)
  java.util.NoSuchElementException at android.os.HwBinder.getService
  avc: denied { find } for interface=vendor.mediatek.tv.mtkmtalservice::IMtkVssService
       scontext=u:r:untrusted_app tcontext=u:object_r:hal_tv_mtkmtalservice_hwservice
```

Stateful sequence (YouTube 1080p in the foreground; the probe is a broadcast receiver, so YouTube is not paused):

```sh
research/vss-probe/run.sh "mtalinit,init,info"     # 0, 0, info zeros
research/vss-probe/run.sh "connect:0,info"          # 0, words 47187200 0 65536 70780800 24576 65536
research/vss-probe/run.sh "start,info,himem"        # start -2 (unsupported MTVENC ioctl), himem size 0
research/vss-probe/run.sh "next420,himem"           # -2 (unsupported MTVENC ioctl), himem size 0
research/vss-probe/run.sh "trigger,fb:0/0/1280/720/0,himem"   # 0, -1, size 0
research/vss-probe/run.sh "disconnect:0,info"       # cleanup: 0
adb logcat -d | grep ' MTAL'                        # service-side log lines
```

Struct-size method: the probe reads the reply as `status, int32 ret, buffer`. libhwbinder rejects a `readBuffer` whose length differs from the requested one, so the probe repeats the call with sizes 4, 8, …, 1024 and reports the first that parses.

Related static checks:
- `lshal -i`, the `/vendor/etc/vintf/manifest.xml` entry for `vendor.mediatek.tv.mtkmtalservice`, `vendor_hwservice_contexts`, and the compiled policy (`vendor_sepolicy.cil` + `plat_sepolicy.cil`) grep for `hal_tv_mtkmtalservice`.
- `libmtal.so`: `llvm-nm -D`, `llvm-readelf -d`, `strings`; `ls -lZ /dev/mtal`; `run-as id.poncol.tvcompanion sh -c 'exec 3</dev/mtal'` → `Permission denied`.

## Known limitations

- One device, one YouTube video, one firmware build. Each condition was measured in short windows (3 × 2.2 s, 10 s traces).
- Per-call detile time was traced only at 1080p. The 720p/480p scaling comes from fps, not traces.
- Experiment E is blocked (decoder errors), so the SurfaceView vs TextureView question is open.
- Chromecast was measured only with ambilight ON (~22.5 fps, even pacing); there is no OFF baseline and no trace.
- The `top` CPU readings are single-sample snapshots. The Perfetto CPU totals are the reliable numbers.
- An `am start -S` during the harness run force-stopped the app and ended one ambilight session. It didn't affect the recorded rows.
