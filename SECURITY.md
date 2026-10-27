# Security

Do not report private keys, PINs, local addresses, notification bodies, or device configuration in public issues. If repository private vulnerability reporting is available, use it for sensitive reports; otherwise request a private contact without disclosing the vulnerability. No security contact or reporting service is configured by this scaffold.

This is a development foundation. Notification transport is unencrypted LAN HTTP with a per-install six-digit PIN. Release transport encryption and stronger pairing remain open work. Debug lamp provisioning requires authorized ADB and a debuggable application; it is not a release onboarding design.

Lamp secrets are encrypted at rest with Android Keystore, excluded from backup, and never embedded in the APK. Do not expose local services to the public internet. Notification content remains transient; default privacy presentation hides supplied message content.
