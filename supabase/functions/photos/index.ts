// 사진 목록(차 전용). apikey + x-yeoro-device(기기 비밀)가 맞아야 한다. 업로드 토큰만으로는 볼 수 없다.
//   GET ?t=<token> → {"expires_at","photos":[{id,taken_at,taken_at_source,uploaded_at,w,h,thumb_url,url}]}
//   DELETE ?t=<token>&id=<photo id> 또는 &ids=<id,id,…(50개까지)> → 204(이 여행의 사진을 하나라도 지움) / 400 / 403 / 404
//   (만료된 여행도 차는 지울 수 있다. 없는 id는 건너뛰고, 하나도 없으면 404 unknown_photo.)
import { admin, BUCKET, removePhotos } from "../_shared/admin.ts";
import { CORS, fail, json, requireApiKey } from "../_shared/http.ts";
import { constantTimeEqual, isDeviceSecret, isToken, parsePhotoIds, sha256Hex } from "../_shared/validate.ts";

const SIGNED_URL_SECONDS = 3600;

type PhotoRow = {
  id: string;
  taken_at: string | null;
  taken_at_source: string;
  uploaded_at: string;
  w: number;
  h: number;
  path: string;
  thumb_path: string;
};

type Trip = { device_hash: string; expires_at: string };

/** 여행을 찾고 기기 비밀을 확인한다. 실패면 Response, 통과하면 여행. */
async function ownTrip(token: string, device: string): Promise<Trip | Response> {
  const { data: trip, error } = await admin()
    .from("trips")
    .select("device_hash, expires_at")
    .eq("token", token)
    .maybeSingle();
  if (error) throw error;
  if (!trip) return fail(404, "unknown_token");
  if (!isDeviceSecret(device) || !constantTimeEqual(trip.device_hash, await sha256Hex(device))) {
    return fail(403, "device_mismatch");
  }
  return trip as Trip;
}

async function list(token: string, device: string): Promise<Response> {
  const trip = await ownTrip(token, device);
  if (trip instanceof Response) return trip;

  const { data: rows, error: rowsError } = await admin()
    .from("photos")
    .select("id, taken_at, taken_at_source, uploaded_at, w, h, path, thumb_path")
    .eq("token", token)
    .order("uploaded_at");
  if (rowsError) throw rowsError;
  const photos = (rows ?? []) as PhotoRow[];
  if (photos.length === 0) return json({ expires_at: trip.expires_at, photos: [] });

  const paths = photos.flatMap((p) => [p.path, p.thumb_path]);
  const { data: signed, error: signError } = await admin().storage.from(BUCKET)
    .createSignedUrls(paths, SIGNED_URL_SECONDS);
  if (signError) throw signError;
  const urlOf = new Map<string, string>();
  for (const s of signed ?? []) if (s.path && s.signedUrl) urlOf.set(s.path, s.signedUrl);

  return json({
    expires_at: trip.expires_at,
    photos: photos
      .filter((p) => urlOf.has(p.path) && urlOf.has(p.thumb_path))
      .map((p) => ({
        id: p.id,
        taken_at: p.taken_at,
        taken_at_source: p.taken_at_source,
        uploaded_at: p.uploaded_at,
        w: p.w,
        h: p.h,
        thumb_url: urlOf.get(p.thumb_path),
        url: urlOf.get(p.path),
      })),
  });
}

/** 차(주인)가 사진을 지운다. 누가 올렸든 지울 수 있다. */
async function remove(token: string, device: string, url: URL): Promise<Response> {
  const trip = await ownTrip(token, device);
  if (trip instanceof Response) return trip;
  const ids = parsePhotoIds(url.searchParams.get("id"), url.searchParams.get("ids"));
  if (!ids) return fail(400, "bad_ids");
  const { data: rows, error } = await admin()
    .from("photos")
    .select("id, path, thumb_path")
    .eq("token", token)
    .in("id", ids);
  if (error) throw error;
  if (!rows || rows.length === 0) return fail(404, "unknown_photo");
  await removePhotos(rows as { id: string; path: string; thumb_path: string }[]);
  return new Response(null, { status: 204, headers: CORS });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const denied = requireApiKey(req);
  if (denied) return denied;
  if (req.method !== "GET" && req.method !== "DELETE") {
    const res = fail(405, "method_not_allowed");
    res.headers.set("Allow", "GET, DELETE, OPTIONS");
    return res;
  }
  const url = new URL(req.url);
  const token = url.searchParams.get("t");
  if (!isToken(token)) return fail(404, "unknown_token");
  const device = req.headers.get("x-yeoro-device") ?? "";
  try {
    return req.method === "GET" ? await list(token, device) : await remove(token, device, url);
  } catch (e) {
    console.error("photos failed", e instanceof Error ? e.message : e);
    return fail(500, "server_error");
  }
});
