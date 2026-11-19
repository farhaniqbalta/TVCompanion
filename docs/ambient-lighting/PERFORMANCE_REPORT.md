# Performance report

| Measure | Owner diagnostic, 160×90 long cast run | Integrated Android app |
| --- | --- | --- |
| Processed frame rate | 17.52 FPS over 410.97 s, 30 FPS cap, source cadence unknown | NOT TESTED |
| Analysis | 1.15 ms average sampling/hash, excluding image acquisition and network | NOT TESTED |
| CPU | About 3.8% of one core in the standalone process over 391.29 s | NOT TESTED |
| PSS | Approximately 20–22 MB standalone process PSS | NOT TESTED |
| System CPU delta, integrated PSS delta | NOT TESTED | NOT TESTED |
| Capture to visible light latency | NOT TESTED | NOT TESTED |
| Playback and soak | User reported no visible impact in the ~7-minute diagnostic | NOT TESTED |

The diagnostic figures come from the owner's `AMBIENT_CAPTURE_COMPATIBILITY_REPORT.md`. Its intentionally rate-limited samples do not establish maximum throughput or compositor losses.

For C2, repeat the same supported moving clip and settings with 20 FPS and 30 FPS caps in separate local builds. Change only the cadence value in `CaptureOutput` for the 20 FPS build. Compare the retrieved aggregate `Ambilight` log at Stop, including acquired and processed counts, frame-span, acquisition interval p50/p95 and processing-plus-send p50/p95. The acquisition intervals use `System.nanoTime()`. The cap uses monotonic image timestamp deltas and falls back to acquisition time if the source clock fails validation. Never subtract timestamps across those clocks. Histograms store 0.1 ms processing buckets and 1 ms interval buckets, with overflow at 10 ms and 100 ms respectively. The first processing sample includes pipeline initialization. Acquisition count excludes frames never returned by ImageReader.

Sample process and device CPU against an Off baseline under the same playback workload. Compare PSS, open FDs and thread counts before, during and after. Repeat for at least 30 minutes and through a bounded network interruption. Measure light delay with a high-frame-rate recording of both a visible timed color change and the strip. Record measurement resolution, clip cadence, firmware, and repeat counts.

**Native decision pending C5.** The implementation remains Kotlin. The standalone diagnostic does not justify migrating the app pipeline to C++. Consider a matched native benchmark only if the integrated measurement exceeds the pipeline, GC jitter or device CPU thresholds in the handoff.
