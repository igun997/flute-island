import { test } from "node:test";
import assert from "node:assert/strict";
import { handle } from "../src/worker.js";

const env = { JIRA_CLIENT_ID: "cid", JIRA_CLIENT_SECRET: "sec" };
const ORIGIN = "https://auth.example.workers.dev";

const post = (path, body) =>
  new Request(ORIGIN + path, { method: "POST", body: typeof body === "string" ? body : JSON.stringify(body) });

function fakeFetch(status, data) {
  const calls = [];
  const f = async (url, init) => {
    calls.push({ url, init, body: JSON.parse(init.body) });
    return new Response(JSON.stringify(data), { status });
  };
  f.calls = calls;
  return f;
}

test("callback bounces the query string into the app deep link", async () => {
  const res = await handle(new Request(`${ORIGIN}/jira/callback?code=a%20b&state=S`), env);
  const html = await res.text();
  assert.equal(res.status, 200);
  assert.match(html, /fluteisland:\/\/jira\/callback\?code=a%20b&amp;state=S/);
});

test("callback output is escaped", async () => {
  const res = await handle(new Request(`${ORIGIN}/jira/callback?x="><script>alert(1)</script>`), env);
  const html = await res.text();
  assert.doesNotMatch(html, /<script>alert/);
});

test("token exchange adds the secret and returns only token fields", async () => {
  const f = fakeFetch(200, { access_token: "A", refresh_token: "R", expires_in: 3600, scope: "s", extra: "leak" });
  const res = await handle(post("/jira/token", { code: "c1", redirect_uri: `${ORIGIN}/jira/callback` }), env, f);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { access_token: "A", refresh_token: "R", expires_in: 3600, scope: "s" });
  assert.equal(f.calls[0].url, "https://auth.atlassian.com/oauth/token");
  assert.deepEqual(f.calls[0].body, {
    grant_type: "authorization_code", code: "c1", redirect_uri: `${ORIGIN}/jira/callback`, client_id: "cid", client_secret: "sec",
  });
});

test("token exchange refuses a foreign redirect_uri", async () => {
  const f = fakeFetch(200, {});
  const res = await handle(post("/jira/token", { code: "c1", redirect_uri: "https://evil.example/cb" }), env, f);
  assert.equal(res.status, 400);
  assert.equal(f.calls.length, 0);
});

test("refresh uses the refresh grant", async () => {
  const f = fakeFetch(200, { access_token: "A2", refresh_token: "R2", expires_in: 3600 });
  const res = await handle(post("/jira/refresh", { refresh_token: "R1" }), env, f);
  assert.equal(res.status, 200);
  assert.equal(f.calls[0].body.grant_type, "refresh_token");
  assert.equal(f.calls[0].body.refresh_token, "R1");
});

test("upstream 4xx is passed on as a class of failure without details", async () => {
  const f = fakeFetch(403, { error: "invalid_grant", error_description: "secret detail" });
  const res = await handle(post("/jira/refresh", { refresh_token: "bad" }), env, f);
  assert.equal(res.status, 403);
  assert.deepEqual(await res.json(), { error: "invalid_grant" });
});

test("upstream 5xx becomes 502", async () => {
  const f = fakeFetch(500, {});
  const res = await handle(post("/jira/refresh", { refresh_token: "x" }), env, f);
  assert.equal(res.status, 502);
});

test("bad bodies are rejected before any upstream call", async () => {
  const f = fakeFetch(200, {});
  for (const body of ["not json", "null", JSON.stringify({}), JSON.stringify({ code: 5 })]) {
    const res = await handle(post("/jira/token", body), env, f);
    assert.equal(res.status, 400);
  }
  const huge = await handle(post("/jira/refresh", JSON.stringify({ refresh_token: "x".repeat(5000) })), env, f);
  assert.equal(huge.status, 400);
  assert.equal(f.calls.length, 0);
});

test("missing configuration is a 500, not a crash", async () => {
  const res = await handle(post("/jira/refresh", { refresh_token: "x" }), { JIRA_CLIENT_ID: "" }, fakeFetch(200, {}));
  assert.equal(res.status, 500);
});

test("unknown routes and wrong methods are 404", async () => {
  assert.equal((await handle(new Request(`${ORIGIN}/nope`), env)).status, 404);
  assert.equal((await handle(new Request(`${ORIGIN}/jira/token`), env)).status, 404);
});
