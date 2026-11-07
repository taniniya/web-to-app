#!/usr/bin/env python3
"""offline_test.py — 「URL からオフライン化」をローカルの静的サイトで検証する。

  1. template/tools/sample-site をローカル HTTP サーバで配信
  2. SiteDownloadHarness (SiteDownloader.download) を実行
  3. 書き出された assets を検査:
     - index.html が起点になっている
     - CSS / JS / 画像がすべてローカル参照へ書き換わっている
     - CSS の url() / @import も再帰的にたどれている
     - 外部ドメインの参照は絶対 URL のまま残る
     - 存在しない資源があっても失敗として数え、ページは生成される
     - <base> が除去され、charset が明示される

使い方:
  python3 offline_test.py --classes <classes dir> [--apksig <apksig.jar>]
"""
import argparse
import http.server
import json
import os
import re
import shutil
import socketserver
import subprocess
import sys
import threading

HERE = os.path.dirname(os.path.abspath(__file__))
SITE = os.path.join(HERE, "sample-site")


class Handler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def start_server(directory):
    handler = lambda *a, **kw: Handler(*a, directory=directory, **kw)
    httpd = socketserver.TCPServer(("127.0.0.1", 0), handler)
    port = httpd.server_address[1]
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    return httpd, port


def read(path):
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def resolve(base_dir, ref):
    ref = ref.split("?")[0].split("#")[0]
    return os.path.normpath(os.path.join(base_dir, ref))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", required=True)
    ap.add_argument("--apksig", default="")
    ap.add_argument("--out", default=os.environ.get("OUT_DIR", "/workspace/build-test/v22/offline"))
    args = ap.parse_args()

    java = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java")
    if not os.path.exists(java):
        java = shutil.which("java")
    if not java:
        print("java が見つかりません (JAVA_HOME を設定してください)")
        return 1

    httpd, port = start_server(SITE)
    url = "http://127.0.0.1:%d/index.html" % port
    out = args.out
    if os.path.isdir(out):
        shutil.rmtree(out)

    cp = args.classes + ((os.pathsep + args.apksig) if args.apksig else "")
    try:
        proc = subprocess.run([java, "-cp", cp, "SiteDownloadHarness", url, out],
                              capture_output=True, text=True)
    finally:
        httpd.shutdown()

    failures = []
    passes = []

    def ok(label, cond, detail=""):
        if cond:
            passes.append(label)
            print("PASS " + label)
        else:
            failures.append(label)
            print("FAIL " + label + ((" :: " + str(detail)[:600]) if detail else ""))

    print(proc.stdout[-2000:])
    if proc.returncode != 0:
        print(proc.stderr[-2000:], file=sys.stderr)

    manifest = None
    for line in proc.stdout.splitlines():
        if line.startswith("MANIFEST "):
            manifest = json.loads(line[len("MANIFEST "):])
    ok("ハーネスが終了コード 0 で完了", proc.returncode == 0, proc.stderr)
    if not manifest:
        print("MANIFEST 行がありません")
        return 1

    assets = set(manifest["assets"])
    ok("index.html が起点にある", "index.html" in assets, sorted(assets))
    index_path = os.path.join(out, "index.html")
    ok("index.html が書き出されている", os.path.exists(index_path))
    if not os.path.exists(index_path):
        return 1
    index = read(index_path)
    css_dir_name = [a for a in assets if a.endswith("/css")]

    # --- タイトル ---
    ok("ページタイトルを取得", manifest["title"] == "オフライン化テスト サイト", manifest["title"])

    # --- 同じサイトの資源がそろっている ---
    def find(name):
        for a in assets:
            if a.endswith("/" + name) or a == name:
                return a
        return None

    for name in ("css/style.css", "css/more.css", "js/app.js", "img/logo.png", "img/bg.png"):
        ok("取得: %s" % name, find(name) is not None, sorted(assets))

    # --- バイト一致 ---
    logo = find("img/logo.png")
    if logo:
        ok("画像のバイトが一致", open(os.path.join(out, logo), "rb").read()
           == open(os.path.join(SITE, "img", "logo.png"), "rb").read())

    js = find("js/app.js")
    if js:
        ok("JS のバイトが一致", open(os.path.join(out, js), "rb").read()
           == open(os.path.join(SITE, "js", "app.js"), "rb").read())

    # --- index.html の参照がローカルになっている ---
    ok("<base> が除去されている", "<base" not in index.lower(), index[:400])
    ok("meta charset が入っている", re.search(r'<meta charset="[A-Za-z0-9_-]+">', index) is not None)

    refs = re.findall(r'(?:src|href)="([^"]+)"', index)
    bad = []
    for r in refs:
        if r.startswith(("http://", "https://", "data:", "#", "javascript:", "mailto:")):
            continue
        if not os.path.exists(resolve(out, r)):
            bad.append(r)
    ok("index.html のローカル参照がすべて実在", not bad, bad)

    ok("script のローカル化", re.search(r'<script src="[^"]*js/app\.js"', index) is not None, index[:600])
    ok("link(stylesheet) のローカル化",
       re.search(r'rel="stylesheet" href="[^"]*css/style\.css"', index) is not None, index[:600])
    ok("img のローカル化", re.search(r'<img src="[^"]*img/logo\.png"', index) is not None, index[:900])
    ok("インライン style の url() 書き換え",
       re.search(r'url\("[^"]*img/bg\.png"\)', index) is not None, index)

    # --- CSS の中の参照 ---
    css = find("css/style.css")
    if css:
        css_path = os.path.join(out, css)
        css_text = read(css_path)
        ok("@import を相対 url() に書き換え",
           re.search(r'@import\s+url\("[^"]*more\.css"\)', css_text) is not None, css_text[:400])
        ok("CSS の url() が実在ファイルを指す",
           all(os.path.exists(resolve(os.path.dirname(css_path), u))
               for u in re.findall(r'url\("([^"]+)"\)', css_text)
               if not u.startswith(("data:", "http"))), css_text[:400])
        ok("data: URL はそのまま", "data:image/png;base64" in css_text)

    # --- 外部参照と失敗 ---
    ok("外部 CDN は絶対 URL のまま残る", 'src="https://cdn.example.com/lib.js"' in index, index[:900])
    ok("外部参照はダウンロードしない", find("lib.js") is None)
    ok("外部参照を数えている", manifest["external"] >= 1, manifest)
    ok("存在しない資源を失敗として数える", manifest["failed"] >= 1, manifest)
    ok("存在しない資源があってもページはできる", manifest["fetched"] >= 6, manifest)
    ok("失敗 URL を記録している", any("missing.png" in u for u in manifest["failedUrls"]),
       manifest["failedUrls"])

    # --- リンク (<a>) は絶対 URL のまま ---
    ok("<a href> は絶対 URL に", "http://127.0.0.1:%d/next.html" % port in index, index[:900])

    print("\n%d passed, %d failed" % (len(passes), len(failures)))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
