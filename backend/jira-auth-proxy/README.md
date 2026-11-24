# Jira OAuth token proxy

Atlassian's OAuth 2.0 (3LO) needs a **client secret** to redeem an authorization code, and an
Android app can't keep a secret. This tiny Cloudflare Worker keeps it, so the app's users just tap
"Sign in with Atlassian" and never paste an API token.

It is stateless and logs nothing. Routes: `GET /jira/callback`, `POST /jira/token`, `POST /jira/refresh`.

## One-time setup

1. Atlassian developer console (https://developer.atlassian.com/console/myapps/) > **Create** > **OAuth 2.0 integration**.
2. **Permissions** > add **Jira API** > configure scope `read:jira-work`.
3. Deploy the worker (below) and note its URL, e.g. `https://flute-island-auth.<you>.workers.dev`.
4. **Authorization** > OAuth 2.0 (3LO) > **Callback URL** = `<worker url>/jira/callback`.
5. **Settings**: copy the **Client ID** into `wrangler.jsonc` (`JIRA_CLIENT_ID`) and into the app's
   `gradle.properties` (`fluteJiraClientId`); put the worker URL in `fluteJiraAuthProxy`.
6. Store the secret (never commit it): `npx wrangler secret put JIRA_CLIENT_SECRET`
7. Redeploy, rebuild the app.

```sh
npm install
npm test
npx wrangler deploy
```
