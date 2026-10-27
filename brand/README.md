# TVCompanion identity

A rounded TV frame represents the shared TV experience. The amber dot represents a companion update arriving without taking over the screen. The mint outline and charcoal background match the application UI.

- `mark.svg`: transparent, square master mark.
- `banner.svg`: 320×180 TV launcher composition with an original geometric wordmark.
- Android assets: adaptive launcher foreground/background, monochrome variant, TV banner, in-app mark and foreground-service notification icon.

Palette: charcoal `#0E171C`, mint `#A2E3D0`, amber `#EFD29A`, and white `#F2F5F4`. Keep space around the mark and preserve the dot; do not stretch the symbol or add animation/glow to it.

Regenerate SVG and Android vector assets from the repository root:

```sh
python3 scripts/generate_brand_assets.py
```

The generator uses the Python standard library. Geometry and lettering are project-authored; no external font files, raster images or logo packages are bundled. Application text remains normal accessible native text rather than vector lettering. The outlined wordmark is reserved for the static launcher banner.

Android supports adaptive launcher masking and a monochrome layer. The TV banner uses the same mark plus readable branding, while the service notification icon is a separate white-on-transparent vector. Delivery-source pictograms remain separate from the application's identity.

These assets do not imply a repository license; license selection remains pending.
