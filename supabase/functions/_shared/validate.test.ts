import { test } from "node:test";
import assert from "node:assert/strict";
import {
  base64Url,
  constantTimeEqual,
  isDeleteKey,
  isDeviceSecret,
  isPhotoId,
  isToken,
  newDeleteKey,
  parseIsoInstant,
  parsePhotoIds,
  sha256Hex,
} from "./validate.ts";

test("isToken: 22자 base64url만 통과", () => {
  assert.equal(isToken("abcdefghijklmnopqrstuv"), true);
  assert.equal(isToken("ABCDEFGHIJ0123456789-_"), true);
  assert.equal(isToken("abcdefghijklmnopqrstu"), false); // 21자
  assert.equal(isToken("abcdefghijklmnopqrstuvw"), false); // 23자
  assert.equal(isToken("abcdefghijklmnopqrstu+"), false); // '+'
  assert.equal(isToken(undefined), false);
  assert.equal(isToken(12345), false);
});

test("isDeviceSecret: 43자 base64url(32바이트)", () => {
  const ok = "A".repeat(43);
  assert.equal(isDeviceSecret(ok), true);
  assert.equal(isDeviceSecret("a-_" + "b".repeat(40)), true);
  assert.equal(isDeviceSecret("A".repeat(42)), false);
  assert.equal(isDeviceSecret("A".repeat(44)), false);
  assert.equal(isDeviceSecret("A".repeat(42) + "="), false);
  assert.equal(isDeviceSecret(null), false);
});

test("sha256Hex", async () => {
  assert.equal(
    await sha256Hex("abc"),
    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
  );
});

test("parseIsoInstant: 오프셋 필수", () => {
  const d = parseIsoInstant("2026-10-31T08:52:00+09:00");
  assert.ok(d instanceof Date);
  assert.equal(d!.toISOString(), "2026-10-30T23:52:00.000Z");
  assert.equal(parseIsoInstant("2026-10-30T23:52:00Z")!.toISOString(), "2026-10-30T23:52:00.000Z");
  assert.equal(parseIsoInstant("2026-10-31T08:52:00"), null);
  assert.equal(parseIsoInstant("2026-10-31"), null);
  assert.equal(parseIsoInstant("2026-13-31T08:52:00+09:00"), null);
  assert.equal(parseIsoInstant("not a date"), null);
  assert.equal(parseIsoInstant(undefined), null);
});

test("parseIsoInstant: 없는 날짜 거부", () => {
  assert.equal(parseIsoInstant("2026-02-31T08:52:00+09:00"), null);
  assert.equal(parseIsoInstant("2026-04-31T08:52:00+09:00"), null);
  assert.equal(parseIsoInstant("2027-02-29T08:52:00Z"), null);
  assert.ok(parseIsoInstant("2028-02-29T08:52:00Z"));
});

test("parseIsoInstant: 범위를 벗어난 오프셋 거부", () => {
  assert.equal(parseIsoInstant("2026-10-31T08:52:00+99:00"), null);
  assert.equal(parseIsoInstant("2026-10-31T08:52:00+09:60"), null);
  assert.equal(parseIsoInstant("2026-10-31T08:52:00+14:01"), null);
  assert.ok(parseIsoInstant("2026-10-31T08:52:00+14:00"));
  assert.ok(parseIsoInstant("2026-10-31T08:52:00-09:30"));
});

test("newDeleteKey: 22자 base64url, 매번 다르다", () => {
  const seen = new Set<string>();
  for (let i = 0; i < 200; i++) {
    const k = newDeleteKey();
    assert.equal(isDeleteKey(k), true, k);
    seen.add(k);
  }
  assert.equal(seen.size, 200);
});

test("base64Url: 패딩 없음, +/ 대신 -_", () => {
  assert.equal(base64Url(new Uint8Array([0xfb, 0xff, 0xbf])), "-_-_");
  assert.equal(base64Url(new Uint8Array(16)), "AAAAAAAAAAAAAAAAAAAAAA");
  assert.equal(base64Url(new Uint8Array([1])), "AQ");
});

test("isDeleteKey", () => {
  assert.equal(isDeleteKey("A".repeat(22)), true);
  assert.equal(isDeleteKey("A".repeat(21)), false);
  assert.equal(isDeleteKey("A".repeat(21) + "="), false);
  assert.equal(isDeleteKey("A".repeat(21) + "+"), false);
  assert.equal(isDeleteKey(undefined), false);
});

test("isPhotoId: 소문자 uuid만", () => {
  assert.equal(isPhotoId("0b6f3c2e-5a1d-4c8e-9f00-1234567890ab"), true);
  assert.equal(isPhotoId("0B6F3C2E-5A1D-4C8E-9F00-1234567890AB"), false);
  assert.equal(isPhotoId("0b6f3c2e5a1d4c8e9f001234567890ab"), false);
  assert.equal(isPhotoId("../x"), false);
  assert.equal(isPhotoId(null), false);
});

test("parsePhotoIds: id·ids 합치기, 중복 제거, 50개 상한", () => {
  const a = "0b6f3c2e-5a1d-4c8e-9f00-1234567890ab";
  const b = "1b6f3c2e-5a1d-4c8e-9f00-1234567890ab";
  assert.deepEqual(parsePhotoIds(a, null), [a]);
  assert.deepEqual(parsePhotoIds(null, `${a},${b}`), [a, b]);
  assert.deepEqual(parsePhotoIds(a, ` ${b} ,${a},`), [a, b]); // 공백·빈 칸·중복
  assert.deepEqual(parsePhotoIds(a.toUpperCase(), null), [a]); // 대문자 → 소문자
  assert.equal(parsePhotoIds(null, null), null);
  assert.equal(parsePhotoIds("", ","), null);
  assert.equal(parsePhotoIds(a, "nope"), null);
  const many = Array.from({ length: 51 }, (_, i) => `00000000-0000-4000-8000-${String(i).padStart(12, "0")}`);
  assert.equal(parsePhotoIds(null, many.join(",")), null);
  assert.equal(parsePhotoIds(null, many.slice(0, 50).join(","))?.length, 50);
  assert.deepEqual(parsePhotoIds(null, [...many.slice(0, 50), many[0]].join(","))?.length, 50); // 중복은 한 번만 센다
});

test("constantTimeEqual", () => {
  assert.equal(constantTimeEqual("abc", "abc"), true);
  assert.equal(constantTimeEqual("abc", "abd"), false);
  assert.equal(constantTimeEqual("abc", "ab"), false);
  assert.equal(constantTimeEqual("", ""), true);
});
