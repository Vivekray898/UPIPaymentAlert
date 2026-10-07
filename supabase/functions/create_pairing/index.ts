// create_pairing — child device registers a 6-digit pairing code.
//
// POST { "pairing_code": "482913", "child_pub_key": "<X.509 b64url>",
//        "child_fcm_token": "<FCM token>" }
// 200 { "success": true, "pair_id": "<uuid>" }
// 400 invalid input | 409 code already in use | 429 rate limited
//
// Registry sees ONLY: 6-digit code, child public key, child FCM token,
// timestamps. Never amount, payer, UPI ID, SMS body, ciphertext, or keys.

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
    const pubKey = body ? asString(body.child_pub_key) : "";
    const fcmToken = body ? asString(body.child_fcm_token) : "";

    if (!/^\d{6}$/.test(code)) {
      return json(400, { success: false, error: "Invalid pairing code format" });
    }
    if (!pubKey || pubKey.length > 1024) {
      return json(400, { success: false, error: "Missing required fields" });
    }
    if (fcmToken.length > 4096) {
      return json(400, { success: false, error: "Invalid FCM token" });
    }

    // 10/5min per (hashed) IP: both devices behind one NAT share this budget,
    // and creating codes reveals nothing — the brute-force surface is the
    // LOOKUP limiter (10/5min against a 1e6 code space).
    const rl = await rateLimited(req, "create", 10);
    if (rl === false) {
      return json(429, { success: false, error: "Too many attempts, try again later" });
    }
    if (rl === null) {
      return json(503, { success: false, error: "Service temporarily unavailable" });
    }

    const r = rest();
    const res = await fetch(`${r.url}/rest/v1/pairings`, {
      method: "POST",
      headers: { ...r.headers, "Prefer": "return=representation" },
      body: JSON.stringify({
        pairing_code: code,
        child_pub_key: pubKey,
        child_fcm_token: fcmToken || null,
      }),
    });
    if (!res.ok) {
      const err = await res.text();
      if (res.status === 409 || err.includes("23505")) {
        return json(409, { success: false, error: "Pairing code already in use" });
      }
      return json(500, { success: false, error: "Failed to create pairing" });
    }
    const rows = await res.json() as Array<Record<string, unknown>>;
    const pairId = Array.isArray(rows) ? asString(rows[0]?.pair_id) : "";
    if (!pairId) {
      return json(500, { success: false, error: "Failed to create pairing" });
    }
    return json(200, { success: true, pair_id: pairId });
  } catch {
    return json(500, { success: false, error: "Internal error" });
  }
});
