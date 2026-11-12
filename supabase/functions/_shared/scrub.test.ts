import { test } from "node:test";
import assert from "node:assert/strict";
import { scrub } from "./scrub.ts";

// 가짜 키: 인코딩하면 모양이 바뀌는 문자(+, /, =, 공백, ~, !)를 넣었다.
const FAKE = "Ab+cD/ef==Gh ij~kl!mn";

test("scrub: 키 원문", () => {
  const out = scrub(`fetch failed for key ${FAKE} at host`, FAKE);
  assert.equal(out, "fetch failed for key [key] at host");
});

test("scrub: URLSearchParams 인코딩 형태", () => {
  const qs = new URLSearchParams({ serviceKey: FAKE, nx: "93" }).toString();
  const enc = new URLSearchParams({ k: FAKE }).toString().slice(2);
  assert.notEqual(enc, FAKE);
  // serviceKey= 뒤
  assert.equal(scrub(`GET https://x/y?${qs}`, FAKE), "GET https://x/y?serviceKey=[key]&nx=93");
  // 다른 이름 뒤에 있어도
  const out = scrub(`echo k=${enc}&z=1`, FAKE);
  assert.ok(!out.includes(enc));
  assert.equal(out, "echo k=[key]&z=1");
});

test("scrub: encodeURIComponent 형태", () => {
  const enc = encodeURIComponent(FAKE);
  assert.notEqual(enc, new URLSearchParams({ k: FAKE }).toString().slice(2)); // 공백·~·! 처리가 다르다
  const out = scrub(`retry with ${enc}.`, FAKE);
  assert.equal(out, "retry with [key].");
});

test("scrub: 키 없이도 serviceKey= 값은 가린다", () => {
  assert.equal(scrub("u?serviceKey=abc%2Bdef&x=1", null), "u?serviceKey=[key]&x=1");
  assert.equal(scrub("no secrets here", FAKE), "no secrets here");
});
