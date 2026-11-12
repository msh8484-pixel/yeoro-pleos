// 여행 등록(POST)·삭제(DELETE). 차가 publishable 키로 부른다.
import { admin, removeTripFiles } from "../_shared/admin.ts";
import { CORS, fail, json, requireApiKey } from "../_shared/http.ts";
import { isDeviceSecret, isToken, parseIsoInstant, sha256Hex } from "../_shared/validate.ts";

const PURGE_GRACE_DAYS = 7;
const PURGE_BATCH = 20;

type TripRow = { token: string; device_hash: string; expires_at: string };

async function readBody(req: Request): Promise<Record<string, unknown> | null> {
  try {
    const body = await req.json();
    return body && typeof body === "object" && !Array.isArray(body) ? body : null;
  } catch {
    return null;
  }
}

async function findTrip(token: string): Promise<TripRow | null> {
  const { data, error } = await admin()
    .from("trips")
    .select("token, device_hash, expires_at")
    .eq("token", token)
    .maybeSingle();
  if (error) throw error;
  return data as TripRow | null;
}

// 순서: trips 행을 먼저 지우고(photos는 cascade) 그 다음 Storage 파일을 지운다.
// 행이 없으면 아무도 이 토큰의 파일을 가리킬 수 없고, 파일 경로는 <token>/<uuid>.jpg 라서
// 같은 토큰으로 다시 등록해도 겹치지 않는다. Storage 삭제가 실패하면 남는 것은 안 보이는 파일뿐이다.
async function deleteTrip(token: string): Promise<void> {
  const { error } = await admin().from("trips").delete().eq("token", token);
  if (error) throw error;
  try {
    await removeTripFiles(token);
  } catch (e) {
    console.error("storage cleanup failed", e instanceof Error ? e.message : e);
  }
}

/** 만료 뒤 7일이 지난 여행을 최대 20개 정리한다. 실패해도 등록은 막지 않는다. */
async function purgeExpired(): Promise<void> {
  try {
    const cutoff = new Date(Date.now() - PURGE_GRACE_DAYS * 86_400_000).toISOString();
    const { data, error } = await admin()
      .from("trips")
      .select("token")
      .lt("expires_at", cutoff)
      .order("expires_at")
      .limit(PURGE_BATCH);
    if (error) throw error;
    for (const row of data ?? []) await deleteTrip(row.token);
  } catch (e) {
    console.error("purge failed", e instanceof Error ? e.message : e);
  }
}

// 응답을 막지 않도록 응답 경로 밖에서 돌린다. purgeExpired는 자체적으로 오류를 삼킨다.
function runInBackground(p: Promise<void>): void {
  const rt = (globalThis as { EdgeRuntime?: { waitUntil?: (p: Promise<unknown>) => void } }).EdgeRuntime;
  const safe = p.catch(() => {});
  if (rt?.waitUntil) rt.waitUntil(safe);
}

async function register(body: Record<string, unknown>): Promise<Response> {
  const { token, device, started_at, dest_name } = body;
  const started = parseIsoInstant(started_at);
  const destOk = dest_name === undefined || dest_name === null ||
    (typeof dest_name === "string" && [...dest_name].length <= 80);
  if (!isToken(token) || !isDeviceSecret(device) || !started || !destOk) {
    return fail(400, "bad_request");
  }
  const deviceHash = await sha256Hex(device);

  let existing = await findTrip(token);
  let created = false;
  if (!existing) {
    const { data, error } = await admin()
      .from("trips")
      .insert({
        token,
        device_hash: deviceHash,
        started_at: started.toISOString(),
        dest_name: typeof dest_name === "string" ? dest_name : null,
      })
      .select("token, device_hash, expires_at")
      .single();
    if (error) {
      if (error.code !== "23505") throw error; // 동시에 같은 토큰이 들어온 경우만 다시 읽는다
      existing = await findTrip(token);
      if (!existing) throw error;
    } else {
      existing = data as TripRow;
      created = true;
    }
  }
  if (existing.device_hash !== deviceHash) return fail(409, "token_taken");

  runInBackground(purgeExpired());
  return json({ token: existing.token, expires_at: existing.expires_at }, created ? 201 : 200);
}

async function remove(body: Record<string, unknown>): Promise<Response> {
  const { token, device } = body;
  if (!isToken(token) || !isDeviceSecret(device)) return fail(400, "bad_request");
  const existing = await findTrip(token);
  if (existing) {
    if (existing.device_hash !== (await sha256Hex(device))) return fail(403, "device_mismatch");
    await deleteTrip(token);
  }
  return new Response(null, { status: 204, headers: CORS });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const denied = requireApiKey(req);
  if (denied) return denied;
  if (req.method !== "POST" && req.method !== "DELETE") {
    const res = fail(405, "method_not_allowed");
    res.headers.set("Allow", "POST, DELETE, OPTIONS");
    return res;
  }

  const body = await readBody(req);
  if (!body) return fail(400, "bad_request");
  try {
    return req.method === "POST" ? await register(body) : await remove(body);
  } catch (e) {
    console.error("trip failed", e instanceof Error ? e.message : e);
    return fail(500, "server_error");
  }
});
