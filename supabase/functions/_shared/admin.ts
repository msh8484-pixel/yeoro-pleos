// service role 클라이언트 (Deno 전용, 테스트 없음). RLS를 넘어 trips·photos·Storage에 접근한다.
import { removeAllFiles } from "./purge.ts";
import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.117.3";

export const BUCKET = "trip-photos";

function secretKey(): string {
  const secrets = Deno.env.get("SUPABASE_SECRET_KEYS");
  if (secrets) {
    try {
      const parsed = JSON.parse(secrets);
      if (parsed && typeof parsed.default === "string" && parsed.default) return parsed.default;
    } catch {
      // 아래로 물러난다.
    }
  }
  const legacy = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (!legacy) throw new Error("service key missing");
  return legacy;
}

let client: SupabaseClient | null = null;

export function admin(): SupabaseClient {
  if (!client) {
    client = createClient(Deno.env.get("SUPABASE_URL")!, secretKey(), {
      auth: { persistSession: false, autoRefreshToken: false },
    });
  }
  return client;
}

/** trip-photos/<token>/ 아래 파일을 모두 지운다. */
export async function removeTripFiles(token: string): Promise<void> {
  await removeAllFiles(admin().storage.from(BUCKET), token);
}

/**
 * 사진 여러 장을 지운다: Storage 원본·썸네일을 먼저 지우고 그다음 행을 지운다.
 * Storage가 실패하면 행은 그대로 두고 던진다(다시 부르면 이어서 지워진다. 없는 파일 remove는 오류가 아니다).
 * 행 삭제가 실패해도 목록은 서명 URL이 없는 행을 빼고 보여 주므로 사진은 보이지 않는다.
 */
export async function removePhotos(rows: { id: string; path: string; thumb_path: string }[]): Promise<void> {
  if (rows.length === 0) return;
  const { error: rmError } = await admin().storage.from(BUCKET).remove(rows.flatMap((r) => [r.path, r.thumb_path]));
  if (rmError) throw rmError;
  const { error } = await admin().from("photos").delete().in("id", rows.map((r) => r.id));
  if (error) throw error;
}
