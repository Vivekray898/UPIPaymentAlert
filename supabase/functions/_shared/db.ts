// Minimal PostgREST client over the service-role key that the Supabase
// platform injects into every Edge Function (SUPABASE_URL /
// SUPABASE_SERVICE_ROLE_KEY). No third-party dependency.
//
// Privacy contract: these helpers only ever touch the `pairings` and
// `pairing_rate_limits` metadata tables. There is no payment data in the
// database at all.

import { callerKey } from "./http.ts";

export interface RestClient {
  url: string;
  headers: Record<string, string>;
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`missing environment variable ${name}`);
  return value;
}

export function rest(): RestClient {
  const url = requiredEnv("SUPABASE_URL");
  const key = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
  return {
    url,
    headers: {
      "apikey": key,
      "Authorization": `Bearer ${key}`,
      "Content-Type": "application/json",
    },
  };
}

/** true = allowed, false = over the limit, null = limiter unavailable. */
export type RateLimit = boolean | null;

/**
 * Server-side rate limiter (pairing_rate_allow RPC from migration
 * 20261006000002_pairing_rate_limits.sql).
 *
 * Returns:
 *   true  -> attempt allowed
 *   false -> caller exhausted their allowance (respond 429)
 *   null  -> limiter could not be consulted (respond 503). Never fail open:
 *            the 6-digit code space is only 1e6 values, so brute force must
 *            be impossible even when the limiter itself breaks.
 */
export async function rateLimited(
  req: Request,
  scope: string,
  maxAttempts: number,
): Promise<RateLimit> {
  try {
    const key = await callerKey(req);
    const r = rest();
    const res = await fetch(`${r.url}/rest/v1/rpc/pairing_rate_allow`, {
      method: "POST",
      headers: r.headers,
      body: JSON.stringify({
        p_scope: scope,
        p_key: key,
        p_max_attempts: maxAttempts,
        p_window: "5 minutes",
      }),
    });
    if (!res.ok) return null;
    const out = await res.text();
    return out.trim() === "true";
  } catch {
    return null;
  }
}
