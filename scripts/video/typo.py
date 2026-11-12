"""여로 영상 타이포그래피: 에디토리얼 키네틱 자막.
키커(작은 영문 대문자, 넓은 자간, 앰버 선) + 헤드라인(Pretendard Light, **굵게** 부분은 ExtraBold).
줄마다 따로 PNG를 만들고, ffmpeg에서 순서대로 아래에서 살짝 떠오르며 나타나게 한다.
"""
import pathlib, re
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SRC = pathlib.Path(__file__).resolve().parent / "src"
F = {w: str(SRC / f"Pretendard-{w}.otf") for w in ["Thin", "ExtraLight", "Light", "Regular", "Medium", "SemiBold", "Bold", "ExtraBold", "Black"]}
AMBER = (255, 184, 112)
WHITE = (255, 255, 255)
_fc = {}

def font(w, s):
    k = (w, s)
    if k not in _fc: _fc[k] = ImageFont.truetype(F[w], s)
    return _fc[k]

def draw_tracked(d, xy, text, f, fill, track=0.0):
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=f, fill=fill)
        x += d.textlength(ch, font=f) + f.size * track
    return x

def tracked_len(d, text, f, track):
    return sum(d.textlength(ch, font=f) + f.size * track for ch in text) - (f.size * track if text else 0)

def rich_len(d, line, size, light="Light", bold="ExtraBold"):
    return sum(d.textlength(seg, font=font(bold if b else light, size)) for seg, b in parse(line))

def parse(line):
    out = []
    for i, part in enumerate(re.split(r"\*\*", line)):
        if part: out.append((part, i % 2 == 1))
    return out

def draw_rich(d, xy, line, size, fill=WHITE, light="Light", bold="ExtraBold"):
    x, y = xy
    for seg, b in parse(line):
        f = font(bold if b else light, size)
        d.text((x, y), seg, font=f, fill=fill)
        x += d.textlength(seg, font=f)

class Layout:
    def __init__(self, W, H, margin, bottom, size, kicker_size, center=False):
        self.W, self.H, self.margin, self.bottom = W, H, margin, bottom
        self.size, self.ks, self.center = size, kicker_size, center

def caption_layers(L, kicker, lines, prefix):
    """줄마다 W×H 투명 PNG. 반환: [(경로, 등장 지연초)]"""
    out = []
    lh = int(L.size * 1.24)
    block_h = lh * len(lines) + (L.ks * 2.6 if kicker else 0)
    y = L.bottom - block_h
    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    delay = 0.0
    if kicker:
        im = Image.new("RGBA", (L.W, L.H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
        fk = font("SemiBold", L.ks); tw = tracked_len(probe, kicker, fk, 0.28)
        x = (L.W - tw - 64) / 2 if L.center else L.margin
        d.rounded_rectangle((x, y + L.ks * 0.55, x + 44, y + L.ks * 0.55 + 3), 1, fill=AMBER + (255,))
        draw_tracked(d, (x + 64, y), kicker, fk, AMBER + (255,), 0.28)
        p = f"{prefix}_k.png"; im.save(p); out.append((p, delay)); delay += 0.16
        y += L.ks * 2.6
    for i, line in enumerate(lines):
        im = Image.new("RGBA", (L.W, L.H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
        tw = rich_len(probe, line, L.size)
        x = (L.W - tw) / 2 if L.center else L.margin
        # 아주 옅은 그림자(밝은 장면에서도 읽히게)
        sh = Image.new("RGBA", (L.W, L.H), (0, 0, 0, 0)); ds = ImageDraw.Draw(sh)
        draw_rich(ds, (x, y + 3), line, L.size, fill=(0, 0, 0, 130))
        im = Image.alpha_composite(im, sh.filter(ImageFilter.GaussianBlur(8))); d = ImageDraw.Draw(im)
        draw_rich(d, (x, y), line, L.size)
        p = f"{prefix}_{i}.png"; im.save(p); out.append((p, delay)); delay += 0.22
        y += lh
    return out

def vignette(W, H, path, strength=170, height_frac=0.55):
    """아래쪽만 은은하게 어둡게 (박스 없이)"""
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    hh = int(H * height_frac)
    g = Image.new("L", (1, hh))
    for y in range(hh): g.putpixel((0, y), int(strength * (y / hh) ** 2.2))
    im.paste((6, 8, 14, 255), (0, H - hh, W, H), g.resize((W, hh)))
    im.save(path)

def overlay_filters(base_label, layers, first_input_index, start=0.35, rise=26, dur=None):
    """layers: [(path, delay)] → (ffmpeg 입력 인자 목록, filter 문자열, 최종 라벨)"""
    args, f, cur = [], [], base_label
    for j, (p, dl) in enumerate(layers):
        idx = first_input_index + j
        args += ["-loop", "1", "-i", p]
        t0 = start + dl
        f.append(f"[{idx}:v]format=rgba,fade=in:st={t0:.2f}:d=0.55:alpha=1" + (f",fade=out:st={dur-0.3:.2f}:d=0.3:alpha=1" if dur else "") + f"[L{j}]")
        f.append(f"[{cur}][L{j}]overlay=0:'if(lt(t,{t0:.2f}),{rise},{rise}*pow(max(0,1-(t-{t0:.2f})/0.7),3))':shortest=1[O{j}]")
        cur = f"O{j}"
    return args, ";".join(f), cur
