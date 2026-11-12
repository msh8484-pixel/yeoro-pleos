#!/usr/bin/env python3
"""여로 숏폼 광고 v2 (9:16, 1080x1920): 에디토리얼 키네틱 타이포 + 1인칭/드론 장면 + 실제 앱 화면.
python3 make_ad.py → out/여로_숏폼_광고.mp4
"""
import subprocess, pathlib
from PIL import Image, ImageDraw, ImageFilter
import typo
from typo import Layout, caption_layers, vignette, overlay_filters, font, draw_tracked, tracked_len, AMBER

ROOT = pathlib.Path(__file__).resolve().parent
SRC, OUT, TMP = ROOT / "src", ROOT / "out", ROOT / "tmp_ad"
SHOTS = ROOT.parent.parent / "yeoro/docs/screens/2b"
W, H, FPS = 1080, 1920, 30
L = Layout(W, H, margin=88, bottom=1660, size=84, kicker_size=26)
LU = Layout(W, H, margin=88, bottom=1700, size=76, kicker_size=26)   # 앱 화면 장면(카드가 위에 있음)

# (종류, 소스, 길이, 키커, 헤드라인 줄)
SEGMENTS = [
    ("clip", "s_drone.mp4", 3.6, "YEORO", ["기억은,", "**사진보다 먼저** 시작됩니다."]),
    ("clip", "clip0.mp4", 2.6, "SAT  08:52", ["트렁크가 두 번 열리고,"]),
    ("clip", "clip1.mp4", 3.0, None, ["뒷좌석이 채워지면", "차는 **알아챕니다.**"]),
    ("ui", "demo-02-ask", 3.2, "01   DEPARTURE", ["묻는 건, **단 한 번.**"]),
    ("clip", "clip2.mp4", 2.4, None, ["비가 내리고,"]),
    ("clip", "clip3.mp4", 2.6, None, ["다시 **그치는** 동안에도"]),
    ("ui", "demo-07-windows", 3.2, "02   ON THE ROAD", ["달리는 내내,", "**조용히** 기억합니다."]),
    ("clip", "clip4.mp4", 2.6, None, ["창문을 내린 **그 순간**까지."]),
    ("clip", "s_cabin.mp4", 2.8, "03   ARRIVAL", ["도착은,", "**차가** 확인합니다."]),
    ("ui", "demo-08-arrival", 3.0, None, ["**두 번째** 방문이에요."]),
    ("clip", "s_dinner.mp4", 3.0, "04   LOCAL", ["그날의 쿠폰으로,", "바다 앞 **저녁 한 끼.**"]),
    ("ui", "demo-08d-reserve", 2.8, None, ["네 식구, **18:30** 예약."]),
    ("clip", "clip6.mp4", 2.8, None, ["사진은 **각자의 폰**에서."]),
    ("clip", "clip7.mp4", 2.4, None, ["그리고 집에 오면,"]),
    ("ui", "demo-12-cover", 3.0, "05   MEMORY", ["**한 권의 앨범**이 됩니다."]),
    ("ui", "demo-15b-then-now", 3.0, None, ["같은 곳,", "**일 년의 차이.**"]),
    ("end", None, 4.8, None, None),
]
CROPS = {"demo-02-ask": (1010, 200, 2500, 965), "demo-07-windows": (1000, 190, 2500, 760),
         "demo-08-arrival": (1000, 170, 2500, 1120), "demo-08d-reserve": (1010, 330, 2500, 1200)}
GRADE = "eq=contrast=1.05:saturation=1.04,colorbalance=rs=0.025:bs=-0.03:rm=0.015:bm=-0.015,noise=alls=6:allf=t"

def run(cmd):
    r = subprocess.run([str(c) for c in cmd], capture_output=True, text=True)
    if r.returncode: raise SystemExit(r.stderr[-2500:])

def card_png(shot, path):
    src = Image.open(SHOTS / f"{shot}.png").convert("RGB")
    app = src.crop(CROPS.get(shot, (860, 92, 2540, 1298)))
    cw = 960; ch = int(app.height * cw / app.width)
    app = app.resize((cw, ch), Image.LANCZOS)
    r, pad = 30, 70
    c = Image.new("RGBA", (cw + pad * 2, ch + pad * 2), (0, 0, 0, 0))
    sh = Image.new("RGBA", c.size, (0, 0, 0, 0)); ImageDraw.Draw(sh).rounded_rectangle((pad, pad + 24, pad + cw, pad + ch + 24), r, fill=(0, 0, 0, 170))
    c = Image.alpha_composite(c, sh.filter(ImageFilter.GaussianBlur(30)))
    m = Image.new("L", (cw, ch), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, cw, ch), r, fill=255)
    c.paste(app, (pad, pad), m)
    # 얇은 유리 테두리
    ImageDraw.Draw(c).rounded_rectangle((pad, pad, pad + cw, pad + ch), r, outline=(255, 255, 255, 40), width=2)
    c.save(path)
    return c.size

def end_png(path):
    im = Image.new("RGB", (W, H), (10, 12, 22))
    glow = Image.new("RGB", (W, H), (0, 0, 0)); ImageDraw.Draw(glow).ellipse((-300, 500, W + 300, 1500), fill=(110, 62, 24))
    im = Image.blend(im, glow.filter(ImageFilter.GaussianBlur(260)), 0.6)
    d = ImageDraw.Draw(im)
    def c(t, f, y, fill, track=0.0):
        tw = tracked_len(d, t, f, track); draw_tracked(d, ((W - tw) / 2, y), t, f, fill, track)
    c("여로", font("ExtraBold", 210), 690, (255, 255, 255), -0.02)
    c("차가 기억하는 가족 여행", font("Light", 56), 960, (236, 238, 245))
    d.rounded_rectangle((W / 2 - 22, 1080, W / 2 + 22, 1083), 1, fill=AMBER)
    c("PLEOS CONNECT  IN-CAR APP", font("SemiBold", 24), 1120, AMBER, 0.3)
    c("HYUNDAI MOTOR GROUP APP DEV CHALLENGE 2026", font("Medium", 22), 1640, (150, 156, 176), 0.22)
    c("#HMG차량용앱개발공모전   #PleosAppMarket", font("Medium", 26), 1690, (150, 156, 176))
    c("엠베이스", font("Medium", 26), 1745, (120, 126, 146))
    im.save(path)

def main():
    TMP.mkdir(exist_ok=True); OUT.mkdir(exist_ok=True)
    vig = TMP / "vig.png"; vignette(W, H, vig, strength=185, height_frac=0.5)
    parts, last_clip = [], "clip0.mp4"
    for i, (kind, src, dur, kicker, lines) in enumerate(SEGMENTS):
        out = TMP / f"s{i:02d}.mp4"
        if kind == "ui" and not (SHOTS / f"{src}.png").exists():
            print("skip (no screenshot):", src); continue
        if kind == "end":
            png = TMP / "end.png"; end_png(png)
            run(["ffmpeg", "-y", "-loop", "1", "-t", f"{dur}", "-i", png, "-vf", f"fps={FPS},noise=alls=4:allf=t,fade=in:st=0:d=0.7,format=yuv420p", "-c:v", "libx264", "-crf", "18", out])
            parts.append(out); continue
        layers = caption_layers(L if kind == "clip" else LU, kicker, lines, str(TMP / f"c{i:02d}"))
        if kind == "clip":
            last_clip = src
            base = [ "-ss", "0.5", "-t", f"{dur}", "-i", SRC / src, "-loop", "1", "-t", f"{dur}", "-i", vig ]
            pre = (f"[0:v]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},{GRADE},setsar=1,"
                   f"scale=iw*1.04:-1,crop={W}:{H}:'(iw-{W})/2':'(ih-{H})/2+10*t'[b0];[1:v]format=rgba[vg];[b0][vg]overlay[b]")
            n_in = 2
        else:
            card = TMP / f"card{i:02d}.png"; cw, ch = card_png(src, card)
            x = (W - cw) // 2; y0 = 470
            base = ["-t", f"{dur}", "-i", SRC / last_clip, "-loop", "1", "-t", f"{dur}", "-i", card, "-loop", "1", "-t", f"{dur}", "-i", vig]
            pre = (f"[0:v]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},boxblur=30:2,eq=brightness=-0.22:saturation=0.75,{GRADE},setsar=1[bg];"
                   f"[1:v]format=rgba,fade=in:st=0:d=0.4:alpha=1[k];[bg][k]overlay={x}:'{y0}+50*pow(max(0,1-t/0.7),3)'[b1];[2:v]format=rgba[vg];[b1][vg]overlay[b]")
            n_in = 3
        args, f, last = overlay_filters("b", layers, n_in, start=0.25, dur=dur)
        run(["ffmpeg", "-y", *base, *args, "-filter_complex", pre + ";" + f + f";[{last}]format=yuv420p[v]", "-map", "[v]", "-t", f"{dur}",
             "-an", "-c:v", "libx264", "-preset", "medium", "-crf", "18", out])
        parts.append(out)
    lst = TMP / "list.txt"; lst.write_text("".join(f"file '{p}'\n" for p in parts))
    total = sum(float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)])) for p in parts)
    out = OUT / "여로_숏폼_광고.mp4"
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", lst, "-i", SRC / "music_ad.mp3", "-filter_complex",
         f"[1:a]atrim=0:{total:.2f},afade=in:st=0:d=0.5,afade=out:st={total-2.5:.2f}:d=2.5[a]", "-map", "0:v", "-map", "[a]",
         "-c:v", "libx264", "-crf", "21", "-preset", "slow", "-maxrate", "14M", "-bufsize", "28M", "-c:a", "aac", "-b:a", "192k",
         "-movflags", "+faststart", "-t", f"{total:.2f}", out])
    print(out, f"{total:.1f}s")

if __name__ == "__main__":
    main()
