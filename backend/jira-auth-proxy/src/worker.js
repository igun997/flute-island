// Token proxy for Atlassian OAuth 2.0 (3LO).
//
// Atlassian requires a client secret to exchange an authorization code, and a secret
// cannot live inside an Android app. This worker keeps it server-side and does exactly
// three things:
//   GET  /jira/callback  bounce the browser back into the app (fluteisland://jira/callback)
//   POST /jira/token     authorization code  -> tokens
//   POST /jira/refresh   refresh token       -> tokens
// It stores nothing and logs nothing: tokens pass straight through to the phone.

const TOKEN_URL = "https://auth.atlassian.com/oauth/token";
const APP_LINK = "fluteisland://jira/callback";
const MAX_BODY = 4096;

const json = (status, body) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });

const escapeHtml = (s) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

function callbackPage(search) {
  const target = APP_LINK + search;
  const safe = escapeHtml(target);
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Flute Island</title>
<meta http-equiv="refresh" content="0;url=${safe}">
<style>body{font:16px system-ui;background:#0b1f2a;color:#eaf4f4;display:grid;place-items:center;min-height:100vh;margin:0;text-align:center}
a{display:inline-block;margin-top:16px;padding:12px 22px;border-radius:99px;background:#2dd4bf;color:#04201d;text-decoration:none;font-weight:600}</style></head>
<body><main><h1>Almost there</h1><p>Returning to Flute Island&hellip;</p><a href="${safe}">Open Flute Island</a></main>
<script>location.replace(${JSON.stringify(target).replace(/</g, "\\u003c")})</script></body></html>`;
  return new Response(html, { status: 200, headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" } });
}

async function readJson(request) {
  const text = await request.text();
  if (text.length > MAX_BODY) throw new Error("too large");
  const value = JSON.parse(text);
  if (value === null || typeof value !== "object") throw new Error("bad body");
  return value;
}

async function exchange(env, doFetch, payload) {
  const upstream = await doFetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ...payload, client_id: env.JIRA_CLIENT_ID, client_secret: env.JIRA_CLIENT_SECRET }),
  });
  let data = {};
  try {
    data = await upstream.json();
  } catch {
    /* fall through to the generic error */
  }
  if (!upstream.ok || typeof data.access_token !== "string") {
    // Pass the class of failure through (so the app can tell "sign in again" from "try later"), nothing else.
    const status = upstream.status >= 400 && upstream.status < 500 ? upstream.status : 502;
    return json(status, { error: typeof data.error === "string" ? data.error : "upstream_error" });
  }
  return json(200, {
    access_token: data.access_token,
    refresh_token: data.refresh_token,
    expires_in: data.expires_in,
    scope: data.scope,
  });
}

export async function handle(request, env, doFetch = fetch) {
  const url = new URL(request.url);

  if (request.method === "GET" && url.pathname === "/jira/callback") return callbackPage(url.search);

  if (request.method === "POST" && (url.pathname === "/jira/token" || url.pathname === "/jira/refresh")) {
    if (!env.JIRA_CLIENT_ID || !env.JIRA_CLIENT_SECRET) return json(500, { error: "not_configured" });
    let body;
    try {
      body = await readJson(request);
    } catch {
      return json(400, { error: "bad_request" });
    }
    if (url.pathname === "/jira/token") {
      if (typeof body.code !== "string" || !body.code) return json(400, { error: "bad_request" });
      // Only ever redeem a code against our own callback; stops this being used as a generic exchange.
      if (body.redirect_uri !== `${url.origin}/jira/callback`) return json(400, { error: "bad_redirect_uri" });
      return exchange(env, doFetch, { grant_type: "authorization_code", code: body.code, redirect_uri: body.redirect_uri });
    }
    if (typeof body.refresh_token !== "string" || !body.refresh_token) return json(400, { error: "bad_request" });
    return exchange(env, doFetch, { grant_type: "refresh_token", refresh_token: body.refresh_token });
  }

  return json(404, { error: "not_found" });
}

export default {
  fetch: (request, env) => handle(request, env),
};
