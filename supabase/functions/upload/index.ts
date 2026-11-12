// 사진 올리기. 키 없이 부른다(가족 휴대폰). CORS *.
//   GET  ?t=<token>          → 302 업로드 페이지(정적 호스팅)
//   GET  ?t=<token>&info=1   → 여행 정보 JSON
//   POST ?t=<token>          → multipart 사진 한 장 → 201 {id, count, delete_key}
//   DELETE ?t=<token>&id=<photo id>, 헤더 x-yeoro-delete: <delete_key>(또는 JSON {"key"}) → 204 / 403 / 404 / 410
import { admin, BUCKET, removePhotos } from "../_shared/admin.ts";
import { CORS, fail, json } from "../_shared/http.ts";
import {
  constantTimeEqual,
  isDeleteKey,
  isPhotoId,
  isToken,
  newDeleteKey,
  parseIsoInstant,
  sha256Hex,
} from "../_shared/validate.ts";
import { exifDateTimeOriginal, exifToIso } from "../_shared/exif.js";

const DEFAULT_PAGE_URL = "https://msh8484-pixel.github.io/yeoro-upload/";
const MAX_PHOTOS = 200;
const RATE_PER_MINUTE = 30;
const PHOTO_MAX_BYTES = 20 * 1024 * 1024;
const THUMB_MAX_BYTES = 1024 * 1024;
// multipart 전체 상한: 사진 20MB + 썸네일 1MB + 머리·필드 여유. formData()로 읽기 전에 거른다.
const REQUEST_MAX_BYTES = Math.floor(21.5 * 1024 * 1024);
// DB 트리거(photos_enforce_limits)가 내는 제한 오류 메시지 → 같은 오류 본문의 429
const LIMIT_ERRORS = ["photo_limit", "rate_limited"];
const SOURCES = ["exif", "file", "none"];

type Trip = { token: string; dest_name: string | null; started_at: string; expires_at: string };

async function findTrip(token: string): Promise<Trip | null> {
  if (!isToken(token)) return null;
  const { data, error } = await admin()
    .from("trips")
    .select("token, dest_name, started_at, expires_at")
    .eq("token", token)
    .maybeSingle();
  if (error) throw error;
  return data as Trip | null;
}

async function countPhotos(token: string, sinceIso?: string): Promise<number> {
  let q = admin().from("photos").select("id", { count: "exact", head: true }).eq("token", token);
  if (sinceIso) q = q.gt("uploaded_at", sinceIso);
  const { count, error } = await q;
  if (error) throw error;
  return count ?? 0;
}

/** 출발 날짜(한국 시각 기준) "YYYY-MM-DD" */
function startedOn(startedAt: string): string {
  return new Date(new Date(startedAt).getTime() + 9 * 3_600_000).toISOString().slice(0, 10);
}

function isJpeg(bytes: Uint8Array): boolean {
  return bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
}

function dimension(v: FormDataEntryValue | null): number | null {
  if (typeof v !== "string" || !/^\d{1,4}$/.test(v)) return null;
  const n = Number(v);
  return n >= 1 && n <= 8192 ? n : null;
}

/** 만료·없는 여행 검사. 통과하면 null. */
function tripGate(trip: Trip | null): Response | null {
  if (!trip) return fail(404, "unknown_token");
  if (new Date(trip.expires_at).getTime() <= Date.now()) return fail(410, "expired");
  return null;
}

async function info(token: string): Promise<Response> {
  const trip = await findTrip(token);
  const gate = tripGate(trip);
  if (gate) return gate;
  return json({
    dest_name: trip!.dest_name,
    started_on: startedOn(trip!.started_at),
    count: await countPhotos(token),
    max: MAX_PHOTOS,
    expires_at: trip!.expires_at,
  });
}

/**
 * 본문을 메모리에 쌓지 않고 흘려 버린다. 읽지 않고 바로 응답하면 게이트웨이가 413 대신 502/503을 낸다
 * (원격에서 확인). 끝없이 보내는 요청에 묶이지 않도록 64MB에서 끊는다.
 */
async function discardBody(req: Request): Promise<void> {
  const reader = req.body?.getReader();
  if (!reader) return;
  let seen = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return;
      seen += value.byteLength;
      if (seen > 64 * 1024 * 1024) {
        await reader.cancel();
        return;
      }
    }
  } catch {
    // 끊긴 연결: 무시
  }
}

async function upload(req: Request, token: string): Promise<Response> {
  const trip = await findTrip(token);
  const gate = tripGate(trip);
  if (gate) return gate;

  const length = Number(req.headers.get("content-length") ?? "");
  if (Number.isFinite(length) && length > REQUEST_MAX_BYTES) {
    await discardBody(req);
    return fail(413, "too_large");
  }

  let form: FormData;
  try {
    form = await req.formData();
  } catch {
    return fail(400, "bad_request");
  }
  const photo = form.get("photo");
  const thumb = form.get("thumb");
  if (!(photo instanceof File) || !(thumb instanceof File)) return fail(400, "bad_request");
  if (photo.size > PHOTO_MAX_BYTES || thumb.size > THUMB_MAX_BYTES) return fail(413, "too_large");
  const photoBytes = new Uint8Array(await photo.arrayBuffer());
  const thumbBytes = new Uint8Array(await thumb.arrayBuffer());
  if (!isJpeg(photoBytes) || !isJpeg(thumbBytes)) return fail(415, "not_jpeg");

  const w = dimension(form.get("w"));
  const h = dimension(form.get("h"));
  if (w === null || h === null) return fail(400, "bad_request");

  // 촬영 시각: 페이지가 보낸 값(exif|file) → 없으면 서버가 EXIF를 읽는다 → 그것도 없으면 none
  let takenAt: string | null = null;
  let source = "none";
  const sentAt = form.get("taken_at");
  const sentSource = form.get("taken_at_source");
  if (sentSource !== null && (typeof sentSource !== "string" || !SOURCES.includes(sentSource))) {
    return fail(400, "bad_request");
  }
  if (typeof sentAt === "string" && sentAt !== "") {
    if (!parseIsoInstant(sentAt)) return fail(400, "bad_request");
    takenAt = sentAt;
    source = sentSource === "exif" ? "exif" : "file";
  } else {
    // EXIF 값이 이상하면(없는 날짜·오프셋 등) 500 대신 촬영 시각 없음으로 둔다.
    const iso = exifToIso(exifDateTimeOriginal(photoBytes));
    takenAt = iso && parseIsoInstant(iso) ? iso : null;
    source = takenAt ? "exif" : "none";
  }

  // 빠른 거절. 동시 요청 사이의 경쟁은 DB 트리거가 원자적으로 막는다(아래 insert 오류 처리).
  const total = await countPhotos(token);
  if (total >= MAX_PHOTOS) return fail(429, "photo_limit");
  const recent = await countPhotos(token, new Date(Date.now() - 60_000).toISOString());
  if (recent >= RATE_PER_MINUTE) return fail(429, "rate_limited");

  const id = crypto.randomUUID();
  // 올린 사람만 아는 지우기 열쇠. 응답으로 한 번만 주고 서버에는 해시만 둔다(열쇠는 로그에 남기지 않는다).
  const deleteKey = newDeleteKey();
  const path = `${token}/${id}.jpg`;
  const thumbPath = `${token}/${id}_t.jpg`;
  const storage = admin().storage.from(BUCKET);
  const opts = { contentType: "image/jpeg", upsert: false };

  const up1 = await storage.upload(path, photoBytes, opts);
  if (up1.error) throw up1.error;
  const up2 = await storage.upload(thumbPath, thumbBytes, opts);
  if (up2.error) {
    await storage.remove([path]);
    throw up2.error;
  }
  const { error } = await admin().from("photos").insert({
    id,
    token,
    taken_at: takenAt,
    taken_at_source: source,
    path,
    thumb_path: thumbPath,
    w,
    h,
    bytes: photoBytes.length,
    delete_key_hash: await sha256Hex(deleteKey),
  });
  if (error) {
    await storage.remove([path, thumbPath]);
    if (LIMIT_ERRORS.includes(error.message)) return fail(429, error.message);
    throw error;
  }
  return json({ id, count: await countPhotos(token), delete_key: deleteKey }, 201);
}

/** 지우기 열쇠: 헤더 x-yeoro-delete, 없으면 JSON 본문 {"key"} 또는 {"delete_key"}. */
async function deleteKeyOf(req: Request): Promise<string> {
  const header = req.headers.get("x-yeoro-delete");
  if (header) return header.trim();
  try {
    const body = await req.json();
    const v = body && typeof body === "object" ? (body.key ?? body.delete_key) : null;
    return typeof v === "string" ? v : "";
  } catch {
    return "";
  }
}

/** 올린 사람이 자기 사진을 지운다. */
async function removeOwn(req: Request, token: string, id: string): Promise<Response> {
  const trip = await findTrip(token);
  const gate = tripGate(trip);
  if (gate) return gate;
  if (!isPhotoId(id)) return fail(404, "unknown_photo");
  const { data: row, error } = await admin()
    .from("photos")
    .select("id, path, thumb_path, delete_key_hash")
    .eq("token", token)
    .eq("id", id)
    .maybeSingle();
  if (error) throw error;
  if (!row) return fail(404, "unknown_photo");
  const key = await deleteKeyOf(req);
  const hash = row.delete_key_hash as string | null;
  if (!hash || !isDeleteKey(key) || !constantTimeEqual(hash, await sha256Hex(key))) {
    return fail(403, "delete_key_mismatch");
  }
  await removePhotos([row as { id: string; path: string; thumb_path: string }]);
  return new Response(null, { status: 204, headers: CORS });
}

function redirect(token: string): Response {
  const page = Deno.env.get("UPLOAD_PAGE_URL") || DEFAULT_PAGE_URL;
  return new Response(null, {
    status: 302,
    headers: { ...CORS, Location: `${page}?t=${encodeURIComponent(token)}` },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const url = new URL(req.url);
  const token = url.searchParams.get("t") ?? "";
  try {
    if (req.method === "GET") {
      return url.searchParams.get("info") === "1" ? await info(token) : redirect(token);
    }
    if (req.method === "POST") return await upload(req, token);
    if (req.method === "DELETE") return await removeOwn(req, token, (url.searchParams.get("id") ?? "").toLowerCase());
    const res = fail(405, "method_not_allowed");
    res.headers.set("Allow", "GET, POST, DELETE, OPTIONS");
    return res;
  } catch (e) {
    console.error("upload failed", e instanceof Error ? e.message : e);
    return fail(500, "server_error");
  }
});
