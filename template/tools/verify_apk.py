#!/usr/bin/env python3
"""verify_apk.py — 生成された APK を aapt / apksigner / zipalign で検証する。

使い方:
  python3 verify_apk.py <apk> [--package com.example.app] [--label アプリ名]
                        [--min-sdk 21] [--target-sdk 34]
                        [--asset assets/web/index.html:/path/to/index.html] ...

終了コード 0 で全項目合格。1 で不合格。
"""
import argparse, os, subprocess, sys, zipfile

BT = os.environ.get("BUILD_TOOLS", "/workspace/tools/android-sdk/build-tools/34.0.0")


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr).strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--package")
    ap.add_argument("--label")
    ap.add_argument("--min-sdk", type=int)
    ap.add_argument("--target-sdk", type=int)
    ap.add_argument("--asset", action="append", default=[],
                    help="APK内パス:比較するローカルファイル")
    ap.add_argument("--config", action="append", default=[],
                    help="app_config.json に含まれているべき 'キー=値'")
    ap.add_argument("--require-v1", action="store_true")
    args = ap.parse_args()

    failures = []

    def ok(label, cond, detail=""):
        print(("PASS " if cond else "FAIL ") + label + ((" :: " + detail) if detail and not cond else ""))
        if not cond:
            failures.append(label)

    # --- aapt dump badging ---
    code, out = run([f"{BT}/aapt", "dump", "badging", args.apk])
    ok("aapt dump badging が通る", code == 0, out)
    badging = {}
    for line in out.splitlines():
        if line.startswith("package:"):
            for part in line.split():
                if part.startswith("name="):
                    badging["package"] = part.split("=", 1)[1].strip("'")
                if part.startswith("versionName="):
                    badging["versionName"] = part.split("=", 1)[1].strip("'")
        if line.startswith("sdkVersion:"):
            badging["minSdk"] = int(line.split(":")[1].strip().strip("'"))
        if line.startswith("targetSdkVersion:"):
            badging["targetSdk"] = int(line.split(":")[1].strip().strip("'"))
        if line.startswith("application-label:"):
            badging["label"] = line.split(":", 1)[1].strip().strip("'")
        if line.startswith("launchable-activity:"):
            badging["activity"] = line.split("name=")[1].split()[0].strip("'")

    if args.package:
        ok("パッケージ名", badging.get("package") == args.package,
           str(badging.get("package")))
    if args.label:
        ok("アプリ名", badging.get("label") == args.label, str(badging.get("label")))
    if args.min_sdk is not None:
        ok(f"minSdkVersion={args.min_sdk}", badging.get("minSdk") == args.min_sdk,
           str(badging.get("minSdk")))
    if args.target_sdk is not None:
        ok(f"targetSdkVersion={args.target_sdk}", badging.get("targetSdk") == args.target_sdk,
           str(badging.get("targetSdk")))
    ok("ランチャーアクティビティあり", badging.get("activity") == "com.webtoapp.standalone.MainActivity",
       str(badging.get("activity")))

    # --- マニフェストが壊れていないか (aapt xmltree が通る) ---
    code, out = run([f"{BT}/aapt", "dump", "xmltree", args.apk, "AndroidManifest.xml"])
    ok("AndroidManifest.xml が解析できる", code == 0 and "E: manifest" in out, out[:400])
    ok("uses-sdk が壊れていない", "E: uses-sdk" in out)

    # --- 署名 ---
    code, out = run([f"{BT}/apksigner", "verify", "--verbose", args.apk])
    ok("apksigner verify", code == 0 and "Verifies" in out, out)
    ok("v2 または v3 署名あり",
       "v2 scheme (APK Signature Scheme v2): true" in out
       or "v3 scheme (APK Signature Scheme v3): true" in out, out)
    if args.require_v1:
        ok("v1 署名あり (Android 5.0 向け)", "(JAR signing): true" in out)

    # --- アライメント ---
    code, out = run([f"{BT}/zipalign", "-c", "4", args.apk])
    ok("zipalign -c 4", code == 0, out)

    # --- 中身 ---
    with zipfile.ZipFile(args.apk) as z:
        names = z.namelist()
        ok("classes.dex あり", "classes.dex" in names)
        ok("resources.arsc あり", "resources.arsc" in names)
        ok("app_config.json あり", "assets/app_config.json" in names)
        ok("META-INF 署名あり", any(n.startswith("META-INF/") for n in names))
        if "assets/app_config.json" in names:
            cfg = z.read("assets/app_config.json").decode("utf-8")
            for want in args.config:
                k, v = want.split("=", 1)
                key = f'"{k}":'
                ok(f"config {want}", key in cfg and cfg.split(key, 1)[1].lstrip().startswith(v),
                   cfg)
        for spec in args.asset:
            apk_path, local = spec.split(":", 1)
            ok(f"asset {apk_path} あり", apk_path in names)
            if apk_path in names and os.path.exists(local):
                ok(f"asset {apk_path} の内容一致",
                   z.read(apk_path) == open(local, "rb").read())

    print("\n%d failed" % len(failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
