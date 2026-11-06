#!/usr/bin/env python3
"""end_to_end_test.py — 生成パイプライン全体の Python 検証。

テンプレートAPKに対して
  1. AXML StringPool 書換 (パッケージ名 / アプリ名)
  2. アイコン差し替え (mipmap 5 解像度)
  3. assets/app_config.json 注入
  4. ApkWriter (アライメント維持) で再構築
  5. apksigner で v1+v2 署名
を行い、aapt dump badging / apksigner verify / zipalign で検証する。

ApkGenerator.java の移植前リファレンステスト。
"""
import io, struct, subprocess, sys, zipfile, os
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_template import ApkWriter

BT = "/workspace/tools/android-sdk/build-tools/34.0.0"
TEMPLATE = "/workspace/template-app/app/src/main/assets_template/template.apk"
KEYSTORE = "/workspace/tools/keystore/debug.keystore"

OLD_PKG = "com.webtoapp.standalone.template"
NEW_PKG = "jp.tanishi.testapp"
OLD_LABEL = "STANDALONE_TEMPLATE_APP_LABEL"
NEW_LABEL = "テスト生成アプリ"


def read_string_pool(b: bytes):
    sp_type, sp_hsize, sp_size, str_count, style_count, flags, str_start, style_start = \
        struct.unpack("<HHI5I", b[8:8+28])
    is_utf8 = bool(flags & 0x100)
    offsets = [struct.unpack("<I", b[8+28+4*i:8+28+4*(i+1)])[0] for i in range(str_count)]
    data_start = 8 + str_start
    strings = []
    for off in offsets:
        s0 = data_start + off
        if is_utf8:
            # u16len, u8len, data
            u16len = b[s0]
            u8len = b[s0+1]
            strings.append(b[s0+2:s0+2+u8len].decode("utf-8"))
        else:
            u16len = struct.unpack("<H", b[s0:s0+2])[0]
            strings.append(b[s0+2:s0+2+u16len*2].decode("utf-16le"))
    return sp_type, sp_hsize, sp_size, str_count, style_count, flags, str_start, style_start, strings


def write_string_pool(str_count, style_count, flags, strings):
    """UTF-16 形式で StringPool を再エンコードする。"""
    new_str = bytearray()
    offsets = []
    for s in strings:
        offsets.append(len(new_str))
        enc = s.encode("utf-16le")
        new_str += struct.pack("<H", len(s)) + enc + b"\x00\x00"
    while len(new_str) % 4:
        new_str += b"\x00"
    str_start = 28 + 4 * len(strings) + 4 * 0  # styles なし
    chunk_size = str_start + len(new_str)
    header = struct.pack("<HHI5I", 0x0001, 28, chunk_size, len(strings), 0, flags, str_start, 0)
    off_bytes = b"".join(struct.pack("<I", o) for o in offsets)
    return header + off_bytes + bytes(new_str)


def rewrite_axml(manifest: bytes, replacements: dict):
    total = struct.unpack("<I", manifest[4:8])[0]
    sp_type, sp_hsize, sp_size, str_count, style_count, flags, str_start, style_start, strings = \
        read_string_pool(manifest)
    strings = [replacements.get(s, s) for s in strings]
    new_pool = write_string_pool(str_count, style_count, flags, strings)
    rest = manifest[8 + sp_size:]
    new_total = 8 + len(new_pool) + len(rest)
    return struct.pack("<HHI", 0x0003, 8, new_total) + new_pool + rest


def main():
    # 1-3. エントリ生成
    with zipfile.ZipFile(TEMPLATE) as z:
        base_entries = [(i.filename, z.read(i.filename), i.compress_type) for i in z.infolist()
                        if not i.filename.startswith("META-INF/")]

    out_entries = []
    for name, data, ct in base_entries:
        if name == "AndroidManifest.xml":
            data = rewrite_axml(data, {OLD_PKG: NEW_PKG, OLD_LABEL: NEW_LABEL})
        elif name.startswith("res/mipmap-") and name.endswith("ic_launcher.png"):
            sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
            px = sizes.get(name.split("-")[1].split("-v4")[0], 48)
            img = Image.new("RGBA", (px, px), (220, 60, 40, 255))
            bio = io.BytesIO()
            img.save(bio, "PNG")
            data = bio.getvalue()
        out_entries.append((name, data, ct))

    config = ('{"url": "https://taniniya.github.io/tanishi-survivor/",'
              ' "appName": "テスト生成アプリ", "adBlock": true, "fullscreen": true}').encode()
    # 既存エントリは置き換え (重複禁止)
    replaced = False
    for i, (name, data, ct) in enumerate(out_entries):
        if name == "assets/app_config.json":
            out_entries[i] = (name, config, ct)
            replaced = True
    if not replaced:
        out_entries.append(("assets/app_config.json", config, 8))

    # 4. ApkWriter で再構築
    w = ApkWriter()
    for name, data, ct in out_entries:
        w.add(name, data, 0 if ct == zipfile.ZIP_STORED else 8, align=4)
    unsigned = "/workspace/build-test/gen_unsigned.apk"
    with open(unsigned, "wb") as f:
        f.write(w.finish())
    print("unsigned:", os.path.getsize(unsigned), "bytes")

    # 5. 署名
    signed = "/workspace/build-test/gen_signed.apk"
    if os.path.exists(signed):
        os.remove(signed)
    subprocess.run([f"{BT}/apksigner", "sign",
                    "--ks", KEYSTORE, "--ks-pass", "pass:android",
                    "--ks-key-alias", "androiddebugkey", "--key-pass", "pass:android",
                    "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                    "--out", signed, unsigned], check=True)
    print("signed:", os.path.getsize(signed), "bytes")

    # 検証
    r = subprocess.run([f"{BT}/aapt", "dump", "badging", signed], capture_output=True, text=True)
    print("--- badging ---")
    print("\n".join(l for l in r.stdout.splitlines() if any(
        k in l for k in ("package:", "application-label:", "launchable-activity", "sdkVersion", "targetSdk"))))
    r = subprocess.run([f"{BT}/apksigner", "verify", "--verbose", signed], capture_output=True, text=True)
    print("--- apksigner verify ---")
    print(r.stdout)
    r = subprocess.run([f"{BT}/zipalign", "-c", "4", signed], capture_output=True, text=True)
    print("--- zipalign ---", "OK" if r.returncode == 0 else "FAIL", r.stdout.strip())


if __name__ == "__main__":
    main()
