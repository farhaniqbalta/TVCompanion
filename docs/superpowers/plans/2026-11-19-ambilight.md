# Ambilight Implementation Plan

> **For agentic workers:** Use Superpowers test-driven development and verification before completion. The owner-supplied final handoff defines the design and device gates.

1. C0. Record the baseline, module boundary and diagnostic support matrix. Verify the dedicated branch against origin/main.
2. C1. Add pure JVM fixtures before implementing layout, RGBA sampling, smoothing, cadence and DDP. Run the engine suite offline.
3. C2. Exercise session transitions and stale events with fakes. Integrate an Android 14 foreground service with one capture looper and one bounded control executor. Verify build and manifest before requesting TV installation.
4. C3. Add native settings, persistence and the dashboard entry. Run Android JVM tests, lint and builds. Verify remote focus and app switching on the TV after approval.
5. C4/C5. Record hardware/runtime evidence separately from automated checks. Verify physical mapping, WLED stop semantics, 20/30 FPS comparison and soak before a native optimization decision.
