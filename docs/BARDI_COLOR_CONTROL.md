# Bardi color-control preview

Candidate: **0.6.0-bardi-color-preview**, versionCode 10. Branch: `feature/bardi-color-control`, based on the pushed AirPlay UI checkpoint `1f60f7d`. Color control is locally implemented and the candidate was subsequently installed at the user’s request. No lamp commands were sent by the agent; the user subsequently confirmed that all requested controls work.

## Supported profile and evidence

This extends the existing single-lamp Tuya 3.5 LAN adapter, not a generic device-discovery system. Prior POC records correlate the current BARDI Light Bulb 9W RGBWW schema with power DPS20, mode DPS21, white brightness DPS22 and color DPS24. Allowlisted schema fields and the format of saved status were reviewed without printing credentials, device identifiers or current color values. The schema allows `white` / `colour` / `scene` / `music`; HSV ranges are hue 0–360 and saturation/value 0–1000. The saved DPS24 is a 12-character hexadecimal string. No private source file is copied into the public repository or required by its build.

The LAN encoding follows the documented type-B/HSV16 layout `hhhhssssvvvv`: three four-digit hexadecimal values. [TinyTuya source documentation](https://github.com/jasonacox/tinytuya/blob/master/tinytuya/BulbDevice.py) independently documents DPS24 and these ranges. This is format evidence, **not itself validation of new color writes on this lamp**; subsequent user testing is recorded below. Other profiles, old RGB+HSV encodings, malformed values, unsupported modes and absent color status do not enable color commands.

## UI and behavior

- Six named presets: red, orange, yellow, green, blue and purple. Presets set full saturation and retain color brightness; when switching from white, the confirmed white percentage is mapped approximately to the color-value scale. This does not guarantee equal perceived brightness between white and RGB emitters. If white brightness is unavailable, stored color brightness is retained.
- Hue adjusts by 15 degrees and wraps; saturation and color brightness adjust by 10 percentage points and clamp to their bounds. Zero color brightness is valid and produces no visible light.
- A color action explicitly switches to color mode. **Use white mode** switches back without modifying stored white brightness, color temperature or power. Existing white-brightness controls remain limited to powered-on white mode.
- The screen shows confirmed mode/HSV values and a selected preset only after readback. In white mode the HSV display is explicitly labeled as stored color. Presets include text labels; accessibility descriptions distinguish hue/saturation/value adjustments.
- Color controls require the lamp on, a known white/color mode and valid DPS24. Scene/music modes are not overridden or probed. The user can change those modes in the existing lamp app and refresh. Color commands never implicitly turn the lamp on.
- A wide TV uses columns for existing device/white controls; narrow/large-text layouts stack. The color panel remains scrollable. No polling, drag-stream writes or animation loop is added.

## Boundaries and command confirmation

`LightColor`, `LightCommand.Color` and `LightCommand.WhiteMode` are pure domain models. The controller enforces capability checks and one pending operation; the feature owns pure adjustment policy and Android presentation. The Tuya module alone parses/encodes DPS values. No native AirPlay or notification dependency is introduced.

A fresh pre-write status must support the requested action. Color mode plus DPS24 are submitted in **one control packet**, never two sequential writes. Low-level serialization accepts only the narrow allowlisted groups: existing single power/white-brightness writes, single white-mode selection, or the two-field color/mode group. It rejects standalone color data, unsupported modes, mixed power/color groups and out-of-range HSV.

After the existing bounded settling delay, a new session reads back every requested value. Color confirmation accepts hex letter-case differences and equivalent hue 0/360; mode must also read `colour`. A matching color with an unconfirmed mode is a failure. Unconfirmed writes clear actionable controller state and require Refresh; no retry, automatic rollback or optimistic color update is added. Existing socket deadlines, cancellation and cleanup remain in force.

## Software verification and user acceptance

APK build and **105 JVM tests** pass, including existing AirPlay/notification/lamp suites, codec boundaries, grouped packet validation, mode-and-color readback, refusal while off/unsupported, white-mode restoration and pure adjustment/controller behavior. Lint has zero errors and the existing ChromeOS ABI warning. No CPU/RAM benchmark was requested or run.

After installation, the user reported “all works” in response to testing presets, hue/saturation/color-brightness adjustments and return to white mode. This records user acceptance of the requested Bardi functionality; no claim is made about exhaustive firmware compatibility, fault recovery, offline operation or benchmarking. Existing private configuration was preserved.

## Authorized home installation

The user returned home and requested installation. VersionCode 10 was successfully installed as an update with the same application identity. Existing preference files were verified byte-for-byte preserved; the notification receiver was not running before the update. Only the dashboard was opened. No lamp write or AirPlay session was started. The user subsequently confirmed the requested Bardi controls work. ADB closure remains unverified; no device settings were changed as part of recording acceptance.

Installed APK SHA256: `9350abdd5dfa192af80eeaeaeec12ebd8d7041cbe9c0d9b743727b67cce56920`.
