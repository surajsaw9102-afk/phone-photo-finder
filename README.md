# Phone Photo Finder

A privacy-first Android gallery search app. It indexes photos and videos on your phone, creates image labels locally, and lets you search by filename, ML Kit labels, and a small English/Hinglish synonym map.

## What works in this build

- Reads media using Android MediaStore after you grant photo/video access.
- Shows a thumbnail grid; tap an item to open it in your phone's image/video viewer.
- Searches filenames and on-device image labels (examples: `dog`, `beach`, `blue shirt`, `laal shirt`, `kutta`, `samundar`).
- Shows scan progress and an actionable error if gallery access fails.
- Saves the metadata index in app-private local storage so a restart does not automatically lose the index.
- Reuses labels already generated on this device during later scans.
- Supports optional Supabase account sign-in/sign-up and explicit metadata-only sync in bounded batches.
- Allows the signed-in user to delete their cloud metadata index with a confirmation prompt.
- Does **not** automatically upload photos, videos, or thumbnails.

## Known limits (MCP integration intentionally excluded)

- No ChatGPT/MCP connection is included yet.
- Image understanding uses on-device ML Kit image labels, not a full visual-language model or vector embeddings. Searches are best-effort, not guaranteed semantic matches.
- Videos are searchable by filename and media type; their visual contents are not analyzed.
- Local index stores MediaStore URIs. Android may invalidate a URI if a file is deleted/moved or permissions change; run a fresh scan to refresh it.
- Sign-in state is kept in memory for the current app session; signing out or closing the app requires signing in again.
- The current CI produces a **debug APK**. It is not a Play Store release or a production security certification.
- A successful CI build verifies compilation/packaging only; the app still needs real-device checks on different Android versions and gallery sizes.

## Install the debug APK

1. Open the [latest Android build workflow](https://github.com/surajsaw9102-afk/phone-photo-finder/actions/workflows/android-build.yml).
2. Open the latest successful run on branch `feature/android-gallery-mvp`.
3. Under **Artifacts**, download `phone-photo-finder-debug` and extract the ZIP.
4. Install `app-debug.apk` on your Android phone. Android may ask you to allow installs from the browser/file manager you used.
5. Open the app, grant photo/video access, and tap **Scan my gallery**. Keep the app open during the first on-device labeling pass; progress should show the number of items processed.

## Build from source

Requirements: Android Studio, JDK 17, Android SDK platform 35.

1. Open this repository in Android Studio.
2. Let Gradle sync.
3. Run on an Android 10+ device or emulator with Google Play services for ML Kit support.

## Privacy and cloud metadata

- Gallery access is local and permission-gated.
- Labels and the local index are stored in the app's private storage.
- Supabase sync is optional and happens only when you tap **Sync metadata** while signed in.
- Sync sends item URIs, filenames, media type, MIME type, and labels—not original image/video bytes or thumbnails.
- The Supabase table uses row-level security so authenticated users can access only their own rows.
- **Delete my cloud index** deletes synced metadata from your account; it does not delete media or the local index.
- The Android app contains only a Supabase publishable client key; service-role keys must never be shipped in the app.

## Relevant references

- [Android shared media storage / MediaStore](https://developer.android.com/training/data-storage/shared/media)
- [Google ML Kit image labeling for Android](https://developers.google.com/ml-kit/vision/image-labeling/android)
- [Supabase Row Level Security](https://supabase.com/docs/guides/database/postgres/row-level-security)
