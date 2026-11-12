// 로그 문자열에서 data.go.kr 서비스키를 가린다. 순수 모듈(키는 인자로 받는다).

/** `serviceKey=…` 값, 키 원문, encodeURIComponent·URLSearchParams 인코딩 형태를 모두 "[key]"로. */
export function scrub(msg: string, key?: string | null): string {
  let out = msg.replace(/serviceKey=[^&\s)"']+/gi, "serviceKey=[key]");
  if (key) {
    const forms = new Set([key, encodeURIComponent(key), new URLSearchParams({ k: key }).toString().slice(2)]);
    // 긴 형태부터 바꿔야 짧은 형태가 긴 형태 일부를 먼저 깨뜨리지 않는다.
    for (const f of [...forms].sort((a, b) => b.length - a.length)) {
      if (f) out = out.split(f).join("[key]");
    }
  }
  return out;
}
