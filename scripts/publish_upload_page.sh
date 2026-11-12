#!/usr/bin/env bash
# 휴대폰 업로드 페이지를 공개 저장소(GitHub Pages)에 올린다.
# 올리는 파일은 정적 페이지 3개뿐이다: web/upload/index.html, web/upload/page.js, supabase/functions/_shared/exif.js
# (키·비밀 없음. 페이지는 키 없이 upload 함수만 부른다.)
# 사용: scripts/publish_upload_page.sh   → https://msh8484-pixel.github.io/yeoro-upload/?t=<token>
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${YEORO_UPLOAD_REPO:-msh8484-pixel/yeoro-upload}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

git clone -q "https://github.com/$REPO.git" "$WORK/site" 2>/dev/null || {
  echo "저장소를 받을 수 없다: $REPO"
  exit 1
}
cd "$WORK/site"
git checkout -q -B main
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp "$ROOT/web/upload/index.html" "$ROOT/web/upload/page.js" "$ROOT/supabase/functions/_shared/exif.js" .
touch .nojekyll

# 비밀이 섞이지 않았는지 확인 (Supabase 키·JWT·data.go.kr 키 이름)
if grep -RInE 'sb_secret_|sb_publishable_|sbp_|eyJ[A-Za-z0-9_-]{10,}|DATA_GO_KR|ANTHROPIC' --exclude-dir=.git . ; then
  echo "비밀처럼 보이는 문자열이 있어 멈춘다"
  exit 1
fi

git add -A
if git diff --cached --quiet; then
  echo "바뀐 것 없음"
else
  SRC="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  git -c user.email=yeoro-team@users.noreply.github.com -c user.name=yeoro-team commit -q -m "업로드 페이지 갱신 (yeoro $SRC)"
  git push -q origin main
  echo "올림: $(git rev-parse --short HEAD)"
fi
echo "https://${REPO%%/*}.github.io/${REPO#*/}/?t=<token>"
