# Phone Photo Finder — ChatGPT MCP connector

This implementation is intentionally split into three pieces:

1. Android keeps the gallery local by default, syncs searchable metadata only, and uploads a compressed JPEG preview only when the user taps Share with ChatGPT on a specific photo.
2. Supabase owns user accounts, per-user metadata, a private gallery-shares Storage bucket, and RLS. No service-role key is shipped in Android.
3. The MCP Edge Function exposes read-only search/latest-photo tools and returns image content only for a photo explicitly shared by the signed-in user.

## Current status

- supabase/functions/gallery-mcp/index.ts: MCP SDK + Streamable HTTP implementation using Supabase OAuth middleware. Not deployed by this PR.
- supabase/functions/oauth-consent/index.ts: sign-in/consent page for the Supabase OAuth server. Not deployed by this PR.
- supabase/migrations/20261017000000_mcp_gallery_sharing.sql: proposed migration for private photo previews, RLS, and the OAuth audience hook. Review and approve before applying to the production database.
- Android UI adds camera-origin metadata and explicit share/unshare controls. The original file stays on the phone; only a compressed JPEG preview (max 1.8 MB) is uploaded.
- The prior static-bearer prototype was removed because it was not compatible with the requested OAuth connector.

## Important OAuth compatibility detail

The MCP authorization spec requires resource-bound access tokens. Supabase's hosted OAuth server issues tokens with aud: authenticated by default and its OAuth endpoint has historically accepted but ignored the RFC 8707 resource parameter. This implementation includes a custom access-token hook that adds the exact MCP resource URI to the audience only for OAuth client IDs placed in mcp_private.oauth_clients, while retaining authenticated for Supabase API compatibility.

Do not skip this step: without the audience hook, the connector may fail resource-server validation or violate the MCP authorization boundary.

## Setup checklist (requires dashboard actions)

1. Review the migration and apply it to Supabase only after approving the RLS and storage-policy changes.
2. Deploy gallery-mcp and oauth-consent Edge Functions with platform JWT verification disabled only because gallery-mcp verifies OAuth through withOAuthProtectedResource() and withSupabase({ auth: 'user' }); oauth-consent is a public login/consent page and grants no access without a signed-in user approving the request.
3. In Supabase Authentication → OAuth Server, enable OAuth 2.1. **Important:** Supabase combines the Authorization Path with the Site URL from Authentication → URL Configuration; the path is not an absolute URL. First inspect the existing Site URL because changing it can affect email-confirmation/password-reset redirects. If the current Site URL is already https://qcscozdqdjjwjouxtnzf.supabase.co, use Authorization Path /functions/v1/oauth-consent. Otherwise, do not change Site URL blindly: host the consent page on the intended site origin or plan and test all existing auth redirect flows before changing that setting.
4. The live Supabase OIDC discovery document currently advertises S256 and token endpoint methods none/client_secret_basic/client_secret_post, but does not advertise CIMD or a dynamic-registration endpoint. Therefore create a **pre-registered OAuth client** for ChatGPT. Use the exact callback URI shown by the ChatGPT connector management UI (because the current issuer metadata does not advertise RFC 9207 issuer identification, expect a callback-ID-specific URI). Prefer Authorization Code + PKCE (S256) and client_secret_basic if the connector UI supports it. Do not enable unrestricted dynamic registration for a personal gallery.
5. Insert that exact OAuth client_id into the private allowlist, replacing the placeholder with the registered value:

   insert into mcp_private.oauth_clients (client_id, resource_uri, enabled)
   values ('<EXACT_REGISTERED_CLIENT_ID>', 'https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp', true)
   on conflict (client_id) do update set enabled = true;

6. In Supabase Authentication → Hooks → Custom Access Token, select public.custom_access_token_hook. This is necessary for the MCP audience to be included in tokens for the allowlisted client.
7. Configure the ChatGPT custom connector to use https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp and the OAuth client ID/secret from step 4. Never put the client secret or service-role key in GitHub or Android.
8. Open Phone Photo Finder, sign in, scan and sync metadata. Tap Share with ChatGPT on one photo. Then ask ChatGPT to search and show that shared photo.
9. Verify the connector can search, return the newest camera photo, show an explicitly shared image, reject an unshared image, and isolate one user's gallery from another user.

## Tools exposed to ChatGPT

- search_gallery(query, media_type?, limit?) — metadata search; includes sharedWithChatGPT.
- latest_photo() — newest indexed image, including screenshots.
- latest_camera_photo() — newest image under Android MediaStore DCIM/Camera or Pictures/Camera.
- get_shared_photo(asset_id) — returns image content only if that exact photo was explicitly shared.
- gallery_status() — metadata and explicit-share status.

## Verified discovery details

A live read of the project's OIDC discovery endpoint returned issuer `https://qcscozdqdjjwjouxtnzf.supabase.co/auth/v1`, an authorization-code + refresh-token grant list, S256 PKCE support, and token endpoint authentication methods `client_secret_basic`, `client_secret_post`, and `none`. The currently returned document did not advertise CIMD or a DCR registration endpoint. The OAuth Server must still be enabled/configured in the dashboard and the consent path must be set before live sign-in is considered ready.

## Privacy and operational notes

- No bulk media upload.
- Private Storage bucket; only the signed-in owner can upload, and the MCP audience can read that owner's shared preview.
- OAuth MCP tokens are read-only at the database/storage policy layer; direct Android sessions retain sync/share management permissions.
- Delete my cloud index removes metadata and shared preview objects for that account.
- Unsharing a photo removes its private object and shared index row. The original on the phone is not deleted.
- latest_camera_photo depends on Android's MediaStore path. Some camera apps save to nonstandard folders, so those images may not be classified as camera-origin.
- End-to-end ChatGPT OAuth/image rendering still requires real connector tests after dashboard setup. A GitHub CI build alone does not prove the connector works.

## Automated validation

- GitHub Actions runs Deno tests for Bearer-token audience parsing and English/Hinglish/Devanagari query tokenization, then type-checks both Edge Functions.
- A separate disposable PostgreSQL 16 workflow applies the proposed migration against a minimal Supabase-compatible schema and checks owner isolation, wrong-audience denial, read-only OAuth behavior, and Storage access revocation when the explicit share row is removed.
- These automated tests do not replace a live Supabase OAuth authorization/token exchange, dashboard-hook configuration, actual Android device test, or ChatGPT inline-image rendering test. Those remain release gates.
