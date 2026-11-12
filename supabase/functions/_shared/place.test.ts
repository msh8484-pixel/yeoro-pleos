import { test } from "node:test";
import assert from "node:assert/strict";
import { haversineKm, pickFestival, placeKindOf, placeNameOf } from "./place.ts";

// 경포해변 locationBasedList2(radius 1000) 응답 축약 (2026-10-28 실제 호출)
const GYEONGPO = [
  { contentid: "674023", contenttypeid: "15", cat3: "A02070200", title: "병오년 해맞이 행사", dist: "173.09" },
  { contentid: "3536121", contenttypeid: "39", cat3: "A05020100", title: "경포동해횟집", dist: "179.63" },
  { contentid: "3540945", contenttypeid: "32", cat3: "B02010900", title: "모텔 산토리니", dist: "330.10" },
  { contentid: "3351277", contenttypeid: "12", cat1: "A02", cat2: "A0202", cat3: "A02020200", title: "라카이 샌드파인 수영장", dist: "490.06" },
  { contentid: "2804293", contenttypeid: "32", cat3: "B02010100", title: "하이오션 경포", dist: "500.51" },
  { contentid: "3454461", contenttypeid: "12", cat1: "A02", cat2: "A0202", cat3: "A02020700", title: "경포호수광장", dist: "858.77" },
];

test("placeKindOf: 경포 → TOURIST", () => {
  assert.equal(placeKindOf(GYEONGPO), "TOURIST");
});

test("placeKindOf: 숙박만 → LODGING", () => {
  const lodging = [
    { contenttypeid: "32", cat3: "B02010900", dist: "120" },
    { contenttypeid: "39", cat3: "A05020100", dist: "200" },
    { contenttypeid: "12", cat3: "A02020200", dist: "700" }, // 반경 밖
  ];
  assert.equal(placeKindOf(lodging), "LODGING");
});

test("placeKindOf: 캠핑장이 가장 먼저", () => {
  const camping = [
    { contenttypeid: "12", cat3: "A01010100", dist: "100" },
    { contenttypeid: "28", cat3: "A03021700", dist: "300" },
    { contenttypeid: "32", cat3: "B02010700", dist: "50" },
  ];
  assert.equal(placeKindOf(camping), "CAMPING");
  assert.equal(placeKindOf([{ contenttypeid: "39", cat3: "A05020100", dist: "10" }]), "OTHER");
  assert.equal(placeKindOf([]), "OTHER");
  assert.equal(placeKindOf([{ contenttypeid: "14", cat3: "", dist: "450" }]), "TOURIST");
});

test("placeNameOf: 경포 → 관광단지(라카이) 빼고 경포호수광장, distanceM", () => {
  assert.deepEqual(placeNameOf(GYEONGPO), { name: "경포호수광장", contentId: "3454461", distanceM: 859 });
  assert.equal(placeNameOf(GYEONGPO, 800), null); // 라카이만 남는데 빠진다
});

test("placeNameOf: 리조트·상업 시설(cat3 4종·cat1 A03) 제외", () => {
  const pool = ["A02020200", "A02020300", "A02020400", "A02020500"].map((cat3, i) => ({
    contentid: String(i), contenttypeid: "12", cat1: "A02", cat2: "A0202", cat3, title: `시설${i}`, dist: String(10 + i),
  }));
  pool.push({ contentid: "9", contenttypeid: "12", cat1: "A03", cat2: "A0302", cat3: "A03022700", title: "레포츠", dist: "5" });
  assert.equal(placeNameOf(pool), null);
  pool.push({ contentid: "10", contenttypeid: "14", cat1: "A02", cat2: "A0206", cat3: "A02060100", title: "박물관", dist: "900" });
  assert.deepEqual(placeNameOf(pool), { name: "박물관", contentId: "10", distanceM: 900 });
});

test("placeNameOf: 자연(A01)·역사(A0201)가 더 가까운 A02보다 먼저, 같은 tier는 가까운 것", () => {
  const list = [
    { contentid: "1", contenttypeid: "12", cat1: "A02", cat2: "A0202", cat3: "A02020700", title: "광장", dist: "100" },
    { contentid: "2", contenttypeid: "12", cat1: "A01", cat2: "A0101", cat3: "A01011200", title: "해변", dist: "700.4" },
    { contentid: "3", contenttypeid: "12", cat1: "A01", cat2: "A0101", cat3: "A01010500", title: "호수", dist: "300.6" },
    { contentid: "4", contenttypeid: "12", cat1: "A02", cat2: "A0201", cat3: "A02010700", title: "정자", dist: "500" },
  ];
  assert.deepEqual(placeNameOf(list), { name: "호수", contentId: "3", distanceM: 301 });
  assert.deepEqual(placeNameOf(list.filter((x) => x.contentid !== "3")), { name: "정자", contentId: "4", distanceM: 500 });
});

test("placeNameOf: 빈 목록·이름 없음·반경 밖·다른 유형 → null", () => {
  assert.equal(placeNameOf([]), null);
  assert.equal(placeNameOf([{ contentid: "1", contenttypeid: "12", cat1: "A01", title: "  ", dist: "10" }]), null);
  assert.equal(placeNameOf([{ contentid: "1", contenttypeid: "12", cat1: "A01", title: "멀다", dist: "1000.5" }]), null);
  assert.equal(placeNameOf([{ contentid: "1", contenttypeid: "39", cat1: "A05", title: "식당", dist: "10" }]), null);
});

// searchFestival2 강릉(51/150) eventStartDate=20261024 응답 축약
const FESTIVALS = [
  { title: "강릉커피축제", eventstartdate: "20261021", eventenddate: "20261025", contentid: "825295",
    mapx: "128.9473094259", mapy: "37.7726104945" },
  { title: "강원한돈페스타", eventstartdate: "20261023", eventenddate: "20261025", contentid: "4127769",
    mapx: "128.8974540518", mapy: "37.7558959137" },
  { title: "2026 단단단페스티벌", eventstartdate: "20261126", eventenddate: "20261128", contentid: "2853386",
    mapx: "128.8950411936", mapy: "37.7482018271" },
];

test("pickFestival: 커피축제 넷째 날(2026-10-24)", () => {
  const f = pickFestival(FESTIVALS, "20261024", 37.8055, 128.9087);
  assert.ok(f);
  assert.equal(f.name, "강릉커피축제");
  assert.equal(f.start, "2026-10-21");
  assert.equal(f.end, "2026-10-25");
  assert.equal(f.dayIndex, 4);
  assert.equal(f.contentId, "825295");
  assert.ok(f.distanceKm > 3 && f.distanceKm < 6, String(f.distanceKm));
});

test("pickFestival: 끝난 축제·시작 전 축제·먼 축제 제외", () => {
  assert.equal(pickFestival(FESTIVALS, "20261026", 37.8055, 128.9087), null); // 모두 끝남, 단단단은 시작 전
  assert.equal(pickFestival(FESTIVALS, "20261024", 37.8055, 128.9087, 1), null); // 1km 안 없음
  const noCoord = [{ ...FESTIVALS[0], mapx: "", mapy: "" }];
  assert.equal(pickFestival(noCoord, "20261024", 37.8055, 128.9087), null);
  assert.equal(pickFestival(FESTIVALS, "20261121", 37.8055, 128.9087), null);
  const last = pickFestival(FESTIVALS, "20261025", 37.8055, 128.9087);
  assert.equal(last?.dayIndex, 5); // 마지막 날 포함
});

test("haversineKm", () => {
  const d = haversineKm({ lat: 37.5665, lon: 126.978 }, { lat: 37.8055, lon: 128.9087 });
  assert.ok(Math.abs(d - 171.6) < 2, String(d));
  assert.equal(haversineKm({ lat: 1, lon: 1 }, { lat: 1, lon: 1 }), 0);
});
