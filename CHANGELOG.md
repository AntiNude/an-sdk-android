# Changelog

All notable changes to **an-sdk-android** (AntiNude Android SDK) are
documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Pre-1.0 releases (0.x) may include breaking changes between minor versions —
re-read this file before upgrading.

## [0.9.0] — 2026-05-25

First public release tag. Folds together the previously untagged 0.2 / 0.3
internal milestones plus the changes needed to call the SDK production-ready
for the pilot.

### Added
- `ANClient.Companion.SDK_VERSION` — public constant; used in the
  `User-Agent` on every telemetry call.
- `X-AntiNude-Bundle` request header carrying `context.packageName`, so the
  backend can enforce bundle-binding on production keys with a non-null
  `restriction`.
- `ANClient.create(context, ...)` automatically captures the host package id.
- `ANClient.create(apiKey, modelBytes, ..., packageId)` — optional
  `packageId` parameter on the bytes-overload for callers that supply their
  own model.
- README rewritten from scratch to reflect the real `ANClient` /
  `ScanResult` / `Detection` surface, NudeNet 320n model, JitPack install
  instructions (`com.github.AntiNude:an-sdk-android:0.9.0`).

### Changed
- `User-Agent` now sourced from `SDK_VERSION` rather than a hard-coded
  string.
- `ANClient` private constructor now takes an additional `packageId: String?`
  parameter — propagated only via the factory methods, so callers do not
  need to migrate.

### Notes (history before 0.9.0)
Earlier work shipped on `main` without git tags:
- **0.3.0** — replaced the mock with the real NudeNet 320n detector via
  ONNX Runtime 1.24.2. Introduced `Detection` and the `detections` list on
  `ScanResult`. Introduced typed `ANException` with the full `ANErrorCode`
  enum and `isRetryable`.
- **0.2.0** — first end-to-end build: on-device `scanImage(bytes)` +
  verdict-only reporting to `/api/v1/scan`.
- **0.1.0** — mock SDK without a model; simulated responses.

## Roadmap

Targeted for **1.0.0** (no firm date yet):
- Video scanning (`scanVideo(uri:fps:)` API; on-device keyframe sampling).
- Published to Maven Central (drop the JitPack dependency).
- Tier-aware backend rate limits surfacing the higher Pro ceiling.
- Our own labelled eval set and publicly reported precision / recall.
