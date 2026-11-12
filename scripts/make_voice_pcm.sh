#!/bin/bash
# 디버그 음성 답 PCM 만들기 (macOS 호스트): say -v Yuna → 16kHz·16bit·mono WAV → 헤더 제거 → 앞 200ms·뒤 500ms 무음
# 결과: app/src/debug/assets/voice/{yes,no,maybe}.pcm (PcmVoiceIo가 dbg voice_pcm yes|no|maybe 때 STT에 넣는다)
set -eu
cd "$(dirname "$0")/.."
OUT=app/src/debug/assets/voice; mkdir -p "$OUT"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
make() {   # make <이름> <문장>
  say -v Yuna -o "$TMP/$1.aiff" "$2"
  afconvert -f WAVE -d LEI16@16000 -c 1 "$TMP/$1.aiff" "$TMP/$1.wav"
  python3 - "$TMP/$1.wav" "$OUT/$1.pcm" <<'PY'
import sys, wave
with wave.open(sys.argv[1]) as w:
    assert (w.getframerate(), w.getsampwidth(), w.getnchannels()) == (16000, 2, 1)
    pcm = w.readframes(w.getnframes())
ms = lambda n: b"\0" * (32 * n)   # 16kHz·16bit = 32바이트/ms
open(sys.argv[2], "wb").write(ms(200) + pcm + ms(500))
PY
  echo "$OUT/$1.pcm  $(wc -c < "$OUT/$1.pcm" | tr -d ' ')B  \"$2\""
}
make yes "응, 여행이야"
make no "아니, 그냥 가는 거야"
make maybe "음 글쎄"
