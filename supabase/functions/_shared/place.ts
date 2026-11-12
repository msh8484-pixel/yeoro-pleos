// TourAPI 결과 해석: 목적지 종류, 장소 이름, 진행 중인 축제 고르기. 순수 모듈.

export type PlaceKind = "TOURIST" | "LODGING" | "CAMPING" | "OTHER";

type NearItem = { contenttypeid?: unknown; cat3?: unknown; dist?: unknown; title?: unknown; contentid?: unknown };

const CAMPING_CAT3 = "A03021700";
const TOURIST_TYPES = new Set(["12", "14", "15", "28"]); // 관광지, 문화시설, 축제공연행사, 레포츠
const LODGING_TYPE = "32";

function within(it: NearItem, radiusM: number): boolean {
  const d = Number(it.dist);
  return Number.isFinite(d) && d <= radiusM;
}

/**
 * 반경 안 항목 중 하나라도: 캠핑장(cat3 A03021700) → CAMPING, 관광 유형(12·14·15·28) → TOURIST,
 * 숙박(32) → LODGING. 우선순위 CAMPING > TOURIST > LODGING, 아무것도 없으면 OTHER.
 * (위치 검색 결과는 빠짐이 있어 "가장 가까운 하나"가 아니라 "하나라도"로 판단한다.)
 */
export function placeKindOf(list: NearItem[], radiusM = 500): PlaceKind {
  const near = list.filter((it) => within(it, radiusM));
  if (near.some((it) => String(it.cat3 ?? "") === CAMPING_CAT3)) return "CAMPING";
  if (near.some((it) => TOURIST_TYPES.has(String(it.contenttypeid ?? "")))) return "TOURIST";
  if (near.some((it) => String(it.contenttypeid ?? "") === LODGING_TYPE)) return "LODGING";
  return "OTHER";
}

// 장소 이름 후보에서 빼는 리조트·상업 시설(앨범 문구로 어색하다).
const NAME_EXCLUDED_CAT3 = new Set([
  "A02020200", // 관광단지 (예: 라카이 샌드파인 수영장)
  "A02020300", // 온천/욕장/스파
  "A02020400", // 이색찜질방
  "A02020500", // 헬스투어
]);
const NAME_EXCLUDED_CAT1 = "A03"; // 레포츠

type NameItem = NearItem & { cat1?: unknown; cat2?: unknown };

/**
 * 장소 이름: 반경(기본 1000m) 안 관광지(12)·문화시설(14) 중 이름이 있고 리조트·상업 시설이 아닌 것.
 * 자연(cat1 A01)·역사(cat2 A0201)를 먼저(tier 0), 그다음 나머지(tier 1). 같은 tier 안에서는 가장 가까운 것.
 */
export function placeNameOf(
  list: NameItem[],
  radiusM = 1000,
): { name: string; contentId: string; distanceM: number } | null {
  let best: { tier: number; dist: number; it: NameItem } | null = null;
  for (const it of list) {
    const type = String(it.contenttypeid ?? "");
    if (type !== "12" && type !== "14") continue;
    const title = String(it.title ?? "").trim();
    if (!title || !within(it, radiusM)) continue;
    const cat1 = String(it.cat1 ?? ""), cat2 = String(it.cat2 ?? ""), cat3 = String(it.cat3 ?? "");
    if (NAME_EXCLUDED_CAT3.has(cat3) || cat1 === NAME_EXCLUDED_CAT1) continue;
    const tier = cat1 === "A01" || cat2 === "A0201" ? 0 : 1;
    const dist = Number(it.dist);
    if (!best || tier < best.tier || (tier === best.tier && dist < best.dist)) best = { tier, dist, it };
  }
  if (!best) return null;
  return {
    name: String(best.it.title).trim(),
    contentId: String(best.it.contentid ?? ""),
    distanceM: Math.round(best.dist),
  };
}

export function haversineKm(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const R = 6371.0088;
  const rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
}

const isYmd = (s: string) => /^\d{8}$/.test(s);
const dash = (s: string) => `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`;
const dayNumber = (s: string) => Date.UTC(+s.slice(0, 4), +s.slice(4, 6) - 1, +s.slice(6, 8)) / 86_400_000;

export type Festival = {
  name: string;
  start: string; // YYYY-MM-DD
  end: string;
  dayIndex: number; // 시작일이 1
  distanceKm: number; // 소수 한 자리
  contentId: string;
};

type FestivalItem = {
  title?: unknown; eventstartdate?: unknown; eventenddate?: unknown;
  mapx?: unknown; mapy?: unknown; contentid?: unknown;
};

/** date(YYYYMMDD)에 진행 중(start ≤ date ≤ end)이고 maxKm 안에서 가장 가까운 축제. 없으면 null. */
export function pickFestival(
  list: FestivalItem[],
  date: string,
  lat: number,
  lon: number,
  maxKm = 15,
): Festival | null {
  let best: Festival | null = null;
  let bestKm = Infinity;
  for (const it of list) {
    const start = String(it.eventstartdate ?? ""), end = String(it.eventenddate ?? "");
    if (!isYmd(start) || !isYmd(end) || !(start <= date && date <= end)) continue;
    const fx = Number(it.mapx), fy = Number(it.mapy);
    if (!it.mapx || !it.mapy || !Number.isFinite(fx) || !Number.isFinite(fy)) continue;
    const km = haversineKm({ lat, lon }, { lat: fy, lon: fx });
    if (km > maxKm) continue;
    if (km >= bestKm) continue;
    bestKm = km;
    best = {
      name: String(it.title ?? ""),
      start: dash(start),
      end: dash(end),
      dayIndex: dayNumber(date) - dayNumber(start) + 1,
      distanceKm: Math.round(km * 10) / 10,
      contentId: String(it.contentid ?? ""),
    };
  }
  return best;
}
