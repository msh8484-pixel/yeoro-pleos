#!/usr/bin/env python3
"""JPEG에 촬영 시각 EXIF를 넣는다 (표준 라이브러리만).

사용: scripts/exif_stamp.py <in.jpg> <out.jpg> "2026:10:31 13:30:00" [+09:00] [--orientation=N]

APP1 "Exif\\0\\0" → TIFF(II) → IFD0(0x8769 → ExifIFD) → ExifIFD(0x9003 DateTimeOriginal,
오프셋을 주면 0x9011 OffsetTimeOriginal). --orientation=N(1..8)을 주면 IFD0에 0x0112 Orientation도 넣는다
(세로 사진 방향 시험용). 기존 APP1 EXIF는 지우고 새로 끼운다(SOI·APP0 바로 뒤).
"""
from __future__ import annotations

import re
import struct
import sys


def build_exif(local: str, offset: str | None, orientation: int | None = None) -> bytes:
    entries = [(0x9003, local.encode("ascii") + b"\0")]
    if offset:
        entries.append((0x9011, offset.encode("ascii") + b"\0"))
    ifd0_off = 8
    ifd0_count = 2 if orientation else 1
    ifd0_size = 2 + 12 * ifd0_count + 4
    exif_off = ifd0_off + ifd0_size
    exif_size = 2 + 12 * len(entries) + 4
    data_off = exif_off + exif_size

    tiff = bytearray(b"II" + struct.pack("<HI", 42, ifd0_off))
    tiff += struct.pack("<H", ifd0_count)
    if orientation:  # 태그 번호 순서: 0x0112 → 0x8769. SHORT 값은 항목 안에 왼쪽 정렬
        tiff += struct.pack("<HHIHH", 0x0112, 3, 1, orientation, 0)
    tiff += struct.pack("<HHII", 0x8769, 4, 1, exif_off) + struct.pack("<I", 0)
    tiff += struct.pack("<H", len(entries))
    data = bytearray()
    for tag, value in entries:
        if len(value) <= 4:
            tiff += struct.pack("<HHI", tag, 2, len(value)) + value.ljust(4, b"\0")
        else:
            tiff += struct.pack("<HHII", tag, 2, len(value), data_off + len(data))
            data += value
            if len(data) % 2:
                data += b"\0"
    tiff += struct.pack("<I", 0)
    tiff += data
    payload = b"Exif\0\0" + bytes(tiff)
    return b"\xff\xe1" + struct.pack(">H", len(payload) + 2) + payload


def stamp(src: bytes, local: str, offset: str | None, orientation: int | None = None) -> bytes:
    if src[:2] != b"\xff\xd8":
        raise ValueError("JPEG가 아니다")
    out = bytearray(b"\xff\xd8")
    i = 2
    inserted = False
    app1 = build_exif(local, offset, orientation)
    while i < len(src):
        if src[i] != 0xFF:
            raise ValueError(f"마커가 아니다: offset {i}")
        marker = src[i + 1]
        if marker == 0xFF:  # 채움 바이트
            i += 1
            continue
        if marker == 0xDA or marker == 0xD9:  # SOS 이후는 그대로 복사
            if not inserted:
                out += app1
            out += src[i:]
            return bytes(out)
        length = struct.unpack(">H", src[i + 2:i + 4])[0]
        seg = src[i:i + 2 + length]
        i += 2 + length
        if marker == 0xE1 and seg[4:10] == b"Exif\0\0":
            continue  # 기존 EXIF 버림
        if not inserted and marker != 0xE0:  # APP0(JFIF) 뒤, 다른 세그먼트 앞
            out += app1
            inserted = True
        out += seg
    raise ValueError("SOS를 찾지 못했다")


def main(argv: list[str]) -> int:
    orientation = None
    flags = [a for a in argv if a.startswith("--orientation=")]
    argv = [a for a in argv if not a.startswith("--orientation=")]
    if flags:
        value = flags[-1].split("=", 1)[1]
        if not re.fullmatch(r"[1-8]", value):
            print("--orientation은 1..8", file=sys.stderr)
            return 2
        orientation = int(value)
    if len(argv) not in (4, 5):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    src_path, dst_path, local = argv[1], argv[2], argv[3]
    offset = argv[4] if len(argv) == 5 else None
    if not re.fullmatch(r"\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}", local):
        print("시각 형식: YYYY:MM:DD HH:MM:SS", file=sys.stderr)
        return 2
    if offset is not None and not re.fullmatch(r"[+-]\d{2}:\d{2}", offset):
        print("오프셋 형식: +09:00", file=sys.stderr)
        return 2
    with open(src_path, "rb") as f:
        src = f.read()
    with open(dst_path, "wb") as f:
        f.write(stamp(src, local, offset, orientation))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
