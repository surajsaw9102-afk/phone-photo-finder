import 'jsr:@supabase/functions-js/edge-runtime.d.ts'

const SUPABASE_URL = 'https://qcscozdqdjjwjouxtnzf.supabase.co'
const PUBLISHABLE_KEY = 'sb_publishable_PQJeTL-aDf1RLOx8iVUAMg_3Mc2Zjmy'

const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Phone Photo Finder — Connect ChatGPT</title>
<style>
:root{color-scheme:dark}body{font:16px system-ui,sans-serif;background:#10141c;color:#f4f7fb;margin:0;min-height:100vh;display:grid;place-items:center;padding:20px;box-sizing:border-box}
main{max-width:480px;width:100%;background:#1a2230;border:1px solid #39465a;border-radius:22px;padding:26px;box-sizing:border-box}
h1{font-size:24px;margin:0 0 10px}p,li{color:#b6c1d1;line-height:1.55}label{display:block;margin:14px 0 6px;color:#b6c1d1}input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #39465a;background:#10141c;color:#fff;font-size:16px}
button{padding:12px 15px;border:0;border-radius:10px;font-weight:700;cursor:pointer;margin:8px 8px 0 0}button.primary{background:#57d6c3;color:#09211e}button.secondary{background:#242e3d;color:#f4f7fb}button.deny{background:#3a2830;color:#ffc66d}
.small{font-size:13px}#status{white-space:pre-wrap;color:#ffc66d}#consent{display:none}#login{display:none}.scope{background:#242e3d;padding:12px;border-radius:12px}
</style>
</head>
<body><main>
<h1>Connect Phone Photo Finder</h1>
<p>Sign in to your Phone Photo Finder account, then review what ChatGPT will be allowed to access.</p>
<div id="login">
<label for="email">Account email</label><input id="email" type="email" autocomplete="username">
<label for="password">Password</label><input id="password" type="password" autocomplete="current-password">
<button class="primary" id="signin">Sign in</button>
<p class="small">Use the same account you use in the Phone Photo Finder Android app. This page does not ask for your ChatGPT password.</p>
</div>
<div id="consent">
<p id="client"></p><p id="redirect" class="small"></p><p id="scopes" class="small"></p>
<div class="scope">
<strong>Requested access</strong>
<ul><li>Read your synced photo/video names, dates and on-device labels.</li><li>Read only compressed JPEG previews that you explicitly shared in the Android app.</li><li>No permission to upload, delete or modify your phone gallery.</li></ul>
<p class="small">Original photos are not bulk-uploaded. You can remove a shared preview in the Android app or delete your cloud index.</p>
</div>
<button class="primary" id="approve">Approve access</button><button class="deny" id="deny">Deny</button>
</div>
<p id="status" role="status"></p>
</main>
<script type="module">
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';
const client = createClient(${JSON.stringify(SUPABASE_URL)}, ${JSON.stringify(PUBLISHABLE_KEY)}, {
  auth: { persistSession: false, autoRefreshToken: true, detectSessionInUrl: false }
});
const params = new URLSearchParams(location.search);
const authorizationId = params.get('authorization_id');
const login = document.getElementById('login');
const consent = document.getElementById('consent');
const status = document.getElementById('status');
const say = (message) => { status.textContent = message; };
let loadedDetails = null;
async function render() {
  if (!authorizationId) { say('Missing authorization request. Return to ChatGPT and reconnect.'); return; }
  const { data: sessionData } = await client.auth.getSession();
  if (!sessionData.session) { login.style.display = 'block'; consent.style.display = 'none'; return; }
  login.style.display = 'none';
  const { data, error } = await client.auth.oauth.getAuthorizationDetails(authorizationId);
  if (error) { say('Could not load the authorization request. Return to ChatGPT and try again.'); return; }
  if (data && 'redirect_url' in data) { location.assign(data.redirect_url); return; }
  loadedDetails = data;
  const appName = data.client?.name || data.client_name || 'ChatGPT';
  document.getElementById('client').textContent = appName + ' is requesting access to the Phone Photo Finder data listed below.';
  document.getElementById('redirect').textContent = 'Registered redirect URI: ' + (data.redirect_uri || 'not supplied');
  document.getElementById('scopes').textContent = 'OAuth scopes requested: ' + (data.scope || 'none supplied');
  consent.style.display = 'block';
}
document.getElementById('signin').addEventListener('click', async () => {
  say('');
  const email = document.getElementById('email').value.trim();
  const password = document.getElementById('password').value;
  if (!email || !password) { say('Enter your account email and password.'); return; }
  const { error } = await client.auth.signInWithPassword({ email, password });
  if (error) { say('Sign-in failed. Check your credentials or confirm your email, then retry.'); return; }
  await render();
});
document.getElementById('approve').addEventListener('click', async () => {
  if (!loadedDetails) return;
  say('Approving access…');
  const { data, error } = await client.auth.oauth.approveAuthorization(authorizationId);
  if (error || !data?.redirect_url) { say('Could not approve this request. No access was granted. Please retry.'); return; }
  location.assign(data.redirect_url);
});
document.getElementById('deny').addEventListener('click', async () => {
  say('Denying access…');
  const { data, error } = await client.auth.oauth.denyAuthorization(authorizationId);
  if (error || !data?.redirect_url) { say('Could not deny this request. Return to ChatGPT and disconnect this connector if needed.'); return; }
  location.assign(data.redirect_url);
});
await render();
</script>
</body></html>`

Deno.serve((req: Request) => {
  if (req.method !== 'GET') return new Response('Method not allowed', { status: 405, headers: { Allow: 'GET' } })
  return new Response(html, {
    status: 200,
    headers: {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Security-Policy': "default-src 'none'; script-src 'unsafe-inline' https://esm.sh; style-src 'unsafe-inline'; connect-src https://qcscozdqdjjwjouxtnzf.supabase.co; base-uri 'none'; frame-ancestors 'none'",
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
    },
  })
})
