#!/usr/bin/env python3
"""make_template.py — テンプレートAPKを組み立てる。

aapt2 link の出力 (template-base.apk) と d8 の出力 (classes.dex) を受け取り、
META-INF を除いた形で 1 つの APK に結合する。

ZIP は自前で書く。理由:
  - targetSdk>=30 の APK では resources.arsc が「無圧縮 & 4バイト境界アライメント」必須。
  - zipfile はエントリの絶対オフセット制御ができないため、zipalign と同じことを自前で行う。

このスクリプトは ApkGenerator.java (ジェネレーター本体) のリファレンス実装でもある。
Java 側はこのロジック (local file header + central directory + アライメント) を移植する。
"""
import os, struct, sys, zlib


def deflate(data: bytes) -> bytes:
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    return c.compress(data) + c.flush()


def crc32(data: bytes) -> int:
    return zlib.crc32(data) & 0xFFFFFFFF


def zipalign_extra(pad: int, align: int) -> bytes:
    """ZIP 拡張フィールドでパディングを表現する (zipalign と同じ方式)。

    extra field は [id:2][size:2][data:size] のため最小 4 バイト。
    pad が 4 未満の場合は align を足して 4 バイト以上を確保する。
    id 0xD935 は Android の zipalign が使う「パディング専用」ID。
    """
    if pad <= 0:
        return b""
    if pad < 4:
        pad += align
    body = b"\x00" * (pad - 4)
    return struct.pack("<HH", 0xD935, pad - 4) + body


class ApkWriter:
    """アライメント制御つきの最小 ZIP (APK) ライタ。"""

    def __init__(self):
        self.chunks = bytearray()
        self.offset = 0
        self.central = []  # (name_b, method, crc, csize, usize, offset, extra)

    def _write(self, b: bytes):
        self.chunks += b
        self.offset += len(b)

    def add(self, name: str, data: bytes, method: int, align: int = 4):
        """method: 0=STORED(無圧縮), 8=DEFLATED。STORED のとき align バイト境界に揃える。"""
        name_b = name.encode("utf-8")
        crc = crc32(data)
        if method == 8:
            payload = deflate(data)
        else:
            payload = data
        csize = len(payload)

        extra = b""
        if method == 0 and align > 1:
            data_start = self.offset + 30 + len(name_b)
            pad = (align - data_start % align) % align
            extra = zipalign_extra(pad, align)

        local = struct.pack("<IHHHHHIIIHH",
                            0x04034B50, 20, 0, method, 0, 0,
                            crc, csize, len(data), len(name_b), len(extra))
        entry_start = self.offset
        self._write(local + name_b + extra + payload)
        self.central.append((name_b, method, crc, csize, len(data), entry_start, extra))

    def finish(self) -> bytes:
        cd_start = self.offset
        for name_b, method, crc, csize, usize, off, extra in self.central:
            self._write(struct.pack("<IHHHHHHIIIHHHHHII",
                                    0x02014B50, 20, 20, 0, method, 0, 0,
                                    crc, csize, usize, len(name_b), len(extra),
                                    0, 0, 0, 0, off) + name_b + extra)
        cd_size = self.offset - cd_start
        self._write(struct.pack("<IHHHHIIH",
                                0x06054B50, 0, 0, len(self.central), len(self.central),
                                cd_size, cd_start, 0))
        return bytes(self.chunks)


def load_base_entries(base_apk: str):
    """template-base.apk から META-INF を除いたエントリを返す。"""
    import zipfile
    entries = []
    with zipfile.ZipFile(base_apk) as z:
        for item in z.infolist():
            if item.filename.startswith("META-INF/"):
                continue
            # aapt2 は resources.arsc を STORED、それ以外を DEFLATED で出力する
            entries.append((item.filename, z.read(item.filename), item.compress_type))
    return entries


def build(base_apk: str, dex_path: str, config_json: bytes, out_path: str):
    import zipfile
    entries = load_base_entries(base_apk)
    with open(dex_path, "rb") as f:
        entries.append(("classes.dex", f.read(), 8))
    entries.append(("assets/app_config.json", config_json, 8))

    w = ApkWriter()
    for name, data, method in entries:
        w.add(name, data, 0 if method == zipfile.ZIP_STORED else 8, align=4)
    blob = w.finish()
    with open(out_path, "wb") as f:
        f.write(blob)
    print("written:", out_path, len(blob), "bytes,", len(entries), "entries")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        print("usage: make_template.py <template-base.apk> <classes.dex> <app_config.json> <out.apk>")
        sys.exit(1)
    with open(sys.argv[3], "rb") as f:
        cfg = f.read()
    build(sys.argv[1], sys.argv[2], cfg, sys.argv[4])
