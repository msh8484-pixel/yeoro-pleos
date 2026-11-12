// 그날의 바깥: 목적지 좌표·시각의 날씨(기상청)·일출몰(천문연)·관광지 종류(TourAPI)·축제(TourAPI)·특일(천문연).
//   GET /outside?lat=&lon=&t=<epoch ms>[&parts=weather,sun,place,festival,holiday]   (apikey 필요)
//     → 200 {at, weather?, sun?, place?, festival?, holiday?, errors: ["part:reason"]}
//       요청한 칸이 실패하면 그 칸만 null, 이유는 errors. 요청하지 않은 칸은 빠진다.
//   GET /outside/holidays?year=2026 → 200 {year, holidays:[{date:"YYYY-MM-DD", name}]}
// DATA_GO_KR_KEY는 서버 secret으로만 쓰고, 응답·로그에 넣지 않는다.
import { CORS, fail, json, requireApiKey } from "../_shared/http.ts";
import {
  fcstAt,
  homepageHref,
  hhmm,
  items,
  kst,
  ncstBase,
  ptyText,
  resultCode,
  skyText,
  toGrid,
  totalCount,
  vilageBase,
} from "../_shared/datago.ts";
import { scrub as scrubKey } from "../_shared/scrub.ts";
import { pickFestival, placeKindOf, placeNameOf } from "../_shared/place.ts";

const KMA = "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0";
const RISE_SET = "https://apis.data.go.kr/B090041/openapi/service/RiseSetInfoService/getLCRiseSetInfo";
const REST_DE = "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";
const TOUR = "https://apis.data.go.kr/B551011/KorService2";
const TOUR_COMMON = { MobileOS: "ETC", MobileApp: "yeoro", _type: "json" };

const FEST_ROWS = 100; // searchFestival2 한 쪽 행 수(시군구 단위라 보통 한 쪽이면 된다)
const PART_TIMEOUT_MS = 6000; // 외부 호출 하나
const TOTAL_BUDGET_MS = 9000; // 요청 전체(함수 10초 안)
const ALL_PARTS = ["weather", "sun", "place", "festival", "holiday"] as const;
type Part = typeof ALL_PARTS[number];

/** 칸 실패 이유(errors에 "part:reason"으로 들어간다). */
class Reason extends Error {
  code: string;
  constructor(code: string) {
    super(code);
    this.code = code;
  }
}

function dataKey(): string {
  const key = Deno.env.get("DATA_GO_KR_KEY");
  if (!key) throw new Reason("no_key");
  return key;
}

/** 로그에 키가 섞이지 않게 가린다. */
const scrub = (msg: string) => scrubKey(msg, Deno.env.get("DATA_GO_KR_KEY"));

/** 받은 행이 totalCount보다 적으면(잘림) 경고 로그. */
function warnIfTruncated(what: string, body: unknown, got: number) {
  const total = totalCount(body);
  if (total !== null && total > got) console.warn("truncated", what, `${got}/${total}`);
}

function reasonOf(e: unknown): string {
  if (e instanceof Reason) return e.code;
  if (e instanceof DOMException && (e.name === "TimeoutError" || e.name === "AbortError")) return "timeout";
  if (e instanceof Error && (e.name === "TimeoutError" || e.name === "AbortError")) return "timeout";
  return "upstream";
}

type Params = Record<string, string | number>;

/** data.go.kr GET → JSON. HTTP 오류·JSON 아님(키 오류 XML 등)·resultCode 불일치는 Reason. */
async function getJson(base: string, params: Params, okCodes: string[], signal: AbortSignal): Promise<unknown> {
  const qs = new URLSearchParams({ serviceKey: dataKey() });
  for (const [k, v] of Object.entries(params)) qs.set(k, String(v));
  const res = await fetch(`${base}?${qs}`, { signal });
  const text = await res.text();
  if (!res.ok) throw new Reason(`http_${res.status}`);
  let body: unknown;
  try {
    body = JSON.parse(text);
  } catch {
    throw new Reason(/SERVICE_KEY|returnReasonCode>30</.test(text) ? "key_rejected" : "not_json");
  }
  const code = resultCode(body);
  if (code === "30") throw new Reason("key_rejected");
  if (code === null || !okCodes.includes(code)) throw new Reason(`code_${code}`);
  return body;
}

type Ctx = { lat: number; lon: number; t: Date; now: Date; signal: () => AbortSignal };

// ---------- 날씨 ----------
type Weather = {
  tempC: number | null;
  sky: string | null;
  precip: string | null;
  kind: "observed" | "forecast";
  source: string;
};

const num = (v: unknown): number | null => {
  const n = Number(v);
  return v != null && v !== "" && Number.isFinite(n) ? n : null;
};

async function weather(c: Ctx): Promise<Weather> {
  const { nx, ny } = toGrid(c.lat, c.lon);
  const nb = ncstBase(c.t, c.now);
  const vb = vilageBase(c.t, c.now);
  // 실황(24시간)·단기예보(그제 발표~4일 앞) 창 밖.
  // ASOS 활용신청이 승인되면 여기서 지상관측 일/시간자료(AsosHourlyInfoService)를 불러
  // { kind: "observed", source: "기상청 ASOS" }로 같은 모양을 돌려주면 된다.
  if (!nb && !vb) throw new Reason("out_of_window");

  const grid = { pageNo: 1, dataType: "JSON", nx, ny };
  const ncstP = nb
    ? getJson(`${KMA}/getUltraSrtNcst`, { ...grid, numOfRows: 20, base_date: nb.date, base_time: nb.time }, ["00"], c.signal())
      .then((b) => {
        const rows = items(b);
        warnIfTruncated("getUltraSrtNcst", b, rows.length);
        const obs: Record<string, string> = {};
        for (const it of rows) obs[String(it.category)] = String(it.obsrValue);
        return obs;
      })
    : Promise.resolve(null);
  const vilP = vb
    ? getJson(`${KMA}/getVilageFcst`, { ...grid, numOfRows: 1000, base_date: vb.date, base_time: vb.time }, ["00"], c.signal())
      .then((b) => {
        const rows = items(b);
        warnIfTruncated("getVilageFcst", b, rows.length);
        return fcstAt(rows, c.t);
      })
    : Promise.resolve(null);
  const [nr, vr] = await Promise.allSettled([ncstP, vilP]);
  const slot = vr.status === "fulfilled" ? vr.value : null;

  if (nr.status === "fulfilled" && nr.value && num(nr.value.T1H) !== null) {
    return {
      tempC: num(nr.value.T1H),
      sky: skyText(slot?.SKY),
      precip: ptyText(nr.value.PTY, true),
      kind: "observed",
      source: "기상청",
    };
  }
  if (slot && num(slot.TMP) !== null) {
    return {
      tempC: num(slot.TMP),
      sky: skyText(slot.SKY),
      precip: ptyText(slot.PTY, false),
      kind: "forecast",
      source: "기상청",
    };
  }
  if (vr.status === "rejected") throw vr.reason;
  if (nr.status === "rejected") throw nr.reason;
  throw new Reason("no_data");
}

// ---------- 일출·일몰 ----------
async function sun(c: Ctx) {
  const b = await getJson(RISE_SET, {
    locdate: kst(c.t).date,
    longitude: c.lon.toFixed(4),
    latitude: c.lat.toFixed(4),
    dnYn: "Y",
    _type: "json",
  }, ["00"], c.signal());
  const it = items(b)[0];
  const sunrise = hhmm(it?.sunrise), sunset = hhmm(it?.sunset);
  if (!sunrise && !sunset) throw new Reason("no_data");
  return { sunrise, sunset, source: "천문연" };
}

// ---------- 장소·축제 (TourAPI) ----------
// 1000m 목록 하나를 종류(500m, placeKindOf)·이름(1000m, placeNameOf)·축제 지역이 함께 쓴다.
// deno-lint-ignore no-explicit-any
async function nearby(c: Ctx): Promise<any[]> {
  const b = await getJson(`${TOUR}/locationBasedList2`, {
    ...TOUR_COMMON,
    mapX: c.lon,
    mapY: c.lat,
    radius: 1000,
    arrange: "E",
    numOfRows: 50,
    pageNo: 1,
  }, ["0000"], c.signal());
  const rows = items(b);
  warnIfTruncated("locationBasedList2", b, rows.length);
  return rows;
}

/** detailCommon2의 homepage. 실패해도 칸 전체를 망치지 않게 null. */
async function homepage(contentId: string, c: Ctx): Promise<string | null> {
  if (!contentId) return null;
  try {
    const b = await getJson(`${TOUR}/detailCommon2`, { ...TOUR_COMMON, contentId }, ["0000"], c.signal());
    return homepageHref(items(b)[0]?.homepage);
  } catch (e) {
    console.warn("homepage failed", contentId, reasonOf(e));
    return null;
  }
}

// deno-lint-ignore no-explicit-any
async function place(c: Ctx, nearP: Promise<any[]>) {
  const list = await nearP;
  const named = placeNameOf(list);
  return {
    kind: placeKindOf(list),
    name: named?.name ?? null,
    contentId: named?.contentId ?? null,
    distanceM: named?.distanceM ?? null,
    // 다시 가기 링크에 쓴다. 이름을 고른 장소가 있을 때만 detailCommon2를 부른다.
    homepage: named ? await homepage(named.contentId, c) : null,
    source: "TourAPI",
  };
}

// deno-lint-ignore no-explicit-any
async function festival(c: Ctx, nearP: Promise<any[]>) {
  let list;
  try {
    list = await nearP;
  } catch (e) {
    throw new Reason(`no_region(${reasonOf(e)})`);
  }
  const first = list[0];
  const regn = first?.lDongRegnCd, signgu = first?.lDongSignguCd;
  if (!regn) return null; // 1km 안에 TourAPI 항목이 없으면 지역을 모른다 → 축제 없음
  const date = kst(c.t).date;
  const params: Params = { ...TOUR_COMMON, eventStartDate: date, lDongRegnCd: String(regn), numOfRows: FEST_ROWS, pageNo: 1 };
  if (signgu) params.lDongSignguCd = String(signgu);
  const b = await getJson(`${TOUR}/searchFestival2`, params, ["0000"], c.signal());
  const rows = items(b);
  // 진행 중 축제가 둘째 쪽에 있을 수 있다: totalCount가 한 쪽을 넘으면 2쪽까지 받는다.
  const total = totalCount(b);
  if (total !== null && total > rows.length && rows.length >= FEST_ROWS) {
    const b2 = await getJson(`${TOUR}/searchFestival2`, { ...params, pageNo: 2 }, ["0000"], c.signal());
    rows.push(...items(b2));
    warnIfTruncated("searchFestival2", b, rows.length);
  }
  const f = pickFestival(rows, date, c.lat, c.lon);
  if (!f) return null;
  return { ...f, homepage: await homepage(f.contentId, c), source: "TourAPI" };
}

// ---------- 특일 ----------
type Holiday = { date: string; name: string };

async function restDays(year: number, month: number | null, signal: AbortSignal): Promise<Holiday[]> {
  const params: Params = { solYear: year, numOfRows: 50, pageNo: 1, _type: "json" };
  if (month !== null) params.solMonth = String(month).padStart(2, "0");
  const b = await getJson(REST_DE, params, ["00"], signal);
  return items(b)
    .filter((it) => String(it?.isHoliday) === "Y" && /^\d{8}$/.test(String(it?.locdate)))
    .map((it) => {
      const d = String(it.locdate);
      return { date: `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)}`, name: String(it.dateName ?? "") };
    })
    .sort((a, b) => a.date.localeCompare(b.date));
}

async function holiday(c: Ctx) {
  const k = kst(c.t).date;
  const list = await restDays(+k.slice(0, 4), +k.slice(4, 6), c.signal());
  const today = `${k.slice(0, 4)}-${k.slice(4, 6)}-${k.slice(6, 8)}`;
  const hit = list.find((h) => h.date === today);
  return hit ? { name: hit.name } : null;
}

// ---------- 요청 처리 ----------
function kstIso(t: Date): string {
  const k = new Date(t.getTime() + 9 * 3_600_000).toISOString().slice(0, 19);
  return `${k}+09:00`;
}

function parseCoord(s: string | null, min: number, max: number): number | null {
  if (s === null || s.trim() === "") return null;
  const n = Number(s);
  return Number.isFinite(n) && n >= min && n <= max ? n : null;
}

async function outside(url: URL): Promise<Response> {
  const q = url.searchParams;
  const lat = parseCoord(q.get("lat"), 33, 39);
  const lon = parseCoord(q.get("lon"), 124, 132);
  const tRaw = q.get("t");
  const tMs = tRaw !== null && /^\d{1,15}$/.test(tRaw) ? Number(tRaw) : NaN;
  if (lat === null || lon === null || !Number.isFinite(tMs)) return fail(400, "bad_request");
  const t = new Date(tMs);
  if (t.getUTCFullYear() < 2000 || t.getUTCFullYear() > 2100) return fail(400, "bad_request");

  const partsParam = q.get("parts");
  const wanted: Part[] = partsParam === null
    ? [...ALL_PARTS]
    : ALL_PARTS.filter((p) => partsParam.split(",").map((s) => s.trim()).includes(p));
  if (wanted.length === 0) return fail(400, "bad_request");

  const deadline = AbortSignal.timeout(TOTAL_BUDGET_MS);
  const c: Ctx = {
    lat, lon, t, now: new Date(),
    signal: () => AbortSignal.any([AbortSignal.timeout(PART_TIMEOUT_MS), deadline]),
  };
  const needNear = wanted.includes("place") || wanted.includes("festival");
  const nearP = needNear ? nearby(c) : Promise.resolve([]);
  nearP.catch(() => {}); // 두 칸이 각각 처리한다(처리 안 된 거절 경고 막기)

  const runners: Record<Part, () => Promise<unknown>> = {
    weather: () => weather(c),
    sun: () => sun(c),
    place: () => place(c, nearP),
    festival: () => festival(c, nearP),
    holiday: () => holiday(c),
  };
  const settled = await Promise.allSettled(wanted.map((p) => runners[p]()));

  const out: Record<string, unknown> = { at: kstIso(t) };
  const errors: string[] = [];
  settled.forEach((r, i) => {
    const part = wanted[i];
    if (r.status === "fulfilled") {
      out[part] = r.value;
    } else {
      out[part] = null;
      const reason = reasonOf(r.reason);
      errors.push(`${part}:${reason}`);
      if (reason !== "out_of_window") {
        const msg = r.reason instanceof Error ? r.reason.message : String(r.reason);
        console.error("outside part failed", part, reason, scrub(msg));
      }
    }
  });
  out.errors = errors;
  const res = json(out);
  res.headers.set("Cache-Control", "no-store"); // 시각·부분 실패에 따라 달라진다
  return res;
}

async function holidays(url: URL): Promise<Response> {
  const y = url.searchParams.get("year") ?? "";
  if (!/^\d{4}$/.test(y) || +y < 2000 || +y > 2100) return fail(400, "bad_request");
  try {
    const list = await restDays(+y, null, AbortSignal.timeout(PART_TIMEOUT_MS));
    const res = json({ year: +y, holidays: list });
    res.headers.set("Cache-Control", "public, max-age=86400");
    return res;
  } catch (e) {
    const reason = reasonOf(e);
    console.error("holidays failed", reason, scrub(e instanceof Error ? e.message : String(e)));
    return fail(502, `upstream_${reason}`);
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
  const denied = requireApiKey(req);
  if (denied) return denied;
  if (req.method !== "GET") {
    const res = fail(405, "method_not_allowed");
    res.headers.set("Allow", "GET, OPTIONS");
    return res;
  }
  const url = new URL(req.url);
  try {
    if (url.pathname.replace(/\/+$/, "").endsWith("/holidays")) return await holidays(url);
    return await outside(url);
  } catch (e) {
    console.error("outside failed", scrub(e instanceof Error ? e.message : String(e)));
    return fail(500, "server_error");
  }
});
