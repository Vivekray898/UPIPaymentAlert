// send_to_child — forward an encrypted envelope to the child's FCM token.
//
// POST { "fcm_token": "<url-encoded token>", "env": "<url-encoded envelope JSON>" }
// 200 { "success": true } | 400 bad input | 404 unknown/stale token
// 429 rate limited | 502 FCM rejected the send
//
// Privacy contract:
//   * This function holds the FCM v1 credential (service account) — the app
//     never does.
//   * It stores NOTHING and logs NOTHING about payments. The payload is
//     ciphertext only; the child device is the sole party that can open it.
//   * No registry check on the token: after pairing the owner holds a copy of
//     the token that may lag behind rotations, and a registry lookup would
//     break delivery in exactly that case. Abuse is bounded by rate limiting;
//     forgery is impossible without kMsg.

import { rateLimited } from "../_shared/db.ts";
import { json, preflight, readJson, urlDecode } from "../_shared/http.ts";
import { sendFcmData } from "../_shared/fcm.ts";

// RemoteEnvelope.MAX_CHARS is 1024; headroom below FCM's 4096-byte data cap.
const MAX_ENV_CHARS = 2048;

Deno.serve(async (req) => {
  const pre = preflight(req);
  if (pre) return pre;
  if (req.method !== "POST") {
    return json(405, { success: false, error: "Method not allowed" });
  }
  try {
    const body = await readJson(req);
    const token = urlDecode(body?.fcm_token);
    const env = urlDecode(body?.env);

    if (!token || token.length > 4096) {
      return json(400, { success: false, error: "Missing fcm_token" });
    }
    if (!env || env.length > MAX_ENV_CHARS) {
      return json(400, { success: false, error: "Missing or oversized env" });
    }
    // Structural check only — fields are never read, logged, or stored.
    try {
      JSON.parse(env);
    } catch {
      return json(400, { success: false, error: "Malformed env" });
    }

    const rl = await rateLimited(req, "send", 60);
    if (rl === false) {
      return json(429, { success: false, error: "Too many attempts, try again later" });
    }
    if (rl === null) {
      return json(503, { success: false, error: "Service temporarily unavailable" });
    }

    const outcome = await sendFcmData(token, { env });
    if (!outcome.ok) {
      return json(outcome.invalidToken ? 404 : 502, {
        success: false,
        error: outcome.invalidToken ? "Unknown FCM token" : "FCM send failed",
      });
    }
    return json(200, { success: true });
  } catch {
    return json(500, { success: false, error: "Internal error" });
  }
});
