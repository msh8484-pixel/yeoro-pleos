#!/usr/bin/env python3
"""여로 숏폼(9:16, 1080x1920) 편집: Higgsfield 1인칭 장면 + 실제 앱 화면 카드 + 자막 + 음악.
사용: python3 make_short.py  → out/여로_숏폼.mp4
src/clip0.mp4 … clip7.mp4 (Higgsfield), src/music_a.mp3, 앱 화면은 ../../yeoro/docs/screens/2b/*.png
"""
import subprocess, pathlib, shutil
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent
SRC, OUT, TMP = ROOT / "src", ROOT / "out", ROOT / "tmp"
SHOTS = ROOT.parent.parent / "yeoro/docs/screens/2b"
W, H, FPS = 1080, 1920, 30
FB = str(SRC / "Pretendard-Bold.otf"); FS = str(SRC / "Pretendard-SemiBold.otf"); FM = str(SRC / "Pretendard-Medium.otf")
AMBER = (255, 180, 106)

# (종류, 소스, 길이, 자막 큰 줄, 자막 작은 줄)
SEGMENTS = [
    ("clip", "clip0", 3.2, "토요일 아침, 짐을 싣는다", None),
    ("ui", "demo-02-ask", 3.8, "차가 먼저 알아챈다", "좌석 4석 · 트렁크 · 먼 목적지"),
    ("clip", "clip1", 3.0, "뒷좌석엔 아이 둘", None),
    ("clip", "clip2", 3.0, "비가 오고", None),
    ("clip", "clip3", 2.8, "그치고", None),
    ("ui", "demo-07-windows", 3.6, "달리는 동안엔 조용히", "와이퍼 · 창문 · 쉼을 차가 기록"),
    ("clip", "clip4", 3.0, "창문 네 개가 열린 순간", None),
    ("clip", "clip5", 2.8, "도착", None),
    ("ui", "demo-08-arrival", 3.8, "두 번째 방문이에요", "P단에 서면 차가 도착을 확인"),
    ("clip", "clip6", 3.0, "사진은 각자 폰에서 QR로", None),
    ("clip", "clip7", 2.8, "그리고 집에 오면", None),
    ("ui", "demo-12-cover", 3.2, "한 권의 앨범", "차가 기억한 길과 순간"),
    ("ui", "demo-15b-then-now", 3.4, "같은 곳, 일 년의 차이", "다시 가면 그때 vs 지금"),
    ("end", None, 4.6, None, None),
]

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode: raise SystemExit(r.stderr[-2000:])

def font(path, size): return ImageFont.truetype(path, size)

def caption_png(big, small, path):
    """아래쪽 자막: 그림자 깔린 흰 큰 글씨 + 앰버 작은 줄"""
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    # 아래 그라데이션(읽기 쉽게)
    grad = Image.new("L", (1, 700))
    for y in range(700): grad.putpixel((0, y), int(170 * (y / 700) ** 1.6))
    im.paste((8, 10, 18, 255), (0, H - 700, W, H), grad.resize((W, 700)))
    d = ImageDraw.Draw(im)
    y = H - 360
    if big:
        f = font(FB, 76)
        tw = d.textlength(big, font=f)
        d.text(((W - tw) / 2, y), big, font=f, fill=(255, 255, 255, 255))
    if small:
        f2 = font(FM, 40)
        tw = d.textlength(small, font=f2)
        d.text(((W - tw) / 2, y + 112), small, font=f2, fill=AMBER + (255,))
    im.save(path)

CROPS = {"demo-02-ask": (1010, 200, 2500, 965), "demo-07-windows": (1000, 190, 2500, 760), "demo-08-arrival": (1000, 170, 2500, 1120)}

def card_png(shot, path):
    """앱 화면에서 Pleos 앱 영역만 잘라 둥근 카드 + 그림자"""
    src = Image.open(SHOTS / f"{shot}.png").convert("RGB")
    app = src.crop(CROPS.get(shot, (860, 92, 2540, 1298)))   # 핵심 부분만 (기본: 왼쪽 계기판·아래 공조 바 제외한 앱 영역)
    cw = 980; ch = int(app.height * cw / app.width)
    app = app.resize((cw, ch), Image.LANCZOS)
    r = 36; pad = 60
    canvas = Image.new("RGBA", (cw + pad * 2, ch + pad * 2), (0, 0, 0, 0))
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((pad, pad + 18, pad + cw, pad + ch + 18), r, fill=(0, 0, 0, 150))
    canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(26)))
    mask = Image.new("L", (cw, ch), 0); ImageDraw.Draw(mask).rounded_rectangle((0, 0, cw, ch), r, fill=255)
    canvas.paste(app, (pad, pad), mask)
    canvas.save(path)

def end_png(path):
    im = Image.new("RGB", (W, H), (14, 18, 32))
    # 은은한 앰버 빛
    glow = Image.new("RGB", (W, H), (0, 0, 0)); ImageDraw.Draw(glow).ellipse((-200, 300, W + 200, 1300), fill=(90, 55, 25))
    im = Image.blend(im, glow.filter(ImageFilter.GaussianBlur(220)), 0.55)
    d = ImageDraw.Draw(im)
    def center(t, f, y, fill):
        d.text(((W - d.textlength(t, font=f)) / 2, y), t, font=f, fill=fill)
    center("여로", font(FB, 150), 700, (255, 255, 255))
    center("차가 기억하는 우리 가족 여행", font(FS, 56), 900, (238, 240, 247))
    center("좌석 · 트렁크 · 기어 · 와이퍼 · 창문", font(FM, 38), 1010, AMBER)
    center("카메라 없이, 차가 이미 아는 신호만으로", font(FM, 38), 1066, (170, 176, 196))
    center("Pleos Connect 차량용 앱 · 엠베이스", font(FM, 34), 1640, (130, 136, 156))
    center("#HMG차량용앱개발공모전  #PleosAppMarket", font(FM, 32), 1700, (130, 136, 156))
    im.save(path)

GRADE = "eq=contrast=1.04:saturation=1.05,colorbalance=rs=0.03:bs=-0.03:rm=0.02:bm=-0.02,noise=alls=7:allf=t"

def seg_clip(i, name, dur, cap):
    out = TMP / f"s{i:02d}.mp4"
    run(["ffmpeg", "-y", "-ss", "0.6", "-t", f"{dur}", "-i", str(SRC / f"{name}.mp4"), "-loop", "1", "-t", f"{dur}", "-i", str(cap),
         "-filter_complex",
         f"[0:v]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},{GRADE},setsar=1[v];"
         f"[1:v]format=rgba,fade=in:st=0.15:d=0.4:alpha=1[c];[v][c]overlay=0:0,format=yuv420p",
         "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(out)])
    return out

def seg_ui(i, shot, dur, cap, bg_clip):
    card = TMP / f"card{i:02d}.png"; card_png(shot, card)
    out = TMP / f"s{i:02d}.mp4"
    cw, ch = Image.open(card).size
    x = (W - cw) // 2; y0 = (H - ch) // 2 - 170
    run(["ffmpeg", "-y", "-t", f"{dur}", "-i", str(SRC / f"{bg_clip}.mp4"), "-loop", "1", "-t", f"{dur}", "-i", str(card),
         "-loop", "1", "-t", f"{dur}", "-i", str(cap),
         "-filter_complex",
         f"[0:v]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},boxblur=28:2,eq=brightness=-0.16:saturation=0.8,{GRADE},setsar=1[bg];"
         f"[1:v]format=rgba,fade=in:st=0:d=0.35:alpha=1[k];"
         f"[bg][k]overlay={x}:'{y0}+40*max(0,1-t/0.6)'[v1];"
         f"[2:v]format=rgba,fade=in:st=0.3:d=0.4:alpha=1[c];[v1][c]overlay=0:0,format=yuv420p",
         "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "18", str(out)])
    return out

def seg_end(i, dur):
    png = TMP / "end.png"; end_png(png)
    out = TMP / f"s{i:02d}.mp4"
    run(["ffmpeg", "-y", "-loop", "1", "-t", f"{dur}", "-i", str(png), "-vf",
         f"fps={FPS},noise=alls=5:allf=t,fade=in:st=0:d=0.6,format=yuv420p", "-c:v", "libx264", "-crf", "18", str(out)])
    return out

def main():
    TMP.mkdir(exist_ok=True); OUT.mkdir(exist_ok=True)
    parts = []; last_clip = "clip0"
    for i, (kind, src, dur, big, small) in enumerate(SEGMENTS):
        cap = TMP / f"cap{i:02d}.png"
        if kind != "end": caption_png(big, small, cap)
        if kind == "clip": parts.append(seg_clip(i, src, dur, cap)); last_clip = src
        elif kind == "ui": parts.append(seg_ui(i, src, dur, cap, last_clip))
        else: parts.append(seg_end(i, dur))
    lst = TMP / "list.txt"; lst.write_text("".join(f"file '{p}'\n" for p in parts))
    total = sum(s[2] for s in SEGMENTS)
    out = OUT / "여로_숏폼.mp4"
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(lst), "-i", str(SRC / "music_a.mp3"),
         "-filter_complex", f"[1:a]atrim=0:{total},afade=in:st=0:d=0.8,afade=out:st={total-2.2}:d=2.2,volume=0.9[a]",
         "-map", "0:v", "-map", "[a]", "-c:v", "libx264", "-crf", "19", "-preset", "medium", "-c:a", "aac", "-b:a", "192k",
         "-movflags", "+faststart", "-t", f"{total}", str(out)])
    print(out, f"{total:.1f}s")

if __name__ == "__main__":
    main()
