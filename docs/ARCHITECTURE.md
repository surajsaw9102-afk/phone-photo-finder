# Architecture and implementation plan

## Implemented Android app (MCP intentionally excluded)

- Reads device images/videos through Android MediaStore after runtime permission.
- Creates image labels locally using Google ML Kit image labeling.
- Displays thumbnails and opens selected media in the phone's existing viewer.
- Searches filenames and labels, with basic English/Hinglish synonym expansion.
- Persists metadata and labels in an app-private local JSON index; source media bytes are not copied into the index.
- Reports progress and errors during scanning. Cached labels are reused on later scans.
- Optional Supabase Auth and explicit metadata-only sync are implemented in the Android UI.
- Sync requests are batched. A signed-in user can delete their cloud metadata index.
- Supabase `gallery-search` Edge Function requires JWT authentication, verifies the user, and searches filename/label metadata. It does not return media bytes.

## Privacy/security requirements

- No original photos, videos, or thumbnails are uploaded automatically.
- Cloud sync is an explicit user action and sends metadata only.
- Android contains only a Supabase publishable client key; never embed a service-role/secret key in a client.
- Row-level security is enabled on `public.gallery_items`; access policies scope rows to `auth.uid() = owner_id`.
- Keep Edge Function JWT verification enabled. The function also verifies the caller token with Supabase Auth.
- No public storage bucket is used for media.
- The local cache uses app-private storage. Clearing app data removes that local cache.
- Cloud metadata can be removed from the app via **Delete my cloud index**.

## Known limitations

- There is intentionally no ChatGPT/MCP connector in this milestone.
- The index uses ML Kit labels, not a full visual-language model or vector embeddings; natural-language matching is best-effort.
- Video visual contents are not analyzed; videos can be found by filename and media type.
- Android MediaStore URIs may become invalid if media is moved/deleted or permissions change; rescan to refresh the index.
- Authentication state is in memory for the current app session.
- CI builds a debug APK. It is not a Play Store release or a security certification.
- CI compilation does not replace real-device tests across Android versions, partial photo access, large libraries, and devices without Google Play services.

## Future MCP bridge (not part of this deliverable)

A hosted MCP server cannot directly access a phone's private MediaStore. Remote search would require the phone to sync metadata to an authenticated service and a separately implemented MCP server/connector to expose read-only search tools.

Proposed flow:
1. The Android app labels media locally.
2. The user signs in and explicitly enables metadata sync.
3. The backend stores only searchable metadata scoped to the authenticated user.
4. An MCP server authenticates the requesting user and searches only that user's index.
5. Results return safe metadata and opaque local asset identifiers; local media remains on the phone.
6. Users can revoke access and delete the cloud index.

Before production, add device pairing/authorization for the MCP layer, rate limits, refresh-token/session handling, tests for cross-user denial and revoked sessions, and signed release packaging.
