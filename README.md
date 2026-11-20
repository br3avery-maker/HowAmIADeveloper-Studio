# SovereignShell — HowAmIADeveloper Studio

Native Android project for a Nostr/nsite development and publishing workflow.

The project's [`metadata.json`](metadata.json) describes a NIP-5A nsite launcher, multi-file editing/forking, Blossom publishing, Nostr identity, and adaptive relay networking. These are **documented project goals**; this README is not a claim that all features are operational.

## Source

- [`app/`](app/) — Android application module.
- [`build.gradle.kts`](build.gradle.kts), [`settings.gradle.kts`](settings.gradle.kts) — Gradle project configuration.
- [`metadata.json`](metadata.json) — project description and capability declaration.
- [`public/`](public/) — checked-in public resources.

## Build and verification

Open the project in Android Studio with the required Android SDK/Gradle environment and build the `app` module. Inspect permissions, signer behavior, storage, and publish flow on the intended Android device before describing them as verified. No device-runtime result is asserted here.

## Boundaries

Nostr signing and publication require appropriate user authorization. Do not confuse this repository with the entire Pretend Internet project or treat a successful build as proof that remote signing survives app switching.
