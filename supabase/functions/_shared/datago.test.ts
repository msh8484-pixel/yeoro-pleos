import { test } from "node:test";
import assert from "node:assert/strict";
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
} from "./datago.ts";

const K = (s: string) => new Date(s + "+09:00");

test("toGrid: 조사 결과 확인값 4개", () => {
  assert.deepEqual(toGrid(37.5665, 126.978), { nx: 60, ny: 127 }); // 서울시청
  assert.deepEqual(toGrid(37.5563, 126.922), { nx: 59, ny: 126 }); // 마포
  assert.deepEqual(toGrid(37.8055, 128.9087), { nx: 93, ny: 133 }); // 경포해변
  assert.deepEqual(toGrid(33.4996, 126.5312), { nx: 53, ny: 38 }); // 제주
});

test("items: 배열·객체 하나·빈 문자열·없음", () => {
  const wrap = (it: unknown) => ({ response: { header: { resultCode: "00" }, body: { items: it } } });
  assert.deepEqual(items(wrap({ item: [{ a: 1 }, { a: 2 }] })), [{ a: 1 }, { a: 2 }]);
  assert.deepEqual(items(wrap({ item: { a: 1 } })), [{ a: 1 }]);
  assert.deepEqual(items(wrap("")), []);
  assert.deepEqual(items(wrap(undefined)), []);
  assert.deepEqual(items(null), []);
  assert.deepEqual(items("<xml/>"), []);
});

test("resultCode: 정상·키 오류(XML 아닌 JSON 형태)", () => {
  assert.equal(resultCode({ response: { header: { resultCode: "0000" } } }), "0000");
  assert.equal(
    resultCode({ OpenAPI_ServiceResponse: { cmmMsgHeader: { returnReasonCode: "30" } } }),
    "30",
  );
  assert.equal(resultCode({}), null);
});

test("kst: 한국 시각으로 날짜·시각", () => {
  assert.deepEqual(kst(new Date("2026-10-24T04:20:00Z")), { date: "20261024", hhmm: "1320", hour: 13 });
  assert.deepEqual(kst(new Date("2026-10-24T15:05:00Z")), { date: "20261025", hhmm: "0005", hour: 0 });
});

test("ncstBase: 정시, 40분 전이면 한 시간 앞, 23시간 넘으면 null", () => {
  assert.deepEqual(ncstBase(K("2026-10-27T15:20:00"), K("2026-10-27T16:04:00")), { date: "20261027", time: "1500" });
  assert.deepEqual(ncstBase(K("2026-10-27T15:50:00"), K("2026-10-27T16:04:00")), { date: "20261027", time: "1500" });
  assert.deepEqual(ncstBase(K("2026-10-27T16:05:00"), K("2026-10-27T16:20:00")), { date: "20261027", time: "1500" });
  assert.equal(ncstBase(K("2026-10-26T15:04:00"), K("2026-10-27T16:04:00")), null); // 25시간 전
  assert.deepEqual(ncstBase(K("2026-10-27T00:10:00"), K("2026-10-27T00:30:00")), { date: "20261026", time: "2300" });
  assert.equal(ncstBase(K("2026-10-27T19:00:00"), K("2026-10-27T16:04:00")), null); // 앞날
});

test("ncstBase: t가 now + 10분보다 뒤면 null, 10분 안은 허용", () => {
  const now = K("2026-10-27T16:04:00");
  assert.equal(ncstBase(K("2026-10-27T16:30:00"), now), null);
  assert.equal(ncstBase(K("2026-10-27T16:15:00"), now), null);
  assert.deepEqual(ncstBase(K("2026-10-27T16:14:00"), now), { date: "20261027", time: "1500" });
});

test("vilageBase: 발표 시각, 그제까지, 4일 앞까지", () => {
  const now = K("2026-10-27T16:04:00");
  assert.deepEqual(vilageBase(K("2026-10-25T06:00:00"), now), { date: "20261025", time: "0500" });
  assert.equal(vilageBase(K("2026-10-24T12:00:00"), now), null);
  assert.deepEqual(vilageBase(K("2026-10-28T13:00:00"), now), { date: "20261027", time: "1400" });
  assert.deepEqual(vilageBase(K("2026-10-27T01:00:00"), now), { date: "20261026", time: "2300" });
  assert.equal(vilageBase(K("2026-10-31T17:00:00"), now), null); // now + 4일 넘음
  // now − 10분 기준: 14:05에는 1400이 아직 없다
  assert.deepEqual(vilageBase(K("2026-10-27T14:30:00"), K("2026-10-27T14:05:00")), { date: "20261027", time: "1100" });
});

test("vilageBase: t − 1시간 기준이라 t의 정시 칸이 든 발표를 고른다", () => {
  const now = K("2026-10-27T18:00:00");
  // 14:30은 1400 발표(15시부터)에 없다 → 1100 발표(14시 칸 있음)
  assert.deepEqual(vilageBase(K("2026-10-27T14:30:00"), now), { date: "20261027", time: "1100" });
  // 15:00이면 1400 발표의 첫 칸(1500)
  assert.deepEqual(vilageBase(K("2026-10-27T15:00:00"), now), { date: "20261027", time: "1400" });
  // 02:30 → 01:30 기준 → 전날 2300
  assert.deepEqual(vilageBase(K("2026-10-27T02:30:00"), now), { date: "20261026", time: "2300" });
});

test("totalCount", () => {
  assert.equal(totalCount({ response: { body: { totalCount: 798 } } }), 798);
  assert.equal(totalCount({ response: { body: { totalCount: "12" } } }), 12);
  assert.equal(totalCount({}), null);
});

test("fcstAt: t 시각에 가장 가까운 예보 칸", () => {
  const row = (d: string, t: string, c: string, v: string) => ({ fcstDate: d, fcstTime: t, category: c, fcstValue: v });
  const rows = [
    row("20261027", "1500", "TMP", "18"), row("20261027", "1500", "SKY", "1"),
    row("20261027", "1600", "TMP", "17"), row("20261027", "1600", "SKY", "3"),
  ];
  assert.deepEqual(fcstAt(rows, K("2026-10-27T15:20:00")), { TMP: "18", SKY: "1" });
  assert.deepEqual(fcstAt(rows, K("2026-10-27T14:30:00")), { TMP: "18", SKY: "1" }); // 1400 칸 없음 → 1500
  assert.deepEqual(fcstAt(rows, K("2026-10-27T16:40:00")), { TMP: "17", SKY: "3" });
  assert.equal(fcstAt(rows, K("2026-10-27T20:00:00")), null); // 90분 넘게 떨어짐
});

test("sky·pty 코드", () => {
  assert.equal(skyText("1"), "맑음");
  assert.equal(skyText(3), "구름많음");
  assert.equal(skyText("4"), "흐림");
  assert.equal(skyText("2"), null);
  assert.equal(ptyText("0", true), null);
  assert.equal(ptyText("1", false), "비");
  assert.equal(ptyText("2", true), "비/눈");
  assert.equal(ptyText("3", false), "눈");
  assert.equal(ptyText("4", false), "소나기");
  assert.equal(ptyText("4", true), null);
  assert.equal(ptyText("5", true), "빗방울");
  assert.equal(ptyText("6", true), "빗방울눈날림");
  assert.equal(ptyText("7", true), "눈날림");
  assert.equal(ptyText("5", false), null);
});

test("hhmm", () => {
  assert.equal(hhmm("1736  "), "17:36");
  assert.equal(hhmm("0641"), "06:41");
  assert.equal(hhmm(""), null);
  assert.equal(hhmm("2561"), null);
});

test("homepageHref: a 태그·맨 URL·빈 값", () => {
  assert.equal(
    homepageHref('<a href="https://www.gn.go.kr/tour/" target="_blank" title="새창">https://www.gn.go.kr</a>'),
    "https://www.gn.go.kr/tour/",
  );
  assert.equal(homepageHref("https://www.coffeefestival.net/"), "https://www.coffeefestival.net/");
  assert.equal(homepageHref(""), null);
  assert.equal(homepageHref('<a href="javascript:alert(1)">x</a>'), null);
  assert.equal(homepageHref("www.example.com"), null);
  assert.equal(homepageHref(undefined), null);
  assert.equal(homepageHref('<a href="http://a.kr/?x=1&amp;y=2">a</a>'), "http://a.kr/?x=1&y=2");
});
