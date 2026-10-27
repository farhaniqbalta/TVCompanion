# Android TV UI/UX refresh — 2026-10-28

## Research

The refresh uses primary Android documentation as guidance. These recommendations inform our design; the specific layouts and colors below are product choices rather than prescribed Android templates.

| Source | Relevant guidance | TVCompanion application |
| --- | --- | --- |
| [TV navigation](https://developer.android.com/training/tv/get-started/navigation) | Minimize navigation steps; make focused actions obvious; prefer natural directional navigation | Two whole-card home targets, direct light power action, stable focus when returning, category rail for settings |
| [TV focus system](https://developer.android.com/design/ui/tv/guides/styles/focus-system) | Distinguish focused, pressed, selected and disabled states using outlines, surfaces, and content | Two-dp focus outline and tinted fill; selected settings remain visually separate from the focused control; no continuous focus animation |
| [Typography](https://developer.android.com/design/ui/tv/guides/styles/typography) | Favor legible type and a consistent hierarchy for viewing from a distance | System sans-serif, short 30–36sp screen headings, 17–18sp actions, concise secondary copy |
| [TV layouts](https://developer.android.com/design/ui/tv/guides/styles/layouts) | Use density-independent sizing and overscan-safe content margins | 48dp horizontal and 27dp vertical content padding with edge-to-edge static background |
| [TV cards](https://developer.android.com/design/ui/tv/guides/components/cards) | Group a single topic within each card and keep supporting copy concise | One light card and one notification card, each with a single focus target and short description |
| [Android settings guidelines](https://source.android.com/docs/core/settings/settings-guidelines) | Group related preferences and use progressive disclosure for advanced settings | Receiver, Appearance, Sources, Connection; address/port/PIN are outside the daily receiver flow |

## Findings in the previous implementation

The home page spent vertical space on two stacked descriptions and separate buttons. Light controls were a long column, requiring navigation past redundant power actions. Notification preferences, connection credentials, previews and service actions competed in two scrolling columns. Text and focus styling lacked a shared hierarchy.

## Design decisions

- Home is a quiet two-card dashboard with a warm light accent and mint notification accent. Card artwork is small native vector linework, not a bitmap illustration or animation.
- Light controls use a device/status panel beside power and white brightness controls. A single explicit power action changes its label from Turn on to Turn off based on confirmed state. It never acts automatically when the screen opens. The existing controller rejects input during a pending operation and still requires readback.
- Refresh remains a secondary action. After the initial successful read, focus can move from Refresh to the useful power action if the user has not moved away. Error and unsupported-mode explanations remain visible.
- Notification settings use explicit category selection. A persistent preview stays beside the settings at normal TV width. Narrow or enlarged-text layouts place it below the settings in the same scroll flow. Source preview actions retain focus while only the sample card is replaced.
- Style and duration choices are explicit dialogs rather than cycling through hidden values. PIN reset has a confirmation because it pauses reception and invalidates existing sender credentials. The PIN is masked until requested and hidden again when leaving the screen.
- The previously working 280dp non-interactive overlay renderer and its timing/queue rules are preserved. This refresh does not change notification transport or lamp protocol behavior.
- Display strings for the refreshed screens are in Android string resources to support later localization.
- No polling, UI timer loop, blur, heavy image assets, new UI runtime or background service is introduced.

## Launcher shortcut boundary

[CleanYourTV card spec 1](https://github.com/Gathin23/cleanyourtv/blob/main/docs/specs/card.md), inspected in the local launcher checkout, supports one static app-launch card per package, with no embedded action button or action extras. The UI refresh does not add a launcher declaration or silently execute lamp commands on launch. A direct Lamp off tile needs a separate integration decision; it cannot be represented as an embedded control in the current card contract.

## Validation

See [verification](VERIFICATION.md) for actual checks. Build success is distinct from physical remote confirmation. No CPU/RAM benchmark is requested.
