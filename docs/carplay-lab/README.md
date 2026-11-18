# CarPlay Feature Lab

A runnable playground for the CarPlay APIs evaluated in
[`../carplay-sdk-audit.html`](../carplay-sdk-audit.html) — every candidate
feature as a small demo you can look at on the CarPlay simulator, with
settings rows to toggle the interesting knobs live.

This is **not** an example app for the library — it doesn't use
react-native-audio-browser at all. It's the empirical instrument behind the
audit: pure Swift against the raw CarPlay SDK, for answering "what does this
actually render?" questions before designing library API around them.

## Run it

1. Open `CarPlayLab.xcodeproj` in Xcode.
2. Select the **CarPlayLab** scheme and an **iOS 26+ simulator** (most
   demos are iOS 26 element APIs; the launcher grays out anything your
   destination can't show).
3. Run, then in the Simulator app: **I/O → External Displays → CarPlay**.
4. The CarPlay display shows a **Lab** tab (the demo launcher) and a
   **Badge** tab (shows `showsTabBadge`; also the target of the tab-select
   demo). Taps log to the on-phone logger window.

No team/signing needed for the simulator (the Siri entitlement was removed
for exactly this reason), no network, no accounts — all content is generated
locally.

The feature → demo map lives in the audit report, section
"Reproducing in the demo lab".

## Provenance

Grafted onto Apple's sample project _Integrating CarPlay with Your Music
App_ (MIT-licensed — see `LICENSE.txt`, notice retained). Our modifications:

- `CarPlayLab/Template Manager/TemplateManager.swift` — fully rewritten as
  the feature lab (this file is the entire lab).
- `CarPlayLab/Supporting Files/Entitlements.plist` — Siri entitlement
  removed so team-less simulator builds work.
- App icon removed (816 K of PNGs for a simulator-only lab) — the app shows
  the generic placeholder icon on the phone and in the CarPlay launcher.

Everything else is Apple's sample as shipped (the Apple Music API plumbing
it contains is unused by the lab). Snapshot taken August 2026, sample
version iOS 14.0 / Xcode 15 era, exercised against the iPhoneSimulator 26.2
SDK.
