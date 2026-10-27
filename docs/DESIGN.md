# TV design direction

Use a quiet dark surface, mint accent, generous spacing, and clear typography. Focus must be visible through an outline and filled background, not color alone. Focus and pressed state belong to input handling, not decorative background animation.

Shared native primitives live in `core:designsystem`. The foundation uses Android Views to reuse the small existing notification renderer and avoid an additional UI runtime. This is a presentation choice; a future Compose screen can use the same domain and controller without changing Tuya behavior.

The home screen exposes only working entry points: Smart home and Notifications. Apple remote control belongs on Apple devices; do not display an inoperative TV remote tile. New features receive their own capability and navigation decision before joining the dashboard.

The light screen shows confirmed status, pending work, and explicit uncertainty after failures. Power and brightness are enabled only from valid current state. Brightness is restricted to the verified white-mode capability. A refresh never changes the lamp.

Keep controls scrollable with clear D-pad order, useful initial focus, and consistent Back behavior. Avoid custom key interception and touch-only controls. Notification overlays must remain non-focusable and non-touchable, never interrupt video or steal navigation by design; actual protected-player compatibility remains a hardware check.

UI strings currently live in native screen builders. English is the foundation language; extraction to localized resources is required before localized releases. The icon and vector banner are provisional project-authored assets.
