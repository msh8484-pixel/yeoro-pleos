#!/usr/bin/env bash
# 여로 백엔드 스모크 테스트 (원격 Supabase 프로젝트 대상).
# - keys.env 를 읽지 않는다. publishable 키는 local.properties 의 yeoro.supabase.key 에서 읽고 찍지 않는다.
# - 토큰·기기 비밀은 매번 openssl rand 로 새로 만든다.
# 사용: scripts/backend_smoke.sh   (실패하면 0이 아닌 값으로 끝난다)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/local.properties"
prop() { grep -E "^$1=" "$PROPS" 2>/dev/null | head -n1 | cut -d= -f2- || true; }

BASE="$(prop yeoro.supabase.url)"
BASE="${BASE:-https://dghpdvpkgvamsaidndqa.supabase.co}"
KEY="$(prop yeoro.supabase.key)"
if [ -z "$KEY" ]; then
  echo "FAIL local.properties 에 yeoro.supabase.key 가 없다"
  exit 1
fi
FN="$BASE/functions/v1"

b64url() { openssl rand -base64 "$1" | tr '+/' '-_' | tr -d '=\n'; }
TOKEN="$(b64url 16)"     # 22자
DEVICE="$(b64url 32)"    # 43자
OTHER="$(b64url 32)"
STARTED="2026-10-31T08:52:00+09:00"

FAILED=0
BODY_FILE="$(mktemp)"
PNG_FILE="$(mktemp)"
# 종료 때 테스트 여행을 best-effort로 지운다(이미 지워졌어도 204, 실패해도 무시).
cleanup() {
  curl -s -o /dev/null -X DELETE "$FN/trip" -H 'content-type: application/json' -H "apikey: $KEY" \
    --data "{\"token\":\"$TOKEN\",\"device\":\"$DEVICE\"}" || true
  rm -f "$BODY_FILE" "$PNG_FILE"
}
trap cleanup EXIT
# call METHOD PATH BODY [with-key=1]  → HTTP 상태를 출력, 본문은 $BODY_FILE
call() {
  local method="$1" path="$2" body="$3" withkey="${4:-1}" out
  local args=(-s -o - -w '\n%{http_code}' -X "$method" "$FN/$path" -H 'content-type: application/json' --data "$body")
  if [ "$withkey" = 1 ]; then args+=(-H "apikey: $KEY"); fi
  out="$(curl "${args[@]}")"
  printf '%s' "$out" | sed '$d' > "$BODY_FILE"
  printf '%s' "$out" | tail -n1
}
expect() { # 설명 기대상태 실제상태
  if [ "$2" = "$3" ]; then
    echo "OK   $1 ($3)"
  else
    echo "FAIL $1: 기대 $2, 실제 $3 $(cat "$BODY_FILE")"
    FAILED=1
  fi
}

REG="{\"token\":\"$TOKEN\",\"device\":\"$DEVICE\",\"started_at\":\"$STARTED\",\"dest_name\":\"강릉 경포해변\"}"
REG_OTHER="{\"token\":\"$TOKEN\",\"device\":\"$OTHER\",\"started_at\":\"$STARTED\",\"dest_name\":null}"
REG_BAD="{\"token\":\"short+\",\"device\":\"$DEVICE\",\"started_at\":\"$STARTED\",\"dest_name\":null}"
DEL_OTHER="{\"token\":\"$TOKEN\",\"device\":\"$OTHER\"}"
DEL="{\"token\":\"$TOKEN\",\"device\":\"$DEVICE\"}"

step() { # 설명 기대상태 METHOD PATH BODY [with-key]
  local status
  status="$(call "$3" "$4" "$5" "${6:-1}")"
  expect "$1" "$2" "$status"
}

step "trip 등록"             201 POST   trip "$REG"
step "trip 재등록(같은 기기)" 200 POST   trip "$REG"
step "trip 다른 기기 등록"    409 POST   trip "$REG_OTHER"
step "trip 잘못된 토큰"       400 POST   trip "$REG_BAD"
step "trip 키 없이"           401 POST   trip "$REG" 0

# --- 사진 올리기 (upload: 키 없음, photos: 키 + 기기 비밀) ---
FIX="$ROOT/test/fixtures/exif"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR' > "$PNG_FILE"
# raw 설명 기대상태 curl인자…  → 본문은 $BODY_FILE
raw() {
  local desc="$1" want="$2" out
  shift 2
  out="$(curl -s -o "$BODY_FILE" -w '%{http_code}' "$@")"
  expect "$desc" "$want" "$out"
}
jget() { python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); exec("v=d"+sys.argv[2]); print(v)' "$BODY_FILE" "$1" 2>/dev/null || true; }
check() { # 설명 기대값 실제값
  if [ "$2" = "$3" ]; then echo "OK   $1 ($3)"; else echo "FAIL $1: 기대 $2, 실제 $3"; FAILED=1; fi
}

LOC="$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' "$FN/upload?t=$TOKEN")"
check "upload 페이지로 302" "302 https://msh8484-pixel.github.io/yeoro-upload/?t=$TOKEN" "$LOC"
raw "upload info" 200 "$FN/upload?t=$TOKEN&info=1"
check "info started_on·count·max" "2026-10-31 0 200" "$(jget "['started_on']") $(jget "['count']") $(jget "['max']")"
raw "upload 사진(서버가 EXIF 읽음)" 201 -X POST "$FN/upload?t=$TOKEN" \
  -F "photo=@$FIX/exif.jpg;type=image/jpeg" -F "thumb=@$FIX/exif.jpg;type=image/jpeg" -F w=64 -F h=48
check "업로드 뒤 count" "1" "$(jget "['count']")"
raw "upload PNG 거부" 415 -X POST "$FN/upload?t=$TOKEN" \
  -F "photo=@$PNG_FILE;type=image/png" -F "thumb=@$FIX/exif.jpg;type=image/jpeg" -F w=64 -F h=48
raw "photos 기기 비밀 없이" 403 "$FN/photos?t=$TOKEN" -H "apikey: $KEY"
raw "photos 다른 기기" 403 "$FN/photos?t=$TOKEN" -H "apikey: $KEY" -H "x-yeoro-device: $OTHER"
raw "photos 목록" 200 "$FN/photos?t=$TOKEN" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE"
check "목록 1장·exif·촬영 시각" "1 exif 2026-10-31T04:30:00" \
  "$(jget "['photos'].__len__()") $(jget "['photos'][0]['taken_at_source']") $(jget "['photos'][0]['taken_at'][:19]")"
PHOTO_URL="$(jget "['photos'][0]['url']")"
THUMB_URL="$(jget "['photos'][0]['thumb_url']")"
for u in "$PHOTO_URL" "$THUMB_URL"; do
  check "서명 URL이 JPEG" "ffd8ff" "$(curl -s "$u" | head -c 3 | xxd -p)"
done

# --- 사진 지우기: 올린 사람(지우기 열쇠) · 차(기기 비밀) ---
# 첫 사진(P1)은 남겨 두고 P2~P6을 올려 지운다. 지운 뒤 목록에는 P1만 남고, 지운 사진의 서명 URL은 더 이상 열리지 않아야 한다.
UPLOAD_ID=""; UPLOAD_KEY=""
up() { # 설명 → UPLOAD_ID, UPLOAD_KEY
  raw "$1" 201 -X POST "$FN/upload?t=$TOKEN" \
    -F "photo=@$FIX/exif.jpg;type=image/jpeg" -F "thumb=@$FIX/exif.jpg;type=image/jpeg" -F w=64 -F h=48
  UPLOAD_ID="$(jget "['id']")"; UPLOAD_KEY="$(jget "['delete_key']")"
}
P1="$(curl -s "$FN/photos?t=$TOKEN" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE" | python3 -c 'import json,sys; print(json.load(sys.stdin)["photos"][0]["id"])')"
up "지우기용 P2 올리기"; P2="$UPLOAD_ID"; K2="$UPLOAD_KEY"
check "201에 delete_key(22자 base64url)" "yes" "$(printf '%s' "$K2" | grep -qE '^[A-Za-z0-9_-]{22}$' && echo yes || echo no)"
up "지우기용 P3 올리기"; P3="$UPLOAD_ID"; K3="$UPLOAD_KEY"
up "지우기용 P4 올리기"; P4="$UPLOAD_ID"
up "지우기용 P5 올리기"; P5="$UPLOAD_ID"
up "지우기용 P6 올리기"; P6="$UPLOAD_ID"
check "열쇠는 사진마다 다르다" "yes" "$([ "$K2" != "$K3" ] && echo yes || echo no)"
raw "photos 목록(지우기 전 6장)" 200 "$FN/photos?t=$TOKEN" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE"
check "지우기 전 6장" "6" "$(jget "['photos'].__len__()")"
GONE_URLS="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); keep=sys.argv[2]; print("\n".join(u for p in d["photos"] if p["id"]!=keep for u in (p["url"],p["thumb_url"])))' "$BODY_FILE" "$P1")"
WRONG_KEY="$(b64url 16)"
NO_SUCH="00000000-0000-4000-8000-000000000000"
raw "올린 사람 삭제: 틀린 열쇠" 403 -X DELETE "$FN/upload?t=$TOKEN&id=$P2" -H "x-yeoro-delete: $WRONG_KEY"
raw "올린 사람 삭제: 열쇠 없이" 403 -X DELETE "$FN/upload?t=$TOKEN&id=$P2"
raw "올린 사람 삭제: 다른 사진의 열쇠" 403 -X DELETE "$FN/upload?t=$TOKEN&id=$P2" -H "x-yeoro-delete: $K3"
raw "올린 사람 삭제: 없는 사진" 404 -X DELETE "$FN/upload?t=$TOKEN&id=$NO_SUCH" -H "x-yeoro-delete: $K2"
raw "올린 사람 삭제: 형식 틀린 id" 404 -X DELETE "$FN/upload?t=$TOKEN&id=nope" -H "x-yeoro-delete: $K2"
raw "올린 사람 삭제: 없는 여행" 404 -X DELETE "$FN/upload?t=$(b64url 16)&id=$P2" -H "x-yeoro-delete: $K2"
raw "올린 사람 삭제: 맞는 열쇠(헤더)" 204 -X DELETE "$FN/upload?t=$TOKEN&id=$P2" -H "x-yeoro-delete: $K2"
raw "올린 사람 삭제: 다시(이미 없음)" 404 -X DELETE "$FN/upload?t=$TOKEN&id=$P2" -H "x-yeoro-delete: $K2"
raw "올린 사람 삭제: 맞는 열쇠(JSON 본문)" 204 -X DELETE "$FN/upload?t=$TOKEN&id=$P3" \
  -H 'content-type: application/json' --data "{\"key\":\"$K3\"}"
raw "차 삭제: 키 없이" 401 -X DELETE "$FN/photos?t=$TOKEN&id=$P4" -H "x-yeoro-device: $DEVICE"
raw "차 삭제: 다른 기기" 403 -X DELETE "$FN/photos?t=$TOKEN&id=$P4" -H "apikey: $KEY" -H "x-yeoro-device: $OTHER"
raw "차 삭제: 형식 틀린 ids" 400 -X DELETE "$FN/photos?t=$TOKEN&ids=$P4,nope" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE"
raw "차 삭제: 없는 사진" 404 -X DELETE "$FN/photos?t=$TOKEN&id=$NO_SUCH" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE"
raw "차 삭제: 다른 사람이 올린 사진 한 장" 204 -X DELETE "$FN/photos?t=$TOKEN&id=$P4" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE"
raw "차 삭제: 여러 장(없는 id 섞임)" 204 -X DELETE "$FN/photos?t=$TOKEN&ids=$P5,$P6,$NO_SUCH" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE"
raw "photos 목록(지운 뒤)" 200 "$FN/photos?t=$TOKEN" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE"
check "지운 뒤 목록은 P1 한 장" "1 $P1" "$(jget "['photos'].__len__()") $(jget "['photos'][0]['id']")"
raw "지운 뒤 info" 200 "$FN/upload?t=$TOKEN&info=1"
check "지운 뒤 count" "1" "$(jget "['count']")"
GONE_OK=0; GONE_N=0
while IFS= read -r u; do
  [ -z "$u" ] && continue
  GONE_N=$((GONE_N + 1))
  code="$(curl -s -o /dev/null -w '%{http_code}' "$u")"
  [ "$code" != 200 ] && GONE_OK=$((GONE_OK + 1))
done <<< "$GONE_URLS"
check "지운 사진의 Storage 파일(원본·썸네일)이 없다" "10/10" "$GONE_OK/$GONE_N"

# --- 제한 경쟁: 31장을 8개씩 동시에 보낸다. 분당 30장(이미 1장 있음)을 넘는 만큼은 429여야 하고,
#     저장된 수는 동시 요청이 몰려도 30을 넘지 않아야 한다(DB 트리거 photos_enforce_limits).
RACE_DIR="$(mktemp -d)"
seq 1 31 | xargs -P 8 -I{} curl -s -o /dev/null -w '%{http_code}\n' -X POST "$FN/upload?t=$TOKEN" \
  -F "photo=@$FIX/exif.jpg;type=image/jpeg" -F "thumb=@$FIX/exif.jpg;type=image/jpeg" -F w=64 -F h=48 \
  > "$RACE_DIR/codes"
N201="$(grep -c '^201$' "$RACE_DIR/codes" || true)"
N429="$(grep -c '^429$' "$RACE_DIR/codes" || true)"
NOTHER="$(grep -vcE '^(201|429)$' "$RACE_DIR/codes" || true)"
rm -rf "$RACE_DIR"
check "동시 31장: 429가 하나 이상" "yes" "$([ "$N429" -ge 1 ] && echo yes || echo "no(201=$N201 429=$N429)")"
check "동시 31장: 201·429 말고 다른 응답 없음" "0" "$NOTHER"
raw "동시 업로드 뒤 info" 200 "$FN/upload?t=$TOKEN&info=1"
STORED="$(jget "['count']")"
check "저장된 수 = 1 + 201 수, 30 이하" "$((1 + N201)) yes" "$STORED $([ "${STORED:-999}" -le 30 ] && echo yes || echo no)"

step "trip 다른 기기로 삭제"  403 DELETE trip "$DEL_OTHER"
step "trip 삭제"             204 DELETE trip "$DEL"
raw "삭제 뒤 info" 404 "$FN/upload?t=$TOKEN&info=1"
raw "삭제 뒤 photos 목록" 404 "$FN/photos?t=$TOKEN" -H "apikey: $KEY" -H "x-yeoro-device: $DEVICE"
raw "삭제 뒤 업로드 거부" 404 -X POST "$FN/upload?t=$TOKEN" \
  -F "photo=@$FIX/exif.jpg;type=image/jpeg" -F "thumb=@$FIX/exif.jpg;type=image/jpeg" -F w=64 -F h=48
# (앞에서 한 번 연 P1의 서명 URL은 Storage CDN 캐시 때문에 만료 전까지 열릴 수 있어 여기서 보지 않는다. 아래 직접 세기가 기준)
# 서버에 남은 것: SUPABASE_ACCESS_TOKEN(Management API)이 있으면 이 토큰의 행·Storage 객체를 직접 센다(값은 찍지 않는다).
if [ -n "${SUPABASE_ACCESS_TOKEN:-}" ]; then
  REF="$(printf '%s' "$BASE" | sed -E 's#https://([a-z0-9]+)\.supabase\.co.*#\1#')"
  LEFT="$(curl -s -X POST "https://api.supabase.com/v1/projects/$REF/database/query" \
    -H "Authorization: Bearer $SUPABASE_ACCESS_TOKEN" -H 'content-type: application/json' \
    --data "{\"query\":\"select (select count(*) from public.trips where token='$TOKEN') + (select count(*) from public.photos where token='$TOKEN') + (select count(*) from storage.objects where bucket_id='trip-photos' and name like '$TOKEN/%') as n\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["n"])' 2>/dev/null || echo "?")"
  check "삭제 뒤 이 여행의 trips·photos 행·Storage 객체 0개" "0" "$LEFT"
else
  echo "SKIP 서버 행 직접 세기 (SUPABASE_ACCESS_TOKEN 없음: 위의 404·서명 URL 확인으로 갈음)"
fi

# --- 공공데이터 (outside: 키 필요, 서버가 DATA_GO_KR_KEY로 data.go.kr 호출) ---
NOW_MS="$(date +%s)000"
GP="lat=37.8055&lon=128.9087"   # 강릉 경포해변
# 외부 API 일시 장애(timeout·http_5xx)는 경고로만 보고, 그 밖의 오류나 모양 틀림은 실패.
# assess 이름 → "ok" 또는 실패 이유. 경고는 stderr로 WARN.
assess() {
  python3 - "$BODY_FILE" "$1" <<'PY'
import json, re, sys
d = json.load(open(sys.argv[1])); which = sys.argv[2]
errs = d.get("errors", [])
transient = lambda e: re.fullmatch(r"[a-z]+:(timeout|http_5\d\d|no_region\((timeout|http_5\d\d)\))", e) is not None
tolerated = [e for e in errs if transient(e)]
for e in tolerated: print("WARN 외부 API 일시 오류(허용): " + e, file=sys.stderr)
down = {e.split(":")[0] for e in tolerated}
bad = []
def need(part, ok, desc):
    if part in down and d.get(part) is None: return
    if not ok: bad.append(desc)
# TourAPI는 데이터가 자주 바뀐다(2026-11 강릉·종로 관광지 대량 누락 확인). 종류는 나왔는데 이름이 없으면 외부 데이터 변화로 보고 경고만.
def need_place(ok, desc):
    if d.get("place") and d["place"].get("kind") and d["place"].get("name") is None:
        print("WARN TourAPI 근처 장소 이름 없음(외부 데이터 변화, 허용): " + desc, file=sys.stderr); return
    need("place", ok, desc)
w, s, p, f = d.get("weather"), d.get("sun"), d.get("place"), d.get("festival")
if which == "now":
    rest = [e for e in errs if not transient(e)]
    if rest: bad.append("errors=%s" % rest)
    need("weather", bool(w) and w.get("kind") in ("observed", "forecast") and w.get("source") == "기상청", "weather=%s" % w)
    need("sun", bool(s) and re.fullmatch(r"\d\d:\d\d", s.get("sunset") or ""), "sun=%s" % s)
    need_place(bool(p) and p.get("kind") == "TOURIST" and p.get("name") and 0 <= (p.get("distanceM") or -1) <= 1000
         and p.get("name") != "라카이 샌드파인 수영장", "place=%s" % p)
else:  # 1024
    rest = [e for e in errs if not transient(e)]
    if rest != ["weather:out_of_window"]: bad.append("errors=%s" % rest)
    if w is not None: bad.append("weather=%s" % w)
    need("sun", bool(s) and s.get("sunset") == "17:36", "sun=%s" % s)
    need("festival", bool(f) and f.get("name") == "강릉커피축제" and f.get("dayIndex") == 4, "festival=%s" % f)
    need_place(bool(p) and p.get("name") == "경포호수광장" and p.get("distanceM") == 859, "place=%s" % p)
print("ok" if not bad else "; ".join(bad))
PY
}
# "지금"은 실황 지연·일시 장애가 잦아 한 번 다시 묻는다.
raw "outside 경포 지금" 200 "$FN/outside?$GP&t=$NOW_MS" -H "apikey: $KEY"
NOW_RESULT="$(assess now)"
if [ "$NOW_RESULT" != ok ]; then
  echo "WARN 지금 첫 시도: $NOW_RESULT → 한 번 다시"
  raw "outside 경포 지금(재시도)" 200 "$FN/outside?$GP&t=$NOW_MS" -H "apikey: $KEY"
  NOW_RESULT="$(assess now)"
fi
check "지금: 날씨(실황/예보)·일몰·장소(이름·거리)" "ok" "$NOW_RESULT"
check "지금: Cache-Control no-store" "no-store" \
  "$(curl -s -o /dev/null -D - "$FN/outside?$GP&t=$NOW_MS&parts=sun" -H "apikey: $KEY" | tr -d '\r' | awk -F': ' 'tolower($1)=="cache-control"{print $2}')"
T1024="$(python3 -c 'import datetime;print(int(datetime.datetime.fromisoformat("2026-10-24T13:20:00+09:00").timestamp()*1000))')"
raw "outside 경포 2026-10-24 13:20" 200 "$FN/outside?$GP&t=$T1024" -H "apikey: $KEY"
check "10-24: 커피축제 넷째 날·일몰 17:36·경포호수광장 859m·날씨 창 밖" "ok" "$(assess 1024)"
raw "outside/holidays 2026" 200 "$FN/outside/holidays?year=2026" -H "apikey: $KEY"
check "특일 20건 이상·10-09 한글날" "yes 한글날" \
  "$(jget "['holidays'].__len__()>=20 and 'yes' or 'no'") $(python3 -c 'import json,sys; print(next((h["name"] for h in json.load(open(sys.argv[1]))["holidays"] if h["date"]=="2026-10-09"),"없음"))' "$BODY_FILE")"
check "holidays Cache-Control" "public, max-age=86400" \
  "$(curl -s -o /dev/null -D - "$FN/outside/holidays?year=2026" -H "apikey: $KEY" | tr -d '\r' | awk -F': ' 'tolower($1)=="cache-control"{print $2}')"
raw "outside 범위 밖 좌표" 400 "$FN/outside?lat=30.0&lon=128.9&t=$NOW_MS" -H "apikey: $KEY"
raw "outside t 없음" 400 "$FN/outside?$GP" -H "apikey: $KEY"
raw "outside 키 없이" 401 "$FN/outside?$GP&t=$NOW_MS"

# --- AI 기록문 (diary, Task 11): 선택 기능. 배포 안 됨(404)·AI 꺼짐(503)이면 "AI 꺼짐"으로 통과, 200도 통과 ---
DIARY_BODY='{"facts":["4명이 함께 출발했습니다.","강릉 경포해변에 도착했습니다."]}'
DIARY="$(curl -s -o "$BODY_FILE" -w '%{http_code}' -X POST "$FN/diary" -H 'content-type: application/json' -H "apikey: $KEY" --data "$DIARY_BODY")"
case "$DIARY" in
  200) echo "OK   diary (200)" ;;
  404|503) echo "OK   diary ($DIARY): AI 꺼짐" ;;
  *) echo "FAIL diary: 기대 200·404·503, 실제 $DIARY $(cat "$BODY_FILE")"; FAILED=1 ;;
esac

if [ "$FAILED" != 0 ]; then
  echo "backend_smoke: 실패"
  exit 1
fi
echo "backend_smoke: 모두 통과"
