// HTTP 공용 도구 (Deno 전용 부분: Deno.env)

export const CORS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-yeoro-device, x-yeoro-delete",
  "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json; charset=utf-8" },
  });
}

/** 오류 응답: {"error": code} */
export function fail(status: number, code: string): Response {
  return json({ error: code }, status);
}

function allowedApiKeys(): string[] {
  const keys: string[] = [];
  const publishable = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS");
  if (publishable) {
    try {
      const parsed = JSON.parse(publishable);
      if (parsed && typeof parsed === "object") {
        for (const v of Object.values(parsed)) if (typeof v === "string" && v) keys.push(v);
      }
    } catch {
      // 형식이 이상하면 무시하고 anon 키로 물러난다.
    }
  }
  const anon = Deno.env.get("SUPABASE_ANON_KEY");
  if (anon) keys.push(anon);
  return keys;
}

/** apikey 헤더가 이 프로젝트의 publishable(또는 legacy anon) 키가 아니면 401 응답, 맞으면 null. */
export function requireApiKey(req: Request): Response | null {
  const key = req.headers.get("apikey");
  if (key && allowedApiKeys().includes(key)) return null;
  return fail(401, "unauthorized");
}
