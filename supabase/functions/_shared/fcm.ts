// FCM HTTP v1 sender (OAuth2 service-account flow).
//
// The legacy FCM HTTP API (https://fcm.googleapis.com/fcm/send, key=...) was
// discontinued on 2024-06-20, and the AIza... Web API key shipped in
// google-services.json cannot send messages. FCM v1 requires an OAuth2 access
// token minted from a Firebase service-account key. That key lives ONLY here,
// as the FCM_SERVICE_ACCOUNT_JSON secret - never in the Android app.

interface ServiceAccount {
  project_id?: string;
  client_email: string;
  private_key: string;
}

let cachedToken: { value: string; expiresAt: number } | null = null;

function b64url(input: ArrayBuffer | Uint8Array): string {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(
    /=+$/,
    "",
  );
}

function serviceAccount(): ServiceAccount {
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");
  if (!raw) throw new Error("FCM_SERVICE_ACCOUNT_JSON not set");
  const parsed: unknown = JSON.parse(raw);
  const sa = parsed as ServiceAccount;
  if (!sa.client_email || !sa.private_key) {
    throw new Error("FCM_SERVICE_ACCOUNT_JSON is not a service-account key");
  }
  return sa;
}

function pkcs8Der(pem: string): ArrayBuffer {
  const body = pem
    .replace(/-----BEGIN [A-Z ]*PRIVATE KEY-----/g, "")
    .replace(/-----END [A-Z ]*PRIVATE KEY-----/g, "")
    .replace(/\s+/g, "");
  const binary = atob(body);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out.buffer;
}

/** Mint (and cache) a Google OAuth2 access token for FCM v1. */
export async function accessToken(): Promise<string> {
  if (cachedToken && Date.now() < cachedToken.expiresAt - 60_000) {
    return cachedToken.value;
  }
  const sa = serviceAccount();
  const now = Math.floor(Date.now() / 1000);
  const enc = new TextEncoder();
  const unsigned = `${b64url(enc.encode(JSON.stringify({
    alg: "RS256",
    typ: "JWT",
  })))}.${b64url(enc.encode(JSON.stringify({
    iss: sa.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })))}`;

  const key = await crypto.subtle.importKey(
    "pkcs8",
    pkcs8Der(sa.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    enc.encode(unsigned),
  );
  const assertion = `${unsigned}.${b64url(sig)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body:
      `grant_type=${
        encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer")
      }&assertion=${encodeURIComponent(assertion)}`,
  });
  if (!res.ok) throw new Error(`token exchange failed (${res.status})`);
  const tok = await res.json() as { access_token?: string; expires_in?: number };
  if (!tok.access_token) throw new Error("token exchange returned no token");
  cachedToken = {
    value: tok.access_token,
    expiresAt: now + (tok.expires_in ?? 3600),
  };
  return tok.access_token;
}

export type SendOutcome = { ok: boolean; invalidToken: boolean };

/**
 * Forward ONE data message to FCM v1. Stores nothing, logs nothing: the
 * payload is ciphertext that only the child device can open.
 */
export async function sendFcmData(
  deviceToken: string,
  data: Record<string, string>,
): Promise<SendOutcome> {
  const sa = serviceAccount();
  if (!sa.project_id) {
    throw new Error("service-account key has no project_id");
  }
  const bearer = await accessToken();
  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`,
    {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${bearer}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: { token: deviceToken, data, android: { priority: "HIGH" } },
      }),
    },
  );
  if (res.ok) return { ok: true, invalidToken: false };
  const text = await res.text();
  const invalidToken = res.status === 404 &&
    (text.includes("UNREGISTERED") || text.includes("NOT_FOUND"));
  return { ok: false, invalidToken };
}
