// Minimal Jira Cloud stand-in for the e2e run: GET /rest/api/3/search/jql with Basic auth.
//   node e2e/mock_jira.mjs [port]        (the phone reaches it through `adb reverse`)
// Control endpoints: POST /__tickets  [{key,summary}]  replaces the result set; GET /__log lists requests.
import http from "node:http";

const PORT = Number(process.argv[2] || 8089);
const EXPECTED = "Basic " + Buffer.from("e2e@example.com:token123").toString("base64");
let tickets = [{ key: "OPS-41", summary: "Login loops on retry" }];
const log = [];

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const send = (code, body) => { res.writeHead(code, { "content-type": "application/json" }); res.end(JSON.stringify(body)); };

  if (url.pathname === "/__tickets" && req.method === "POST") {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => { tickets = JSON.parse(body); send(200, { ok: true, count: tickets.length }); });
    return;
  }
  if (url.pathname === "/__log") return send(200, log);

  if (url.pathname === "/rest/api/3/search/jql") {
    const entry = { auth: req.headers.authorization === EXPECTED, jql: url.searchParams.get("jql"), fields: url.searchParams.get("fields"), at: Date.now() };
    log.push(entry);
    if (!entry.auth) return send(401, { errorMessages: ["bad credentials"] });
    return send(200, {
      issues: tickets.map((t) => ({
        key: t.key,
        fields: { summary: t.summary, status: { name: "Reopened" }, priority: { name: "High" } },
      })),
      isLast: true,
    });
  }
  send(404, { error: "not found" });
});

server.listen(PORT, "127.0.0.1", () => console.log(`mock jira on :${PORT}`));
