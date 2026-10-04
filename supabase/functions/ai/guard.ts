// Request guards for the `ai` function. Pure functions so they're unit-tested (ai_test.ts).

/** Largest request body accepted (the learn-cycle payload is the biggest, well under this). */
export const MAX_BODY_BYTES = 1_000_000;

/**
 * Who may spend the AI keys. ALLOWED_USER_IDS set → exactly those users. Unset → the caller must be
 * the project's FIRST account (checked server-side via the service role): Danny creates his account
 * before anyone else can even know the project exists, and CI disables sign-ups right after. A later
 * sign-up is never the oldest account, so it is refused without any manual configuration.
 */
export function allowedUser(
  userId: string,
  allowedIds: string | undefined,
): { ok: true } | { ok: false; error: string } | { checkFirstUser: true } {
  const allowed = (allowedIds ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  if (allowed.length === 0) return { checkFirstUser: true };
  return allowed.includes(userId) ? { ok: true } : { ok: false, error: "user not allowed" };
}

/**
 * The oldest account on the page, or null when it can't be decided safely (no users, or the page is
 * full so the true oldest might be beyond it — fail closed and ask for ALLOWED_USER_IDS instead).
 */
export function oldestUser(users: Array<{ id: string; created_at: string }>, pageSize = 50): string | null {
  if (users.length === 0 || users.length >= pageSize) return null;
  return users.reduce((a, b) => (a.created_at <= b.created_at ? a : b)).id;
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
