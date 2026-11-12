// 공공데이터포털(data.go.kr) 응답 다루기: 항목 정규화, 기상청 격자, base 시각 고르기, 코드 → 한국어.
// 순수 모듈(Node가 타입만 지워 실행). 네트워크·Deno 없음.

const HOUR = 3_600_000;
const MIN = 60_000;
const KST_OFFSET = 9 * HOUR;

/** data.go.kr JSON에서 `items.item`을 늘 배열로: 여러 개면 배열, 하나면 객체, 없으면 `""`. */
// deno-lint-ignore no-explicit-any
export function items(body: unknown): any[] {
  // deno-lint-ignore no-explicit-any
  const b = body as any;
  const container = b?.response?.body?.items ?? b?.body?.items ?? b?.items;
  const item = container && typeof container === "object" ? container.item : undefined;
  if (Array.isArray(item)) return item;
  if (item && typeof item === "object") return [item];
  return [];
}

/** 정상 `response.header.resultCode`, 또는 키 오류 등 `OpenAPI_ServiceResponse.cmmMsgHeader.returnReasonCode`. */
export function resultCode(body: unknown): string | null {
  // deno-lint-ignore no-explicit-any
  const b = body as any;
  const code = b?.response?.header?.resultCode ??
    b?.OpenAPI_ServiceResponse?.cmmMsgHeader?.returnReasonCode;
  return code == null ? null : String(code);
}

/** `response.body.totalCount`(숫자). 없으면 null. 한 번에 받은 행이 모자라는지 보려고 쓴다. */
export function totalCount(body: unknown): number | null {
  // deno-lint-ignore no-explicit-any
  const b = body as any;
  const n = Number(b?.response?.body?.totalCount ?? b?.body?.totalCount);
  return Number.isFinite(n) ? n : null;
}

/** 위경도 → 기상청 동네예보 격자(LCC). */
export function toGrid(lat: number, lon: number): { nx: number; ny: number } {
  const RE = 6371.00877, GRID = 5.0, SLAT1 = 30.0, SLAT2 = 60.0, OLON = 126.0, OLAT = 38.0;
  const XO = 43, YO = 136;
  const DEG = Math.PI / 180.0;
  const re = RE / GRID;
  const slat1 = SLAT1 * DEG, slat2 = SLAT2 * DEG, olon = OLON * DEG, olat = OLAT * DEG;
  let sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
  sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
  let sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5);
  sf = (Math.pow(sf, sn) * Math.cos(slat1)) / sn;
  let ro = Math.tan(Math.PI * 0.25 + olat * 0.5);
  ro = (re * sf) / Math.pow(ro, sn);
  let ra = Math.tan(Math.PI * 0.25 + lat * DEG * 0.5);
  ra = (re * sf) / Math.pow(ra, sn);
  let theta = lon * DEG - olon;
  if (theta > Math.PI) theta -= 2.0 * Math.PI;
  if (theta < -Math.PI) theta += 2.0 * Math.PI;
  theta *= sn;
  return {
    nx: Math.floor(ra * Math.sin(theta) + XO + 0.5),
    ny: Math.floor(ro - ra * Math.cos(theta) + YO + 0.5),
  };
}

const p2 = (n: number) => String(n).padStart(2, "0");

/** 한국 시각(UTC+9)의 날짜 YYYYMMDD, 시각 HHMM, 시. */
export function kst(d: Date): { date: string; hhmm: string; hour: number } {
  const k = new Date(d.getTime() + KST_OFFSET);
  return {
    date: `${k.getUTCFullYear()}${p2(k.getUTCMonth() + 1)}${p2(k.getUTCDate())}`,
    hhmm: `${p2(k.getUTCHours())}${p2(k.getUTCMinutes())}`,
    hour: k.getUTCHours(),
  };
}

/** KST 기준으로 그 시각이 속한 정시(ms). */
function kstHourFloor(ms: number): number {
  return Math.floor((ms + KST_OFFSET) / HOUR) * HOUR - KST_OFFSET;
}

/** KST 자정(ms). */
function kstDayStart(ms: number): number {
  return Math.floor((ms + KST_OFFSET) / (24 * HOUR)) * 24 * HOUR - KST_OFFSET;
}

function baseOf(ms: number): { date: string; time: string } {
  const k = kst(new Date(ms));
  return { date: k.date, time: k.hhmm };
}

/**
 * 초단기실황(getUltraSrtNcst) base: t의 KST 정시. 그 정시 + 40분이 아직 안 지났으면 한 시간 앞.
 * now − base > 23시간이면(API는 최근 24시간만) null. t가 now + 10분보다 뒤(앞날)여도 null.
 */
export function ncstBase(t: Date, now: Date): { date: string; time: string } | null {
  const nowMs = now.getTime();
  if (t.getTime() > nowMs + 10 * MIN) return null;
  let base = kstHourFloor(t.getTime());
  if (nowMs < base + 40 * MIN) base -= HOUR;
  if (nowMs < base + 40 * MIN) return null;
  if (nowMs - base > 23 * HOUR) return null;
  return baseOf(base);
}

const VILAGE_HOURS = [2, 5, 8, 11, 14, 17, 20, 23];

/**
 * 단기예보(getVilageFcst) base: 발표 시각(02,05,…,23시) 중 min(t − 1시간, now − 10분) 이하의 가장 늦은 것.
 * (발표는 다음 정시부터 예보하므로 t − 1시간이라야 t의 정시 칸이 들어 있다: 14시 발표 → 15시부터.)
 * base_date가 KST 그제보다 이르면(API는 3일치만) null. t가 now + 4일을 넘으면 null.
 */
export function vilageBase(t: Date, now: Date): { date: string; time: string } | null {
  const nowMs = now.getTime();
  if (t.getTime() > nowMs + 4 * 24 * HOUR) return null;
  const ref = Math.min(t.getTime() - HOUR, nowMs - 10 * MIN);
  const day = kstDayStart(ref);
  const hourOfDay = Math.floor((ref - day) / HOUR);
  let base: number;
  const same = VILAGE_HOURS.filter((h) => h <= hourOfDay);
  if (same.length > 0) base = day + same[same.length - 1] * HOUR;
  else base = day - HOUR; // 전날 23시
  if (base < kstDayStart(nowMs) - 2 * 24 * HOUR) return null;
  return baseOf(base);
}

/**
 * 단기예보 행들(`fcstDate, fcstTime, category, fcstValue`)에서 t에 가장 가까운 예보 칸의 {category: value}.
 * 가장 가까운 칸도 90분 넘게 떨어져 있으면 null.
 */
// deno-lint-ignore no-explicit-any
export function fcstAt(rows: any[], t: Date): Record<string, string> | null {
  const slots = new Map<number, Record<string, string>>();
  for (const r of rows) {
    const d = String(r?.fcstDate ?? ""), h = String(r?.fcstTime ?? "");
    if (!/^\d{8}$/.test(d) || !/^\d{4}$/.test(h)) continue;
    const ms = Date.UTC(+d.slice(0, 4), +d.slice(4, 6) - 1, +d.slice(6, 8), +h.slice(0, 2), +h.slice(2, 4)) -
      KST_OFFSET;
    const slot = slots.get(ms) ?? {};
    slot[String(r.category)] = String(r.fcstValue);
    slots.set(ms, slot);
  }
  let best: number | null = null;
  for (const ms of slots.keys()) {
    if (best === null || Math.abs(ms - t.getTime()) < Math.abs(best - t.getTime())) best = ms;
  }
  if (best === null || Math.abs(best - t.getTime()) > 90 * MIN) return null;
  return slots.get(best) ?? null;
}

/** 하늘상태 SKY: 1 맑음, 3 구름많음, 4 흐림. */
export function skyText(code: unknown): "맑음" | "구름많음" | "흐림" | null {
  switch (String(code ?? "").trim()) {
    case "1": return "맑음";
    case "3": return "구름많음";
    case "4": return "흐림";
    default: return null;
  }
}

/** 강수형태 PTY. 0(없음)이면 null. 단기예보(ncst=false)는 4 소나기, 초단기(ncst=true)는 5·6·7. */
export function ptyText(code: unknown, ncst: boolean): string | null {
  const c = String(code ?? "").trim();
  const common: Record<string, string> = { "1": "비", "2": "비/눈", "3": "눈" };
  if (common[c]) return common[c];
  if (!ncst && c === "4") return "소나기";
  if (ncst) {
    const short: Record<string, string> = { "5": "빗방울", "6": "빗방울눈날림", "7": "눈날림" };
    if (short[c]) return short[c];
  }
  return null;
}

/** 천문연 "1736  " → "17:36". 형식이 틀리면 null. */
export function hhmm(s: unknown): string | null {
  const m = /^(\d{2})(\d{2})$/.exec(String(s ?? "").trim());
  if (!m || +m[1] > 23 || +m[2] > 59) return null;
  return `${m[1]}:${m[2]}`;
}

/** TourAPI homepage: `<a href="…">`면 href, 맨 URL이면 그대로. http/https만, 아니면 null. */
export function homepageHref(s: unknown): string | null {
  if (typeof s !== "string") return null;
  const text = s.trim();
  if (!text) return null;
  const a = /href\s*=\s*["']([^"']+)["']/i.exec(text);
  const raw = (a ? a[1] : text).trim().replace(/&amp;/g, "&");
  if (!/^https?:\/\/[^\s<>"']+$/i.test(raw)) return null;
  return raw;
}
