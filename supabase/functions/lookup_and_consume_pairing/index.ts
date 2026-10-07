// lookup_and_consume_pairing — owner device redeems a 6-digit pairing code.
//
// POST { "pairing_code": "482913" }
// 200 { "success": true, "pair_id": "<uuid>", "child_pub_key": "...",
//       "child_fcm_token": "..." }
// 400 invalid input | 404 unknown/expired/used | 429 rate limited
//
// Single-use: the consume is ONE atomic UPDATE (only an active, unexpired row
// matches), so two owners racing the same code can never both win.

import { rateLimited, rest } from "../_shared/db.ts";
import { asString, json, preflight, readJson } from "../_shared/http.ts";

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  if (req.method !== "POST") {
    return json(405, { success: false, error: "Method not allowed" });
  }
  try {
    const body = await readJson(req);
    const code = body ? asString(body.pairing_code).trim() : "";
    if (!/^\d{6}$/.test(code)) {
      return json(400, { success: false, error: "Invalid pairing code" });
    }

    const rl = await rateLimited(req, "lookup", 10);
    if (rl === false) {
      return json(429, { success: false, error: "Too many attempts, try again later" });
    }
    if (rl === null) {
      return json(503, { success: false, error: "Service temporarily unavailable" });
    }

    const r = rest();
    const nowIso = new Date().toISOString();
    const res = await fetch(
      `${r.url}/rest/v1/pairings?pairing_code=eq.${
        encodeURIComponent(code)
      }&consumed_at=is.null&expires_at=gte.${encodeURIComponent(nowIso)}`,
      {
        method: "PATCH",
        headers: { ...r.headers, "Prefer": "return=representation" },
        body: JSON.stringify({ consumed_at: nowIso }),
      },
    );
    if (!res.ok) {
      return json(500, { success: false, error: "Lookup failed" });
    }
    const rows = await res.json() as Array<Record<string, unknown>>;
    if (!Array.isArray(rows) || rows.length !== 1) {
      return json(404, {
        success: false,
        error: "Pairing code not found, expired, or already used",
      });
    }
    const row = rows[0];
    return json(200, {
      success: true,
      pair_id: asString(row.pair_id),
      child_pub_key: asString(row.child_pub_key),
      child_fcm_token: row.child_fcm_token == null
        ? ""
        : asString(row.child_fcm_token),
    });
  } catch {
    return json(500, { success: false, error: "Internal error" });
  }
});
