// update_child_fcm_token — child refreshes its FCM token after rotation.
//
// POST { "token": "<new FCM token>", "pair_id": "<uuid from registration>" }
// 200 { "success": true } | 400 bad input | 404 unknown pairing | 429 rate limited
//
// The child device sends its own pair_id (it learned it from create_pairing),
// so this can only ever touch that device's own row. Nothing else is stored.

import { rateLimited, rest } from "../_shared/db.ts";
import { asString, json, preflight, readJson } from "../_shared/http.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  if (req.method !== "POST") {
    return json(405, { success: false, error: "Method not allowed" });
  }
  try {
    const body = await readJson(req);
    const token = body ? asString(body.token).trim() : "";
    const pairId = body ? asString(body.pair_id).trim() : "";

    if (!token || token.length > 4096) {
      return json(400, { success: false, error: "Missing token" });
    }
    if (!UUID.test(pairId)) {
      return json(400, { success: false, error: "Missing or invalid pair_id" });
    }

    const rl = await rateLimited(req, "token", 30);
    if (rl === false) {
      return json(429, { success: false, error: "Too many attempts, try again later" });
    }
    if (rl === null) {
      return json(503, { success: false, error: "Service temporarily unavailable" });
    }

    const r = rest();
    const res = await fetch(
      `${r.url}/rest/v1/pairings?pair_id=eq.${encodeURIComponent(pairId)}`,
      {
        method: "PATCH",
        headers: { ...r.headers, "Prefer": "return=representation" },
        body: JSON.stringify({ child_fcm_token: token }),
      },
    );
    if (!res.ok) {
      return json(500, { success: false, error: "Failed to update token" });
    }
    const rows = await res.json() as unknown;
    if (!Array.isArray(rows) || rows.length !== 1) {
      return json(404, { success: false, error: "Pairing not found" });
    }
    return json(200, { success: true });
  } catch {
    return json(500, { success: false, error: "Internal error" });
  }
});
