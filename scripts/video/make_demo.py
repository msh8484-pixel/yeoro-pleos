#!/usr/bin/env python3
"""여로 시연 영상(16:9, 1920x1080): 1인칭 인트로 → 에뮬레이터 실제 동작 녹화(장면별 자막) → 아웃트로.
record_demo.sh가 남긴 rec/(rec_N.mp4, segs, log.tsv)를 쓴다.  python3 make_demo.py → out/여로_시연영상.mp4
"""
import subprocess, pathlib, re
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent
SRC, REC, OUT, TMP = ROOT / "src", ROOT / "rec", ROOT / "out", ROOT / "tmp_demo"
W, H, FPS = 1920, 1080, 30
FB = str(SRC / "Pretendard-Bold.otf"); FS = str(SRC / "Pretendard-SemiBold.otf"); FM = str(SRC / "Pretendard-Medium.otf")
AMBER = (255, 180, 106)
MAXLEN = 9.0       # 장면 하나의 목표 길이(초). 길면 앞부분을 빠르게 돌린다
TAIL = 4.0         # 장면 끝(결과 화면)은 이만큼 1배속으로

# 장면 번호 → (자막 큰 줄, 작은 줄). 없는 장면은 넣지 않는다
CAPS = {
    "00b": ("MEMORY", "1년 전 이번 주, **그곳**을 다시 꺼내 줍니다.", "작년 이맘때 다녀온 곳이 있으면 하루 한 번"),
    "01": ("DEPARTURE", "가족이 탔다는 걸, **차가 먼저** 압니다.", "좌석 4석 · 트렁크 2회 · 177km 관광지 · 주말"),
    "02": ("DEPARTURE", "묻는 건 **단 한 번**, 말로 답해도 됩니다.", "Pleos 온디바이스 음성 (TTS · STT)"),
    "03": ("ON THE ROAD", "달리는 동안엔 **화면도 쉽니다.**", "움직임 · 알림 없는 정지 화면 — 주행 규제 준수"),
    "04": ("ON THE ROAD", "휴게소에서 **38분.**", "P단 · 도어 신호"),
    "05": ("ON THE ROAD", "와이퍼가 켜지면, **비가 온 날**로.", "와이퍼 신호"),
    "06": ("ON THE ROAD", "와이퍼가 멈추면, **비가 그친 오후**로.", "와이퍼 꺼짐 10분"),
    "07": ("ON THE ROAD", "창문 네 개가 **열린 순간.**", "창문 신호"),
    "08": ("ARRIVAL", "도착은 **차가** 확인합니다.", "목적지 300m · P단 2분 → 스탬프 · 두 번째 방문"),
    "08c": ("LOCAL", "그 동네의 **쿠폰과 저녁 예약**까지.", "차가 확인한 탑승 인원 4명 그대로 · 제휴 시 제공 (예시)"),
    "08b": ("TOGETHER", "사진은 **각자의 폰**에서, QR 한 번.", "앱 설치 없이 · 찍은 시각대로 제자리에"),
    "09": ("HOME", "집에 오면, 여행이 **한 권으로** 닫힙니다.", "집 P단 → 앨범"),
    "10": ("MEMORY", "그날의 **날씨와 일몰**까지 담은 앨범.", "기상청 · 한국천문연구원 공공데이터"),
    "11": ("MEMORY", "가족이 찍은 사진이 **표지**가 됩니다.", None),
    "12": ("MEMORY", "글은 차가 **기록한 사실**로만.", "지어내지 않는 기록문"),
    "12b": ("AGAIN", "같은 곳, **일 년의 차이.**", "두 번째 방문부터 그때 vs 지금"),
    "12c": ("AGAIN", "다음 여행은 **지도 QR** 하나로.", "폰으로 넘겨 바로 길안내"),
    "13": ("OUR FAMILY", "우리 가족의 **여행 책장.**", "앨범 · 가족 지도 · 단골 단계"),
    "14": ("OUR FAMILY", "자주 가는 곳은 **다시 가기**로.", None),
}

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode: raise SystemExit(" ".join(map(str, cmd))[:300] + "\n" + r.stderr[-2500:])

def dur(p): return float(subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(p)]).strip())

def font(p, s): return ImageFont.truetype(p, s)

def lower_third(big, small, path, center=False):
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    g = Image.new("L", (1, 360))
    for y in range(360): g.putpixel((0, y), int(200 * (y / 360) ** 1.4))
    im.paste((8, 10, 18, 255), (0, H - 360, W, H), g.resize((W, 360)))
    d = ImageDraw.Draw(im)
    fb, fs = font(FB, 60), font(FM, 32)
    if center:
        d.text(((W - d.textlength(big, font=fb)) / 2, H - 230), big, font=fb, fill=(255, 255, 255, 255))
        if small: d.text(((W - d.textlength(small, font=fs)) / 2, H - 140), small, font=fs, fill=AMBER + (255,))
    else:
        d.rounded_rectangle((96, H - 222, 104, H - 112), 4, fill=AMBER + (255,))
        d.text((132, H - 232), big, font=fb, fill=(255, 255, 255, 255))
        if small: d.text((134, H - 150), small, font=fs, fill=AMBER + (255,))
    im.save(path)

RW, RH, RX, RY = 1536, 864, 192, 34      # 녹화 화면 자리 (위), 아래 띠에 자막

def frame_png(kicker, head, small, path):
    """본편 배경: 남색 바탕 + 녹화 자리 그림자 + 아래 띠에 에디토리얼 자막(왼쪽 정렬)"""
    import typo
    im = Image.new("RGB", (W, H), (10, 12, 22))
    glow = Image.new("RGB", (W, H), (0, 0, 0)); ImageDraw.Draw(glow).ellipse((200, 600, W - 200, 1500), fill=(80, 48, 20))
    im = Image.blend(im, glow.filter(ImageFilter.GaussianBlur(240)), 0.55).convert("RGBA")
    sh = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((RX, RY + 14, RX + RW, RY + RH + 14), 22, fill=(0, 0, 0, 170))
    im = Image.alpha_composite(im, sh.filter(ImageFilter.GaussianBlur(24)))
    d = ImageDraw.Draw(im)
    y = RY + RH + 30
    fk = typo.font("SemiBold", 20)
    d.rounded_rectangle((RX, y + 10, RX + 36, y + 12), 1, fill=typo.AMBER)
    typo.draw_tracked(d, (RX + 52, y), kicker, fk, typo.AMBER, 0.3)
    typo.draw_rich(d, (RX, y + 34), head, 48)
    if small:
        f2 = typo.font("Regular", 24)
        d.text((RX + RW - d.textlength(small, font=f2), y + 56), small, font=f2, fill=(160, 166, 186))
    im.convert("RGB").save(path)

def rec_mask(path):
    m = Image.new("L", (RW, RH), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, RW - 1, RH - 1), 22, fill=255); m.save(path)

def title_png(path, lines):
    im = Image.new("RGB", (W, H), (14, 18, 32))
    glow = Image.new("RGB", (W, H), (0, 0, 0)); ImageDraw.Draw(glow).ellipse((300, -100, W - 300, 900), fill=(90, 55, 25))
    im = Image.blend(im, glow.filter(ImageFilter.GaussianBlur(260)), 0.55)
    d = ImageDraw.Draw(im)
    for text, f, y, fill in lines:
        d.text(((W - d.textlength(text, font=f)) / 2, y), text, font=f, fill=fill)
    im.save(path)

GRADE = "eq=contrast=1.04:saturation=1.05,colorbalance=rs=0.03:bs=-0.03,noise=alls=6:allf=t"

def broll(i, clip, d, cap, out):
    run(["ffmpeg", "-y", "-ss", "0.5", "-t", f"{d}", "-i", str(SRC / clip), "-loop", "1", "-t", f"{d}", "-i", str(cap), "-filter_complex",
         f"[0:v]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},{GRADE},setsar=1[v];"
         f"[1:v]format=rgba,fade=in:st=0.3:d=0.5:alpha=1[c];[v][c]overlay,format=yuv420p", "-an", "-c:v", "libx264", "-crf", "18", str(out)])

def broll2(clip, d, kicker, lines, out):
    import typo
    L = typo.Layout(W, H, margin=120, bottom=960, size=68, kicker_size=22)
    layers = typo.caption_layers(L, kicker, lines, str(out.with_suffix("")))
    vig = TMP / "vig.png"
    if not vig.exists(): typo.vignette(W, H, vig, strength=190, height_frac=0.6)
    args, f, last = typo.overlay_filters("b", layers, 2, start=0.3, dur=d)
    run(["ffmpeg", "-y", "-ss", "0.5", "-t", f"{d}", "-i", str(SRC / clip), "-loop", "1", "-t", f"{d}", "-i", str(vig), *args, "-filter_complex",
         f"[0:v]scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},fps={FPS},{GRADE},setsar=1[b0];[1:v]format=rgba[vg];[b0][vg]overlay[b];" + f + f";[{last}]format=yuv420p[v]",
         "-map", "[v]", "-t", f"{d}", "-an", "-c:v", "libx264", "-crf", "18", str(out)])

def still(png, d, out, fade_in=True):
    vf = f"fps={FPS},noise=alls=4:allf=t" + (",fade=in:st=0:d=0.6" if fade_in else "") + ",format=yuv420p"
    run(["ffmpeg", "-y", "-loop", "1", "-t", f"{d}", "-i", str(png), "-vf", vf, "-c:v", "libx264", "-crf", "18", str(out)])

def scenes():
    """log.tsv의 ▶ 줄과 rec 조각 시작 시각으로 (번호, 녹화 기준 시작초, 끝초) 목록"""
    segs = [tuple(map(float, l.split())) for l in (REC / "segs").read_text().split("\n") if l.strip()]
    marks = []
    for l in (REC / "log.tsv").read_text().split("\n"):
        if "\t" not in l: continue
        t, text = l.split("\t", 1)
        m = re.search(r"▶\s*(\d+[a-z]?)\b", text)
        if m: marks.append((m.group(1), float(t)))
        if "시연 완료" in text: marks.append(("END", float(t)))
    return segs, marks

def main():
    TMP.mkdir(exist_ok=True); OUT.mkdir(exist_ok=True)
    segs, marks = scenes()
    # 녹화 조각을 이어 붙인 한 파일 + 조각마다 시작 시각 (조각 사이 1초 남짓 공백은 무시)
    files = sorted(REC.glob("rec_*.mp4"), key=lambda p: int(re.findall(r"\d+", p.stem)[0]))
    offsets = []; acc = 0.0
    for (idx, t), f in zip(segs, files):
        offsets.append((t, acc, dur(f))); acc += dur(f)
    (TMP / "rec_list.txt").write_text("".join(f"file '{f}'\n" for f in files))
    allrec = TMP / "rec_all.mp4"
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(TMP / "rec_list.txt"), "-vf", f"fps={FPS},scale={W}:{H}", "-an", "-c:v", "libx264", "-crf", "17", str(allrec)])
    def to_rec(t):
        for st, base, d in reversed(offsets):
            if t >= st: return base + min(t - st + 0.6, d)   # screenrecord 시작 지연 보정
        return 0.0

    parts = []
    mask = TMP / "mask.png"; rec_mask(mask)
    # 인트로
    parts.append(TMP / "p_i0.mp4"); broll2("h_drone.mp4", 4.6, "YEORO", ["기억은,", "**사진보다 먼저** 시작됩니다."], parts[-1])
    parts.append(TMP / "p_i1.mp4"); broll2("h_clip0.mp4", 4.2, "PLEOS CONNECT  IN-CAR APP", ["카메라 없이,", "**차가 이미 아는 신호**만으로."], parts[-1])
    # 본편: 장면별
    for k, (num, t) in enumerate(marks):
        if num == "END" or num not in CAPS: continue
        t_end = marks[k + 1][1] if k + 1 < len(marks) else t + 10
        a, b = to_rec(t), to_rec(t_end)
        length = b - a
        if length < 1.5: continue
        # 결과가 보이는 마지막 몇 초는 1배속, 그 앞(신호 넣는 과정)만 빠르게
        cap_max = MAXLEN + (2.5 if num in ("08", "12", "08b") else 0)
        tail = min(TAIL, length); head = length - tail
        hs = min(8.0, max(1.0, head / max(0.5, cap_max - tail))) if head > 0.05 else 1.0
        out_len = head / hs + tail
        bg = TMP / f"bg_{num}.png"; frame_png(*CAPS[num], bg)
        out = TMP / f"p_{num}.mp4"
        vsrc = (f"[0:v]trim=0:{head:.2f},setpts=(PTS-STARTPTS)/{hs:.3f}[h];[0:v]trim={head:.2f}:{length:.2f},setpts=PTS-STARTPTS[t];[h][t]concat=n=2:v=1[r];"
                if head > 0.05 else "[0:v]setpts=PTS-STARTPTS[r];")
        run(["ffmpeg", "-y", "-ss", f"{a:.2f}", "-t", f"{length:.2f}", "-i", str(allrec), "-loop", "1", "-t", f"{out_len:.2f}", "-i", str(bg),
             "-loop", "1", "-t", f"{out_len:.2f}", "-i", str(mask), "-filter_complex",
             vsrc + f"[r]fps={FPS},scale={RW}:{RH},format=rgba[r2];[2:v]format=gray[m];[r2][m]alphamerge[rr];"
             f"[1:v]fps={FPS},setsar=1[b];[b][rr]overlay={RX}:{RY},format=yuv420p",
             "-an", "-t", f"{out_len:.2f}", "-c:v", "libx264", "-crf", "18", str(out)])
        parts.append(out)
    # 아웃트로
    parts.append(TMP / "p_o0.mp4"); broll2("h_dinner.mp4", 4.0, "LOCAL", ["그날의 쿠폰으로,", "바다 앞 **저녁 한 끼.**"], parts[-1])
    parts.append(TMP / "p_o1.mp4"); broll2("h_clip2.mp4", 4.8, None, ["다시 가고 싶은 곳은,", "**차가 기억합니다.**"], parts[-1])
    end = TMP / "end.png"
    import typo
    title_png(end, [("여로", typo.font("ExtraBold", 170), 300, (255, 255, 255)),
                    ("차가 기억하는 가족 여행", typo.font("Light", 50), 530, (236, 238, 245)),
                    ("Vehicle  ·  NaviHelper  ·  TTS  ·  STT", typo.font("Regular", 28), 650, AMBER),
                    ("HYUNDAI MOTOR GROUP APP DEV CHALLENGE 2026  ·  엠베이스", typo.font("Medium", 22), 960, (140, 146, 166))])
    parts.append(TMP / "p_end.mp4"); still(end, 4.5, parts[-1])

    (TMP / "list.txt").write_text("".join(f"file '{p}'\n" for p in parts))
    total = sum(dur(p) for p in parts)
    out = OUT / "여로_시연영상.mp4"
    run(["ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(TMP / "list.txt"), "-i", str(SRC / "music_demo.mp3"), "-filter_complex",
         f"[1:a]atrim=0:{total:.2f},afade=in:st=0:d=1,afade=out:st={total-3:.2f}:d=3,volume=0.85[a]",
         "-map", "0:v", "-map", "[a]", "-c:v", "libx264", "-crf", "19", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-t", f"{total:.2f}", str(out)])
    print(out, f"{total:.1f}s")

if __name__ == "__main__":
    main()
