#!/bin/bash
# 시연 사진 4장을 가족 휴대폰처럼 업로드 함수에 올린다 (업로드 페이지 대신 curl).
# 사용: scripts/demo_upload.sh <upload_url> [YYYY-MM-DD]
#   upload_url: 도착 카드 QR 주소 (디버그 로그 upload_url=…, 예 https://<ref>.supabase.co/functions/v1/upload?t=<토큰>)
#   날짜: 사진 촬영일. 빼면 서버의 여행 출발일(info started_on)을 쓴다.
# 사진마다 exif_stamp.py로 그날 08:50(출발)·10:05(휴게소)·13:30·14:10(경포해변) 촬영 시각(+09:00)을 넣고,
# sips -Z 720으로 썸네일을 만들어 POST한다. taken_at은 보내지 않는다(서버가 EXIF를 읽는다). 201이 아니면 실패.
# 사진: scripts/demo_photos/0[1-4]_*.jpg (CC0, 출처 scripts/demo_photos/CREDITS.md)
set -euo pipefail
cd "$(dirname "$0")"
URL="${1:?사용: demo_upload.sh <upload_url> [YYYY-MM-DD]}"
DAY="${2:-}"
if [ -z "$DAY" ]; then
  DAY="$(curl -s "$URL&info=1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["started_on"])')" \
    || { echo "FAIL 여행 정보(info)를 읽지 못함"; exit 1; }
fi
[[ "$DAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || { echo "FAIL 날짜 형식: $DAY"; exit 1; }
EXIF_DAY="${DAY//-/:}"
TIMES=("08:50" "10:05" "13:30" "14:10")
PHOTOS=(demo_photos/0[1-4]_*.jpg)
[ "${#PHOTOS[@]}" -ge 4 ] || { echo "FAIL demo_photos에 사진이 4장 없음"; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
for i in 0 1 2 3; do
  src="${PHOTOS[$i]}"; t="${TIMES[$i]}"; name="$(basename "$src")"
  # 긴 변 2048 초과면 줄인 사본을 쓴다
  long=$(sips -g pixelWidth -g pixelHeight "$src" | awk '/pixel/{print $2}' | sort -n | tail -1)
  if [ "$long" -gt 2048 ]; then sips -Z 2048 "$src" --out "$WORK/src_$i.jpg" >/dev/null; src="$WORK/src_$i.jpg"; fi
  python3 exif_stamp.py "$src" "$WORK/p_$i.jpg" "$EXIF_DAY $t:00" +09:00 >/dev/null
  sips -Z 720 "$WORK/p_$i.jpg" --out "$WORK/t_$i.jpg" >/dev/null
  w=$(sips -g pixelWidth "$WORK/p_$i.jpg" | awk '/pixelWidth/{print $2}')
  h=$(sips -g pixelHeight "$WORK/p_$i.jpg" | awk '/pixelHeight/{print $2}')
  code=$(curl -s -o "$WORK/body" -w '%{http_code}' -X POST "$URL" \
    -F "photo=@$WORK/p_$i.jpg;type=image/jpeg" -F "thumb=@$WORK/t_$i.jpg;type=image/jpeg" -F "w=$w" -F "h=$h")
  if [ "$code" != 201 ]; then echo "FAIL $name ($DAY $t): $code $(cat "$WORK/body")"; exit 1; fi
  echo "OK   $name → $DAY $t ($code, ${w}x${h})"
done
echo "demo_upload: 4장 올림"
