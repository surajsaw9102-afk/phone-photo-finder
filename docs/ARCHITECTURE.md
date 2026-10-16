# Architecture and implementation plan

## Prototype boundary

The Android app reads images through MediaStore after the user grants permission. On-device image labeling creates searchable descriptions. No original images or thumbnails should be sent to a server by default.

## ChatGPT/MCP bridge

A hosted MCP server cannot directly access a phone's private local MediaStore. The phone must be paired with an authenticated service and online for remote searches. Do not expose an unauthenticated HTTP server on the phone.

Proposed flow:
1. Android creates stable random item IDs and labels on-device.
2. The user explicitly enables ChatGPT linking and pairs/signs in.
3. The phone syncs only minimal searchable metadata over TLS to an authenticated backend.
4. A hosted MCP tool searches only the authenticated user's index.
5. Results return opaque item IDs and safe metadata. Thumbnail transfer must be separately opt-in and use short-lived access.
6. Users can disconnect the device and delete the remote index.

## Security requirements

- Per-user authentication and per-device pairing; no shared hard-coded bearer token.
- Enforce row-level security on every user-owned table.
- Never put Supabase service-role or secret keys in the Android client.
- Do not upload original photos by default.
- Use TLS, request validation, rate limits, and short-lived access tokens.
- No public storage buckets for private photos.
- Test cross-user denial, revoked pairing, malformed queries, and expired image links before production.
- Security review and real-device testing are still required; these notes are not a security certification.

## Milestones

1. Build and test Android gallery search and persist its local index.
2. Add explicit pairing/authentication.
3. Add a Supabase schema with row-level security and metadata sync.
4. Add a hosted MCP server with a read-only search tool.
5. Add CI builds and a signed APK release workflow.
6. Test the end-to-end flow with a real Android device and connected ChatGPT MCP client.

## Known limitations

This first scaffold does not yet contain a working ChatGPT/MCP backend, persistent indexing, a built APK, or device-test results. Label matching is not yet full semantic vector search.
