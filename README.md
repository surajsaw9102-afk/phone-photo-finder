# Phone Photo Finder

An Android-first, privacy-conscious photo search prototype for everyday queries such as “blue shirt”, “dog”, or “beach”.

## Current status

Prototype scaffold only — not a release APK and not yet connected to ChatGPT. The Android app reads the device gallery after permission is granted, runs ML Kit image labeling on-device, and filters labels and filenames. It does not upload photos.

## Run on Android

Requirements: Android Studio, JDK 17, Android SDK platform 35.

1. Open this repository in Android Studio.
2. Let Gradle sync.
3. Run the app on an Android 10+ device.
4. Grant image access and tap “Index my gallery”.

The initial scan may take time for large galleries. The prototype currently keeps its index in memory, so restarting the app loses the index.

## Privacy

- Only image-read permissions are requested.
- No Internet permission is declared in the Android manifest.
- Photo labels are generated on-device; no photos or thumbnails are uploaded.
- This is a prototype, not a security audit or finished ChatGPT integration.

## ChatGPT integration roadmap

A hosted MCP server cannot directly read a phone's local MediaStore. A secure bridge must be paired with the phone and available online when a search is requested. The proposed design syncs minimal searchable metadata only to an authenticated backend; originals stay on the phone unless the user explicitly opts into sharing.

See docs/ARCHITECTURE.md for the plan.
