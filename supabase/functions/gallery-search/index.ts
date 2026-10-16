import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });

  if (req.method !== "POST") return json({ error: "Use POST." }, 405);

  const authorization = req.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) {
    return json({ error: "A signed-in user's access token is required." }, 401);
  }

  const url = Deno.env.get("SUPABASE_URL") ?? "";
  const key = Deno.env.get("SUPABASE_ANON_KEY") ??
    (() => {
      try {
        const keys = JSON.parse(Deno.env.get("SUPABASE_PUBLISHABLE_KEYS") ?? "{}");
        const first = Object.values(keys).find((value) => typeof value === "string");
        return typeof first === "string" ? first : "";
      } catch {
        return "";
      }
    })();

  if (!url || !key) return json({ error: "Supabase client configuration is unavailable." }, 500);

  try {
    const client = createClient(url, key, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const token = authorization.slice("Bearer ".length);
    const { data: authData, error: authError } = await client.auth.getUser(token);
    if (authError || !authData.user) return json({ error: "Invalid or expired user session." }, 401);

    const input = await req.json().catch(() => ({}));
    const query = typeof input.query === "string" ? input.query.trim().slice(0, 160) : "";
    let mediaType: "image" | "video" | null = input.media_type === "image" || input.media_type === "video" ? input.media_type : null;
    const requestedLimit = Number(input.limit ?? 20);
    const limit = Number.isFinite(requestedLimit) ? Math.max(1, Math.min(50, Math.floor(requestedLimit))) : 20;

    let dbQuery = client
      .from("gallery_items")
      .select("asset_id, media_type, display_name, mime_type, taken_at, duration_ms, width, height, labels")
      .order("updated_at", { ascending: false })
      .limit(limit);

    const synonyms: Record<string, string> = {
      kutta: "dog", kutte: "dog", billi: "cat", gaadi: "car", gadi: "car",
      samundar: "beach", samudra: "ocean", pahad: "mountain", ped: "tree",
      phool: "flower", khana: "food", aadmi: "person", ladki: "girl",
      laal: "red", lal: "red", neela: "blue", nila: "blue", hara: "green",
      safed: "white", kala: "black", kapde: "clothing", aasman: "sky",
      suraj: "sun", paani: "water", pani: "water",
      "कुत्ता": "dog", "कुत्ते": "dog", "बिल्ली": "cat", "गाड़ी": "car",
      "समुंदर": "beach", "समुद्र": "ocean", "पहाड़": "mountain", "पेड़": "tree",
      "फूल": "flower", "खाना": "food", "आदमी": "person", "लड़की": "girl",
      "लाल": "red", "नीला": "blue", "हरा": "green", "सफेद": "white",
      "काला": "black", "कपड़े": "clothing", "आसमान": "sky", "सूरज": "sun", "पानी": "water",
    };
    const rawTerms = query.toLocaleLowerCase().split(/[^\p{L}\p{N}]+/u).filter(Boolean);
    const wantsVideo = rawTerms.some((term: string) => ["video", "videos", "clip", "clips"].includes(term));
    const wantsImage = rawTerms.some((term: string) => ["photo", "photos", "image", "images", "picture", "pictures"].includes(term));
    if (!mediaType && wantsVideo) mediaType = "video";
    else if (!mediaType && wantsImage) mediaType = "image";
    if (mediaType) dbQuery = dbQuery.eq("media_type", mediaType);
    const stopWords = new Set(["show", "find", "me", "please", "my", "the", "a", "an", "in", "on", "at", "from", "with", "of", "is", "was", "are", "photo", "photos", "image", "images", "picture", "pictures", "video", "videos", "clip", "clips"]);
    const terms = Array.from(new Set(rawTerms
      .filter((term: string) => term.length > 1 && !stopWords.has(term))
      .slice(0, 8)
      .flatMap((term: string): string[] => synonyms[term] ? [term, synonyms[term]] : [term])));
    if (terms.length > 0) {
      // Terms are restricted to letters/numbers by the tokenizer above, so they are safe
      // to insert into this PostgREST OR filter after the 160-character query limit.
      const nameFilters = terms.map((term: string) => `display_name.ilike.%${term}%`);
      dbQuery = dbQuery.or([...nameFilters, `labels.ov.{${terms.join(",")}}`].join(","));
    }

    const { data, error } = await dbQuery;
    if (error) return json({ error: "Gallery search failed." }, 500);

    return json({
      results: data ?? [],
      count: data?.length ?? 0,
      query,
      note: "Metadata only; original media files are not uploaded or returned.",
    });
  } catch {
    return json({ error: "Invalid request." }, 400);
  }
});
