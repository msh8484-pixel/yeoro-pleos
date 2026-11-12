import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { exifDateTimeOriginal, exifToIso, fitWithin } from "./exif.js";

const FIXTURES = new URL("../../../test/fixtures/exif/", import.meta.url);

function fixture(name: string): ArrayBuffer {
  const b = readFileSync(new URL(name, FIXTURES));
  return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
}

/** Motorola(MM) 바이트 순서의 최소 JPEG: SOI + APP1(EXIF) + EOI. */
function motorolaJpeg(local: string, offset: string): ArrayBuffer {
  const enc = (s: string) => [...new TextEncoder().encode(s + "\0")];
  const u16 = (n: number) => [(n >> 8) & 0xff, n & 0xff];
  const u32 = (n: number) => [(n >>> 24) & 0xff, (n >> 16) & 0xff, (n >> 8) & 0xff, n & 0xff];
  const dto = enc(local); // 20바이트
  const ofs = enc(offset); // 7바이트
  const exifIfd = 26;
  const dataStart = exifIfd + 2 + 12 * 2 + 4;
  const tiff = [
    0x4d, 0x4d, ...u16(42), ...u32(8),
    ...u16(1), ...u16(0x8769), ...u16(4), ...u32(1), ...u32(exifIfd), ...u32(0),
    ...u16(2),
    ...u16(0x9003), ...u16(2), ...u32(dto.length), ...u32(dataStart),
    ...u16(0x9011), ...u16(2), ...u32(ofs.length), ...u32(dataStart + dto.length),
    ...u32(0),
    ...dto, ...ofs,
  ];
  const payload = [...new TextEncoder().encode("Exif"), 0, 0, ...tiff];
  const bytes = [0xff, 0xd8, 0xff, 0xe1, ...u16(payload.length + 2), ...payload, 0xff, 0xd9];
  return new Uint8Array(bytes).buffer;
}

test("EXIF 있음(오프셋 없음) → 기본 +09:00", () => {
  const e = exifDateTimeOriginal(fixture("exif.jpg"));
  assert.deepEqual(e, { local: "2026:10:31 13:30:00", offset: null });
  assert.equal(exifToIso(e), "2026-10-31T13:30:00+09:00");
});

test("오프셋 +00:00은 그대로 쓴다", () => {
  const e = exifDateTimeOriginal(fixture("exif_offset.jpg"));
  assert.deepEqual(e, { local: "2026:10:31 13:30:00", offset: "+00:00" });
  assert.equal(exifToIso(e), "2026-10-31T13:30:00+00:00");
});

test("EXIF 없음 → null", () => {
  assert.equal(exifDateTimeOriginal(fixture("no_exif.jpg")), null);
  assert.equal(exifToIso(null), null);
});

test("잘린 버퍼·JPEG 아님 → null(예외 없음)", () => {
  const full = fixture("exif.jpg");
  for (const n of [0, 1, 2, 4, 10, 20, 40, 60, 90]) {
    assert.equal(exifDateTimeOriginal(full.slice(0, n)), null, `len ${n}`);
  }
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).buffer;
  assert.equal(exifDateTimeOriginal(png), null);
});

test("Motorola(MM) 바이트 순서", () => {
  const e = exifDateTimeOriginal(motorolaJpeg("2026:10:31 08:50:12", "+09:00"));
  assert.deepEqual(e, { local: "2026:10:31 08:50:12", offset: "+09:00" });
  assert.equal(exifToIso(e), "2026-10-31T08:50:12+09:00");
});

test("exifToIso: 형식이 틀리면 null, 기본 오프셋 바꾸기", () => {
  assert.equal(exifToIso({ local: "0000:00:00 00:00:00", offset: null }), null);
  assert.equal(exifToIso({ local: "2026-10-31 13:30:00", offset: null }), null);
  assert.equal(exifToIso({ local: "2026:10:31 13:30:00", offset: null }, "-05:00"), "2026-10-31T13:30:00-05:00");
});

test("fitWithin: 긴 변을 max 이하로, 비율 유지", () => {
  assert.deepEqual(fitWithin(4032, 3024, 2048), { w: 2048, h: 1536 });
  assert.deepEqual(fitWithin(3024, 4032, 720), { w: 540, h: 720 });
  assert.deepEqual(fitWithin(640, 480, 2048), { w: 640, h: 480 });
  assert.deepEqual(fitWithin(2048, 100, 2048), { w: 2048, h: 100 });
});

test("exifToIso: 없는 날짜(왕복 검사)·이상한 오프셋 → null 또는 기본값", () => {
  assert.equal(exifToIso({ local: "2026:02:31 10:00:00", offset: null }), null);
  assert.equal(exifToIso({ local: "2026:04:31 10:00:00", offset: null }), null);
  assert.equal(exifToIso({ local: "2027:02:29 10:00:00", offset: null }), null); // 평년
  assert.equal(exifToIso({ local: "2028:02:29 10:00:00", offset: null }), "2028-02-29T10:00:00+09:00"); // 윤년
  // EXIF 오프셋이 범위를 벗어나면 기본 오프셋으로
  assert.equal(exifToIso({ local: "2026:10:31 13:30:00", offset: "+99:00" }), "2026-10-31T13:30:00+09:00");
  assert.equal(exifToIso({ local: "2026:10:31 13:30:00", offset: "+09:75" }), "2026-10-31T13:30:00+09:00");
  assert.equal(exifToIso({ local: "2026:10:31 13:30:00", offset: "+14:00" }), "2026-10-31T13:30:00+14:00");
  // 기본 오프셋까지 이상하면 null
  assert.equal(exifToIso({ local: "2026:10:31 13:30:00", offset: null }, "+25:00"), null);
});

test("없는 날짜가 박힌 JPEG도 예외 없이 null(서버는 none으로 둔다)", () => {
  const e = exifDateTimeOriginal(motorolaJpeg("2026:02:31 08:50:12", "+09:00"));
  assert.deepEqual(e, { local: "2026:02:31 08:50:12", offset: "+09:00" });
  assert.equal(exifToIso(e), null);
});
