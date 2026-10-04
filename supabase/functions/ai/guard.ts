// Request guards for the `ai` function. Pure functions so they're unit-tested (ai_test.ts).

/** Largest request body accepted (the learn-cycle payload is the biggest, well under this). */
export const MAX_BODY_BYTES = 1_000_000;

/**
 * Who may spend the AI keys. Fails closed: with ALLOWED_USER_IDS unset or empty nobody may, because
 * the repo is public, the anon key ships in the APK, and Supabase allows sign-ups by default.
 */
export function allowedUser(userId: string, allowedIds: string | undefined): { ok: true } | { ok: false; error: string } {
  const allowed = (allowedIds ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  if (allowed.length === 0) {
    return { ok: false, error: "ALLOWED_USER_IDS is not set on the ai function (docs/INSTALL.md §7)" };
  }
  return allowed.includes(userId) ? { ok: true } : { ok: false, error: "user not allowed" };
}

/** Reads a JSON object body of at most [maxBytes]; returns an error string instead of throwing. */
export async function readJsonObject(req: Request, maxBytes = MAX_BODY_BYTES): Promise<{ body: Record<string, unknown> } | { error: string; status: number }> {
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (declared > maxBytes) return { error: "request too large", status: 413 };
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length > maxBytes) return { error: "request too large", status: 413 };
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return { error: "body must be JSON", status: 400 };
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return { error: "body must be a JSON object", status: 400 };
  }
  return { body: parsed as Record<string, unknown> };
}
