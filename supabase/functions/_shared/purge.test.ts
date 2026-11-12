import { test } from "node:test";
import assert from "node:assert/strict";
import { removeAllFiles, type StorageLike } from "./purge.ts";

function fake(files: string[], opts: { placeholders?: string[]; removeNothing?: boolean } = {}) {
  const calls = { list: 0, remove: 0 };
  const live = new Set(files);
  const storage: StorageLike = {
    async list() {
      calls.list++;
      return {
        data: [
          ...[...live].map((name) => ({ id: "id-" + name, name })),
          ...(opts.placeholders ?? []).map((name) => ({ id: null, name })),
        ],
        error: null,
      };
    },
    async remove(paths) {
      calls.remove++;
      if (opts.removeNothing) return { data: [], error: null };
      const out = paths.filter((p) => live.delete(p.split("/")[1]));
      return { data: out.map((name) => ({ name })), error: null };
    },
  };
  return { storage, calls, live };
}

test("파일을 지우고 끝난다", async () => {
  const f = fake(["a.jpg", "b.jpg"]);
  await removeAllFiles(f.storage, "tok");
  assert.equal(f.live.size, 0);
  assert.equal(f.calls.remove, 1);
});

test("폴더 자리표시자만 있으면 remove를 부르지 않고 끝난다", async () => {
  const f = fake([], { placeholders: ["sub"] });
  await removeAllFiles(f.storage, "tok");
  assert.equal(f.calls.remove, 0);
  assert.equal(f.calls.list, 1);
});

test("remove가 아무것도 못 지우면 멈춘다(무한 루프 없음)", async () => {
  const f = fake(["a.jpg"], { removeNothing: true });
  await removeAllFiles(f.storage, "tok");
  assert.equal(f.calls.remove, 1);
});

test("반복 횟수 상한", async () => {
  let n = 0;
  const storage: StorageLike = {
    async list() { return { data: [{ id: "x", name: "f" + n++ }], error: null }; },
    async remove(p) { return { data: p.map((name) => ({ name })), error: null }; },
  };
  await removeAllFiles(storage, "tok", 5);
  assert.equal(n, 5);
});

test("list 오류는 던진다", async () => {
  const storage: StorageLike = {
    async list() { return { data: null, error: new Error("boom") }; },
    async remove() { return { data: [], error: null }; },
  };
  await assert.rejects(removeAllFiles(storage, "tok"));
});
