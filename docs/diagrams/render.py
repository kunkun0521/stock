# -*- coding: utf-8 -*-
"""渲染 docs/diagrams 下的 .puml 到同名 .png。

优先用本地 plantuml.jar（首次运行自动从 Maven 中央仓库下载），
下载失败时回退到 PlantUML 在线服务。

用法：  python render.py            # 渲染全部
        python render.py 01 08      # 只渲染指定编号
"""
import io
import os
import subprocess
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
JAR = os.path.join(HERE, "plantuml.jar")
JAR_URL = ("https://repo1.maven.org/maven2/net/sourceforge/plantuml/plantuml/"
           "1.2024.7/plantuml-1.2024.7.jar")
SERVER = "https://www.plantuml.com/plantuml/png/"
ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"


# ---------- 在线服务编码（备用） ----------
def encode6bit(b: int) -> str:
    if b < 10:
        return chr(48 + b)
    b -= 10
    if b < 26:
        return chr(65 + b)
    b -= 26
    if b < 26:
        return chr(97 + b)
    b -= 26
    return "-" if b == 0 else ("_" if b == 1 else "?")


def encode64(data: bytes) -> str:
    out = []
    for i in range(0, len(data), 3):
        b1 = data[i]
        b2 = data[i + 1] if i + 1 < len(data) else 0
        b3 = data[i + 2] if i + 2 < len(data) else 0
        out.append(encode6bit((b1 >> 2) & 0x3F))
        out.append(encode6bit((((b1 & 0x3) << 4) | (b2 >> 4)) & 0x3F))
        out.append(encode6bit((((b2 & 0xF) << 2) | (b3 >> 6)) & 0x3F))
        out.append(encode6bit(b3 & 0x3F))
    return "".join(out)


def render_online(name: str) -> bool:
    import urllib.request
    puml = os.path.join(HERE, name + ".puml")
    png = os.path.join(HERE, name + ".png")
    text = io.open(puml, encoding="utf-8").read()
    url = SERVER + "~1" + encode64(
        zlib.compressobj(9, zlib.DEFLATED, -15).compress(text.encode("utf-8")) +
        zlib.compressobj(9, zlib.DEFLATED, -15).flush())
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "render.py"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            body = resp.read()
        if not body.startswith(b"\x89PNG"):
            print("[fail]", name, "在线渲染返回非PNG")
            return False
        io.open(png, "wb").write(body)
        print("[ok-online]", name, len(body), "bytes")
        return True
    except Exception as e:
        print("[fail]", name, e)
        return False


# ---------- 本地 jar ----------
def ensure_jar() -> bool:
    if os.path.exists(JAR):
        return True
    print("[info] 下载 plantuml.jar ...")
    try:
        import urllib.request
        req = urllib.request.Request(JAR_URL, headers={"User-Agent": "render.py"})
        with urllib.request.urlopen(req, timeout=180) as resp:
            io.open(JAR, "wb").write(resp.read())
        return os.path.exists(JAR)
    except Exception as e:
        print("[warn] 下载失败：", e)
        return False


def render_local(names) -> bool:
    cmd = ["java", "-jar", JAR, "-charset", "UTF-8", "-tpng"] + \
          [n + ".puml" for n in names]
    r = subprocess.run(cmd, cwd=HERE)
    ok = r.returncode == 0
    for n in names:
        p = os.path.join(HERE, n + ".png")
        print(("[ok]" if os.path.exists(p) else "[fail]"), n,
              (os.path.getsize(p) if os.path.exists(p) else 0), "bytes")
    return ok


def main():
    stems = sorted(os.path.splitext(f)[0] for f in os.listdir(HERE) if f.endswith(".puml"))
    targets = sys.argv[1:]
    if targets:
        resolved = []
        for t in targets:
            hits = [s for s in stems if s == t or s.startswith(t)]
            if not hits:
                print("[skip] 找不到", t)
            resolved.extend(hits[:1] if hits else [])
        targets = resolved or stems
    else:
        targets = stems
    if ensure_jar():
        sys.exit(0 if render_local(targets) else 1)
    ok = all(render_online(t) for t in targets)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
