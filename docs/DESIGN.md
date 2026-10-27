# TV design direction

The UI refresh is grounded in [Android TV UI/UX research](UI_UX_RESEARCH.md). Use quiet charcoal surfaces, a mint focus accent, warm light accents, generous spacing, and a consistent system typeface. Important content stays within 48dp horizontal / 27dp vertical safe margins.

## Screen structure

Home contains two large whole-card targets: Your light and Quiet updates. Each has one action and concise supporting copy. Returning from notification settings restores that home card's focus. No placeholder remote-control tile is shown on Android TV.

The light page keeps device status beside its primary power action and white-brightness step controls. Status, pending work, unsupported modes and unconfirmed errors are explicit. A displayed value only changes after the controller receives confirmed device state. There is no automatic command on entry and no lamp polling.

Notifications use Receiver, Appearance, Sources and Connection sections, with a persistent local preview at normal TV width. On narrow/large-text layouts the preview sits below the section in the scroll flow. Advanced connection details do not compete with everyday settings. PINs are hidden by default and a destructive reset is confirmed.

The existing small popup renderer remains shared by the local sample and overlay. Overlay dimensions and non-focusable/non-touchable behavior are preserved.

## Components and architecture

Shared tokens, vector icons, text, focus surfaces, cards and buttons live in `core:designsystem`. The UI uses native Android Views without an additional framework runtime. Domain/application code remains independent of Android UI and vendor protocols. Presentation can be replaced again without rewriting device adapters.

Distinguish focus with a clear border and filled surface. Selection is a quieter tint. Disabled actions have lower contrast and are skipped by directional navigation. Preserve focus while asynchronous work completes; repeated actions are rejected by the existing controller instead of building a command queue.

Use natural D-pad navigation, with Back returning to the preceding screen. Controls are scrollable when screen size or font scale needs it. Text for the refreshed screens is stored in resource XML; English is currently the only translation.

No looping animation, blur, bitmap background, polling, or recurring UI refresh is needed. Render in response to user actions and state events. New illustration or transitions must respect these resource constraints.

## Brand identity

The [TVCompanion identity](../brand/README.md) uses a rounded mint TV frame and amber companion dot. Adaptive launcher artwork, the wordmark banner, in-app headers and service notification icon derive from one generator. All artwork is vector-based, with no runtime animation or bitmap loading. Delivery-source icons retain their own meaning.
