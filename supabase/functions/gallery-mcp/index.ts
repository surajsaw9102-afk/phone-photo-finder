import 'jsr:@supabase/functions-js/edge-runtime.d.ts'

import { pipeline } from 'npm:@supabase/middleware@1'
import { withOAuthProtectedResource, withSupabase } from 'npm:@supabase/server@1'
import { McpServer } from 'npm:@modelcontextprotocol/sdk@1.25.3/server/mcp.js'
import { WebStandardStreamableHTTPServerTransport } from 'npm:@modelcontextprotocol/sdk@1.25.3/server/webStandardStreamableHttp.js'
import { z } from 'npm:zod@^4.1.13'

const RESOURCE_URI = 'https://qcscozdqdjjwjouxtnzf.supabase.co/functions/v1/gallery-mcp'
const BUCKET = 'gallery-shares'
const MAX_IMAGE_BYTES = 2 * 1024 * 1024

function base64(bytes: Uint8Array): string {
  let binary = ''
  const chunkSize = 0x8000
  for (let i = 0; i < bytes.length; i += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(i, Math.min(i + chunkSize, bytes.length)))
  }
  return btoa(binary)
}

function textResult(text: string, structuredContent?: Record<string, unknown>) {
  return {
    content: [{ type: 'text' as const, text }],
    ...(structuredContent ? { structuredContent } : {}),
  }
}

function tokenHasResourceAudience(req: Request): boolean {
  const authorization = req.headers.get('authorization') ?? ''
  const token = authorization.match(/^Bearer\\s+(.+)$/i)?.[1]
  if (!token) return false
  try {
    const payloadSegment = token.split('.')[1]
    if (!payloadSegment) return false
    const base64 = payloadSegment.replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(base64.padEnd(Math.ceil(base64.length / 4) * 4, '=')))
    const aud = payload.aud
    return Array.isArray(aud) ? aud.includes(RESOURCE_URI) : aud === RESOURCE_URI
  } catch {
    return false
  }
}

function registerTools(server: McpServer, supabase: any) {
  server.registerTool(
    'search_gallery',
    {
      title: 'Search my phone gallery',
      description: 'Search the signed-in user’s synced photo/video metadata by filename or on-device labels. Supports English, Hinglish and Hindi labels when indexed. This does not upload or reveal unshared photo pixels; check sharedWithChatGPT before calling get_shared_photo.',
      inputSchema: {
        query: z.string().trim().min(1).max(120),
        media_type: z.enum(['image', 'video']).optional(),
        limit: z.number().int().min(1).max(20).optional(),
      },
      annotations: { readOnlyHint: true, openWorldHint: false, destructiveHint: false },
    },
    async ({ query, media_type, limit }) => {
      const terms = [...new Set(query.toLocaleLowerCase().split(/[^\p{L}\p{N}]+/u).filter((t) => t.length > 1).slice(0, 6))]
      let request = supabase
        .from('gallery_items')
        .select('asset_id,media_type,display_name,mime_type,taken_at,duration_ms,width,height,labels,camera_origin')
        .order('taken_at', { ascending: false, nullsFirst: false })
        .limit(limit ?? 10)
      if (media_type) request = request.eq('media_type', media_type)
      if (terms.length) {
        const filters = terms.map((term) => `display_name.ilike.%${term}%`)
        filters.push(`labels.ov.{${terms.join(',')}}`)
        request = request.or(filters.join(','))
      }
      const { data, error } = await request
      if (error) return { ...textResult('Gallery search failed. Please try again.'), isError: true }
      const results = data ?? []
      const ids = results.map((item: any) => item.asset_id)
      let sharedIds = new Set<string>()
      if (ids.length) {
        const { data: shared, error: sharedError } = await supabase
          .from('gallery_shared_media')
          .select('asset_id')
          .in('asset_id', ids)
        if (!sharedError) sharedIds = new Set((shared ?? []).map((item: any) => item.asset_id))
      }
      const safeResults = results.map((item: any) => ({
        ...item,
        sharedWithChatGPT: sharedIds.has(item.asset_id),
      }))
      return textResult(
        JSON.stringify({ count: safeResults.length, results: safeResults }),
        { count: safeResults.length, results: safeResults },
      )
    },
  )

  server.registerTool(
    'latest_camera_photo',
    {
      title: 'Find my latest camera photo',
      description: 'Return metadata for the newest indexed image whose Android MediaStore path indicates DCIM/Camera or Pictures/Camera. This does not return image pixels unless the item was explicitly shared.',
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false, destructiveHint: false },
    },
    async () => {
      const { data, error } = await supabase
        .from('gallery_items')
        .select('asset_id,display_name,mime_type,taken_at,width,height,labels,camera_origin')
        .eq('media_type', 'image')
        .eq('camera_origin', true)
        .order('taken_at', { ascending: false, nullsFirst: false })
        .limit(1)
        .maybeSingle()
      if (error) return { ...textResult('Could not read the camera-photo index.'), isError: true }
      if (!data) return textResult('No camera-origin photos are indexed yet. Open Phone Photo Finder, scan the gallery, and sync metadata.')
      const { data: shared } = await supabase.from('gallery_shared_media').select('asset_id').eq('asset_id', data.asset_id).maybeSingle()
      const result = { ...data, sharedWithChatGPT: Boolean(shared) }
      return textResult(JSON.stringify(result), result)
    },
  )

  server.registerTool(
    'latest_photo',
    {
      title: 'Find my latest photo',
      description: 'Return metadata for the newest indexed image. Camera photos and screenshots are both eligible. This does not return image pixels unless the item was explicitly shared.',
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false, destructiveHint: false },
    },
    async () => {
      const { data, error } = await supabase
        .from('gallery_items')
        .select('asset_id,display_name,mime_type,taken_at,width,height,labels,camera_origin')
        .eq('media_type', 'image')
        .order('taken_at', { ascending: false, nullsFirst: false })
        .limit(1)
        .maybeSingle()
      if (error) return { ...textResult('Could not read the photo index.'), isError: true }
      if (!data) return textResult('No photos are indexed yet. Open Phone Photo Finder, scan the gallery, and sync metadata.')
      const { data: shared } = await supabase.from('gallery_shared_media').select('asset_id').eq('asset_id', data.asset_id).maybeSingle()
      const result = { ...data, sharedWithChatGPT: Boolean(shared) }
      return textResult(JSON.stringify(result), result)
    },
  )

  server.registerTool(
    'get_shared_photo',
    {
      title: 'Show a photo shared from my phone',
      description: 'Return actual image pixels for a single photo the user explicitly shared from Phone Photo Finder. Requires the exact asset_id from search_gallery/latest_photo results. Never works for photos that have not been explicitly shared.',
      inputSchema: { asset_id: z.string().min(1).max(512) },
      annotations: { readOnlyHint: true, openWorldHint: false, destructiveHint: false },
    },
    async ({ asset_id }) => {
      const { data: row, error } = await supabase
        .from('gallery_shared_media')
        .select('asset_id,display_name,storage_path,mime_type,taken_at,camera_origin')
        .eq('asset_id', asset_id)
        .maybeSingle()
      if (error) return { ...textResult('Could not check shared-photo access.'), isError: true }
      if (!row) return { ...textResult('This photo has not been shared with ChatGPT. In Phone Photo Finder, sign in, then tap “Share with ChatGPT” on that photo.'), isError: true }
      const { data: file, error: fileError } = await supabase.storage.from(BUCKET).download(row.storage_path)
      if (fileError || !file) return { ...textResult('The shared image is unavailable. It may have been removed from cloud storage.'), isError: true }
      const bytes = new Uint8Array(await file.arrayBuffer())
      if (bytes.byteLength === 0 || bytes.byteLength > MAX_IMAGE_BYTES) {
        return { ...textResult('The shared image has an invalid size. Re-share it from the Android app.'), isError: true }
      }
      return {
        content: [
          { type: 'text' as const, text: `Photo: ${row.display_name}. This is the compressed preview the user explicitly shared.` },
          { type: 'image' as const, data: base64(bytes), mimeType: 'image/jpeg' },
        ],
        structuredContent: {
          display_name: row.display_name,
          taken_at: row.taken_at,
          camera_origin: row.camera_origin,
          mime_type: row.mime_type,
        },
      }
    },
  )

  server.registerTool(
    'gallery_status',
    {
      title: 'Check gallery connector status',
      description: 'Explain what metadata is synced and whether actual photo pixels have been explicitly shared.',
      inputSchema: {},
      annotations: { readOnlyHint: true, openWorldHint: false, destructiveHint: false },
    },
    async () => {
      const { count: metadataCount } = await supabase.from('gallery_items').select('id', { count: 'exact', head: true })
      const { count: sharedCount } = await supabase.from('gallery_shared_media').select('id', { count: 'exact', head: true })
      return textResult(
        `Gallery connector is authenticated. Synced metadata rows: ${metadataCount ?? 'unknown'}. Photos explicitly shared with ChatGPT: ${sharedCount ?? 'unknown'}. Original photos are not bulk-uploaded; only individually shared compressed JPEG previews are available.`,
        { metadataCount: metadataCount ?? null, sharedCount: sharedCount ?? null, metadataOnlySync: true, imagePixelsAvailableOnlyAfterExplicitShare: true, resource: RESOURCE_URI },
      )
    },
  )
}

Deno.serve(
  pipeline(
    [withOAuthProtectedResource(), withSupabase({ auth: 'user' })],
    async (req, { supabase }) => {
      // withSupabase verifies the token signature/session; this additional check
      // enforces the MCP-specific audience configured by the custom token hook.
      if (!tokenHasResourceAudience(req)) {
        return new Response(JSON.stringify({ error: 'invalid_token', error_description: 'Access token audience does not match Phone Photo Finder MCP.' }), {
          status: 401,
          headers: {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
            'WWW-Authenticate': 'Bearer resource_metadata="https://qcscozdqdjjwjouxtnzf.supabase.co/.well-known/oauth-protected-resource/functions/v1/gallery-mcp", error="invalid_token", error_description="Access token audience mismatch"',
          },
        })
      }
      const server = new McpServer(
        { name: 'phone-photo-finder', version: '0.3.0' },
        { instructions: 'Use search_gallery for discovery. Call get_shared_photo only when sharedWithChatGPT is true or the user explicitly asks for an already-shared photo. The server never accepts owner_id; all queries are scoped to the authenticated user. Original media is not bulk-uploaded.' },
      )
      registerTools(server, supabase)
      const transport = new WebStandardStreamableHTTPServerTransport()
      await server.connect(transport)
      return transport.handleRequest(req)
    },
  ),
)
