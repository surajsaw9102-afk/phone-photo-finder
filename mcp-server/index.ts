import { createClient } from "npm:@supabase/supabase-js@2";

const SERVER_NAME = "phone-photo-finder";
const SERVER_VERSION = "0.1.0";
const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, content-type, mcp-session-id, mcp-protocol-version",
  "Access-Control-Allow-Methods": "POST, GET, DELETE, OPTIONS",
  "Content-Type": "application/json",
};

type JsonRpc = { jsonrpc?: string; id?: string | number | null; method?: string; params?: Record<string, unknown> };

function jsonRpc(id: JsonRpc["id"], result: unknown, status = 200) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id: id ?? null, result }), { status, headers: corsHeaders });
}
function rpcError(id: JsonRpc["id"], code: number, message: string, status = 200) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id: id ?? null, error: { code, message } }), { status, headers: corsHeaders });
}
function textResult(text: string, structuredContent?: unknown) {
  return { content: [{ type: "text", text }], ...(structuredContent === undefined ? {} : { structuredContent }) };
}
function safeEqual(a: string, b: string): boolean {
  const encoder = new TextEncoder();
  const aa = encoder.encode(a);
  const bb = encoder.encode(b);
  let diff = aa.length ^ bb.length;
  const n = Math.max(aa.length, bb.length);
  for (let i = 0; i < n; i++) diff |= (aa[i] ?? 0) ^ (bb[i] ?? 0);
  return diff === 0;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method === "GET") {
    return new Response("Phone Photo Finder MCP endpoint. Send MCP JSON-RPC requests using POST.", {
      status: 200, headers: { ...corsHeaders, "Content-Type": "text/plain" },
    });
  }
  if (req.method !== "POST") return new Response("Method not allowed", { status: 405, headers: corsHeaders });

  const configuredToken = Deno.env.get("MCP_ACCESS_TOKEN") ?? "";
  const authHeader = req.headers.get("Authorization") ?? "";
  const suppliedToken = authHeader.startsWith("Bearer ") ? authHeader.slice(7) : "";
  if (configuredToken.length < 32 || !safeEqual(suppliedToken, configuredToken)) {
    return new Response(JSON.stringify({ error: "Unauthorized" }), { status: 401, headers: corsHeaders });
  }

  let rpc: JsonRpc;
  try { rpc = await req.json(); } catch { return rpcError(null, -32700, "Parse error"); }
  if (rpc.jsonrpc !== "2.0" || typeof rpc.method !== "string") return rpcError(rpc.id, -32600, "Invalid Request");
  const params = rpc.params ?? {};

  if (rpc.method === "initialize") {
    return jsonRpc(rpc.id, {
      protocolVersion: typeof params.protocolVersion === "string" ? params.protocolVersion : "2025-03-26",
      capabilities: { tools: {} },
      serverInfo: { name: SERVER_NAME, version: SERVER_VERSION },
      instructions: "Searches metadata synced from the owner's Phone Photo Finder Android app. Actual photo bytes are not yet exposed by this initial version; local Android content:// URIs are not remotely accessible.",
    });
  }
  if (rpc.method === "notifications/initialized" || rpc.method === "notifications/cancelled") {
    return new Response(null, { status: 202, headers: corsHeaders });
  }
  if (rpc.method === "ping") return jsonRpc(rpc.id, {});
  if (rpc.method === "tools/list") {
    return jsonRpc(rpc.id, {
      tools: [
        {
          name: "search_gallery",
          description: "Search the owner's synced phone-photo metadata by filename or detected labels. Supports English, Hinglish and Hindi terms where labels exist. Does not return image bytes.",
          inputSchema: {
            type: "object", properties: {
              query: { type: "string", description: "What to find, e.g. beach, dog, laal shirt, screenshot" },
              media_type: { type: "string", enum: ["image", "video"], description: "Optional media filter" },
              limit: { type: "integer", minimum: 1, maximum: 20, default: 10 },
            }, required: ["query"], additionalProperties: false,
          },
        },
        {
          name: "latest_photo",
          description: "Return metadata for the most recently dated image in the owner's synced gallery index. The index does not yet distinguish camera photos from screenshots.",
          inputSchema: { type: "object", properties: {}, additionalProperties: false },
        },
        {
          name: "gallery_status",
          description: "Explain the scope and limitations of the synced gallery index.",
          inputSchema: { type: "object", properties: {}, additionalProperties: false },
        },
      ],
    });
  }
  if (rpc.method !== "tools/call") return rpcError(rpc.id, -32601, "Method not found");

  const toolName = params.name;
  const args = (params.arguments && typeof params.arguments === "object" ? params.arguments : {}) as Record<string, unknown>;
  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const ownerId = Deno.env.get("GALLERY_OWNER_ID") ?? "";
  if (!supabaseUrl || !serviceKey || !ownerId) {
    return jsonRpc(rpc.id, textResult("Server configuration is incomplete. Set SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, GALLERY_OWNER_ID and MCP_ACCESS_TOKEN as server secrets."));
  }

  try {
    const db = createClient(supabaseUrl, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
    if (toolName === "gallery_status") {
      return jsonRpc(rpc.id, textResult(
        "Connected to the configured owner's synced metadata index. Only metadata is currently synced (names, labels, dates and dimensions). Original photos and thumbnails are not uploaded. Android content:// URIs are local-only. Search results may be stale until the app syncs again.",
        { metadataOnly: true, imageContentAvailable: false, localUrisAccessibleRemotely: false },
      ));
    }
    if (toolName === "latest_photo") {
      const { data, error } = await db.from("gallery_items")
        .select("asset_id, media_type, display_name, mime_type, taken_at, width, height, labels, updated_at")
        .eq("owner_id", ownerId).eq("media_type", "image")
        .order("taken_at", { ascending: false, nullsFirst: false })
        .limit(1).maybeSingle();
      if (error) throw error;
      if (!data) return jsonRpc(rpc.id, textResult("No synced photos found. Open the Android app, scan your gallery and sync metadata."));
      return jsonRpc(rpc.id, textResult(JSON.stringify(data), data));
    }
    if (toolName === "search_gallery") {
      const query = typeof args.query === "string" ? args.query.trim().slice(0, 120) : "";
      if (!query) return jsonRpc(rpc.id, textResult("Please provide a non-empty search query."));
      const requestedLimit = Number(args.limit ?? 10);
      const limit = Number.isFinite(requestedLimit) ? Math.max(1, Math.min(20, Math.floor(requestedLimit))) : 10;
      const mediaType = args.media_type === "image" || args.media_type === "video" ? args.media_type : null;
      const terms = [...new Set(query.toLocaleLowerCase().split(/[^\p{L}\p{N}]+/u).filter((x) => x.length > 1).slice(0, 6))];
      let q = db.from("gallery_items")
        .select("asset_id, media_type, display_name, mime_type, taken_at, width, height, labels")
        .eq("owner_id", ownerId).order("taken_at", { ascending: false, nullsFirst: false }).limit(limit);
      if (mediaType) q = q.eq("media_type", mediaType);
      if (terms.length) {
        const nameFilters = terms.map((t) => "display_name.ilike.%" + t.replace(/[(),.]/g, "") + "%");
        const orFilter = [...nameFilters, "labels.ov.{" + terms.map((t) => t.replace(/[{},]/g, "")).join(",") + "}"].join(",");
        q = q.or(orFilter);
      }
      const { data, error } = await q;
      if (error) throw error;
      const results = data ?? [];
      return jsonRpc(rpc.id, textResult(
        results.length ? JSON.stringify({ count: results.length, results, note: "Metadata only; image bytes are not available in this version." }) : "No matches found in the synced index. The phone may need to sync again.",
        { count: results.length, results, imageContentAvailable: false },
      ));
    }
    return jsonRpc(rpc.id, textResult("Unknown tool: " + String(toolName)));
  } catch (error) {
    console.error("MCP tool failed", error instanceof Error ? error.message : "unknown error");
    return jsonRpc(rpc.id, textResult("The gallery request failed. Check server logs and the owner's synced metadata."));
  }
});
