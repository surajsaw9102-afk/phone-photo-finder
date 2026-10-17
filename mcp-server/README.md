# ChatGPT MCP Gallery Connector (implementation plan)

This package is being developed alongside the Android app. The goal is a remote MCP server that can search a single user's gallery metadata and return an explicitly shared photo to ChatGPT.

## Current app contract

The Android app syncs metadata to `public.gallery_items`:
`owner_id, asset_id, media_type, display_name, mime_type, taken_at, duration_ms, width, height, labels`.

It does **not** currently upload image bytes. The `asset_id`/MediaStore URI is local to Android and cannot be opened by ChatGPT over the internet. Therefore metadata search alone cannot display the actual photo in a ChatGPT message.

## Planned MCP tools

- `search_gallery(query, media_type?, limit?)`: search the authenticated owner's synced metadata.
- `latest_camera_photo()`: return the most recent image metadata, with camera-origin filtering added in the Android index.
- `get_photo(asset_id)`: return image content only if the user has explicitly made that photo available to the connector.
- `gallery_status()`: show last metadata sync and explain when the phone is offline or metadata is stale.

## Privacy and access model

- One owner per connector credential in the initial personal deployment; never accept an arbitrary `owner_id` from tool arguments.
- Keep Supabase service-role credentials on the server only, never in the Android app or repository.
- Require a high-entropy bearer token for the personal MCP endpoint and compare it in constant time.
- Never bulk-upload originals by default. The Android app will need an explicit, user-controlled “Share with ChatGPT” action or similarly clear opt-in before image bytes can be uploaded.
- Use private object storage and short-lived access, or return MCP image content directly. Do not publish permanent public image URLs.
- Do not log photo bytes, bearer tokens, or private signed URLs.

## Deployment requirements

Set server environment variables outside source control:
- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`
- `MCP_ACCESS_TOKEN` (long random secret)
- `GALLERY_OWNER_ID` (the Supabase Auth user UUID for the owner)

The server must be deployed at a public HTTPS endpoint before adding its URL and bearer credential to a ChatGPT custom MCP connector.

## Important status

This is a design/contract document, not a deployed connector. The app currently supports metadata sync only; image sharing and connector authentication still need implementation and end-to-end testing.
