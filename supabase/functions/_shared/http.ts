// Shared HTTP helpers for UPIPaymentAlert Edge Functions.
// No third-party imports: the Edge runtime provides fetch/crypto/TextEncoder.

export const CORS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers":
    "authorization, x-client-info, apikey, content-type",
};

export function json(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

export function preflight(req: Request): Response | null {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: CORS });
  }
  return null;
}

export async function readJson(
  req: Request,
): Promise<Record<string, unknown> | null> {
  try {
    const parsed: unknown = await req.json();
    if (typeof parsed !== "object" || parsed === null) return null;
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

export function asString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

/**
 * The Android client URL-encodes values inside its JSON body (form-style,
 * java.net.URLEncoder), e.g. { "env": "%7B%22k%22%3A..." }. Decode exactly
 * once, here. Falls back to the raw value on malformed sequences so a bad
 * byte can never throw past this boundary.
 */
export function urlDecode(value: unknown): string {
  if (typeof value !== "string") return "";
  try {
    return decodeURIComponent(value.replace(/\+/g, "%20"));
  } catch {
    return value;
  }
}

/**
 * Stable, privacy-preserving caller key for rate limiting: SHA-256 of the
 * client IP (peppered), truncated. The raw IP is never stored or logged.
 */
export async function callerKey(req: Request): Promise<string> {
  const fwd = req.headers.get("x-forwarded-for") ??
    req.headers.get("x-real-ip") ?? "unknown";
  const ip = (fwd.split(",")[0] ?? "unknown").trim();
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(`upipaymentalert|${ip}`),
  );
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("")
    .slice(0, 32);
}
