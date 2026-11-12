// Storage 폴더 비우기 (순수 로직, 저장소 클라이언트를 주입받는다).
export type StorageLike = {
  list(
    path: string,
    opts: { limit: number },
  ): Promise<{ data: { id?: string | null; name: string }[] | null; error: unknown }>;
  remove(paths: string[]): Promise<{ data: unknown[] | null; error: unknown }>;
};

/**
 * <token>/ 아래 실제 파일(id 있음)을 지운다. 폴더 자리표시자(id 없음)는 건너뛴다.
 * remove가 요청보다 적게 지우면(0 포함) 멈춘다. 반복은 maxIter번까지.
 */
export async function removeAllFiles(storage: StorageLike, token: string, maxIter = 50): Promise<void> {
  for (let i = 0; i < maxIter; i++) {
    const { data, error } = await storage.list(token, { limit: 1000 });
    if (error) throw error;
    const names = (data ?? []).filter((f) => f.id && f.name).map((f) => `${token}/${f.name}`);
    if (names.length === 0) return;
    const { data: removed, error: rmError } = await storage.remove(names);
    if (rmError) throw rmError;
    if (!removed || removed.length < names.length) return;
  }
}
