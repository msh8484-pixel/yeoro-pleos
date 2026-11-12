// 순수 검사 함수. Deno(Edge Function)와 Node(테스트) 양쪽에서 돈다.

const TOKEN_RE = /^[A-Za-z0-9_-]{22}$/;
const DEVICE_SECRET_RE = /^[A-Za-z0-9_-]{43}$/; // 32바이트 base64url(패딩 없음)
// 날짜·시각 + 오프셋(Z 또는 ±HH:MM) 필수
const ISO_INSTANT_RE =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/;

/** 여행 공유 토큰: 128비트 난수의 base64url 22자. */
export function isToken(s: unknown): s is string {
  return typeof s === "string" && TOKEN_RE.test(s);
}

/** 기기 비밀: 32바이트 난수의 base64url 43자. */
export function isDeviceSecret(s: unknown): s is string {
  return typeof s === "string" && DEVICE_SECRET_RE.test(s);
}

/** UTF-8 문자열의 SHA-256 소문자 hex. */
export async function sha256Hex(s: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, "0")).join("");
}

/** 오프셋이 있는 ISO 8601 시각만 받는다. 아니면 null. */
export function parseIsoInstant(s: unknown): Date | null {
  if (typeof s !== "string") return null;
  const m = ISO_INSTANT_RE.exec(s);
  if (!m) return null;
  const [, y, mo, d, h, mi, sec] = m;
  if (+mo < 1 || +mo > 12 || +d < 1 || +d > 31 || +h > 23 || +mi > 59 || (sec !== undefined && +sec > 59)) {
    return null;
  }
  // 2026-02-31 같은 없는 날짜: 해당 월의 날짜로 되돌아오는지 확인한다.
  if (new Date(Date.UTC(+y, +mo - 1, +d)).getUTCDate() !== +d) return null;
  // 오프셋은 실제 있을 수 있는 범위(±14:00 이내, 분 < 60)만: 이상한 값이 DB에서 500이 되지 않게.
  const off = m[7];
  if (off !== "Z" && (+off.slice(4, 6) > 59 || +off.slice(1, 3) * 60 + +off.slice(4, 6) > 14 * 60)) return null;
  const date = new Date(s);
  return Number.isNaN(date.getTime()) ? null : date;
}

// ---------- 사진 지우기 ----------

const DELETE_KEY_RE = /^[A-Za-z0-9_-]{22}$/; // 128비트 base64url(패딩 없음)
const PHOTO_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const MAX_DELETE_IDS = 50;

/** 바이트 → base64url(패딩 없음) */
export function base64Url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** 올린 사람이 자기 사진을 지울 때 쓰는 열쇠: 128비트 난수의 base64url 22자. 서버에는 sha256만 남긴다. */
export function newDeleteKey(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(16)));
}

export function isDeleteKey(s: unknown): s is string {
  return typeof s === "string" && DELETE_KEY_RE.test(s);
}

/** 사진 id: 소문자 uuid (gen_random_uuid / crypto.randomUUID). */
export function isPhotoId(s: unknown): s is string {
  return typeof s === "string" && PHOTO_ID_RE.test(s);
}

/**
 * `id`(하나)와 `ids`(쉼표로 여러 개)를 합쳐 중복 없는 목록으로. 대문자 uuid는 소문자로 맞춘다.
 * 하나도 없거나, 형식이 틀린 것이 있거나, max개를 넘으면 null.
 */
export function parsePhotoIds(id: string | null, ids: string | null, max = MAX_DELETE_IDS): string[] | null {
  const parts = [id ?? "", ...(ids ?? "").split(",")].map((s) => s.trim().toLowerCase()).filter((s) => s !== "");
  if (parts.length === 0 || !parts.every(isPhotoId)) return null;
  const unique = [...new Set(parts)];
  return unique.length <= max ? unique : null;
}

/** 같은 길이 문자열을 처음부터 끝까지 다 비교한다(길이가 다르면 false). 해시 비교용. */
export function constantTimeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
