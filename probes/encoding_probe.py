"""Does scripts/explainer.ps1 deliver the plan's bytes intact?

A regression here is silent: `ExplainerServer` decodes with `String(bytes, UTF_8)`, and Java
SUBSTITUTES U+FFFD for bad bytes rather than throwing. So in the IDE the POST does not fail -- the
walkthrough loads with corrupted text. Every em dash in English narration, and every character of a
Japanese or Russian one, goes through that path.

Do not run against a live sandbox: it takes over ~/.claude-explainer-{port,token}, and restores
whatever was there.
"""
import json
import os
import shutil
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~")
PORT_FILE = os.path.join(HOME, ".claude-explainer-port")
TOKEN_FILE = os.path.join(HOME, ".claude-explainer-token")
TOKEN = "encoding-probe-token"
HERE = os.path.dirname(os.path.abspath(__file__))
SKILL = os.path.expanduser("~/.claude/skills/audio-explainer")

received = {}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n)
        received["raw"] = raw
        received["content_type"] = self.headers.get("Content-Type")
        body = json.dumps({"ok": True, "frames": 1, "steps": 1, "issues": []}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    backup = {}
    for f in (PORT_FILE, TOKEN_FILE):
        if os.path.exists(f):
            backup[f] = f + ".probe-backup"
            shutil.copy2(f, backup[f])

    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    open(PORT_FILE, "w").write(str(port))
    open(TOKEN_FILE, "w").write(TOKEN)

    # A plan carrying exactly the characters real narration carries: an em dash and an arrow (English
    # prose gets these from the schema doc's own examples), and one line of Japanese, because the
    # plugin ships a Japanese voice and every character of that plan is non-ASCII.
    plan = {
        "type": "set_plan",
        "title": "Encoding probe — em dash",
        "language": "en",
        "frames": [{"id": 0, "file": "x.cs", "title": "t", "keep": [{"start": 1, "end": 2, "role": "focus"}]}],
        "steps": [{
            "frame": 0, "ranges": [{"start": 1, "end": 2}],
            "ttsText": "Parse(\"2024-01\") → DateOnly — an arrow and an em dash.",
            "note": "日本語のナレーション",
        }],
    }
    plan_path = os.path.join(HERE, "out", "encoding_probe.plan.json")
    os.makedirs(os.path.dirname(plan_path), exist_ok=True)
    with open(plan_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(plan, fh, ensure_ascii=False)
    on_disk = open(plan_path, "rb").read()
    print(f"plan on disk: {len(on_disk)} bytes, valid utf-8 = {is_utf8(on_disk)}")

    results = {}
    for name, argv in (
        ("explainer.ps1 (pwsh7)", ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                   "-File", os.path.join(SKILL, "scripts", "explainer.ps1"),
                                   "plan", plan_path]),
        # The script's header promises Windows PowerShell 5.1 too, and 5.1 is the one whose
        # Get-Content defaults to the ANSI code page -- so it is the host most likely to regress.
        ("explainer.ps1 (winps5.1)", ["C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe",
                                      "-NoProfile", "-ExecutionPolicy", "Bypass",
                                      "-File", os.path.join(SKILL, "scripts", "explainer.ps1"),
                                      "plan", plan_path]),
        ("curl --data-binary", ["curl", "-s", "-X", "POST",
                                f"http://127.0.0.1:{port}/api/message",
                                "-H", f"Authorization: Bearer {TOKEN}",
                                "-H", "Content-Type: application/json",
                                "--data-binary", "@" + plan_path]),
    ):
        received.clear()
        try:
            p = subprocess.run(argv, capture_output=True, text=True, timeout=90)
            out, err, rc = p.stdout.strip(), p.stderr.strip(), p.returncode
        except FileNotFoundError as e:
            print(f"\n--- {name}: not available ({e})")
            continue
        time.sleep(0.4)
        raw = received.get("raw")
        ok = raw is not None and raw == on_disk
        results[name] = ok
        print(f"\n--- {name}")
        print(f"    exit={rc} stdout={out[:120]!r} stderr={err[:160]!r}")
        if raw is None:
            print("    NOTHING ARRIVED at the server")
            continue
        print(f"    received {len(raw)} bytes (disk {len(on_disk)}), content-type={received.get('content_type')}")
        print(f"    byte-identical to disk: {ok}")
        print(f"    valid utf-8: {is_utf8(raw)}")
        if not ok:
            show_first_difference(on_disk, raw)
            # What the IDE would actually display, since Java substitutes rather than throws.
            print(f"    as the IDE would decode it: {raw.decode('utf-8', 'replace')[:150]!r}")

    for f, b in backup.items():
        shutil.move(b, f)
    if not backup:
        for f in (PORT_FILE, TOKEN_FILE):
            if os.path.exists(f):
                os.remove(f)

    print("\n== verdict ==")
    for k, v in results.items():
        print(f"  {'PASS' if v else 'FAIL'}  {k}")
    return 0 if all(results.values()) else 1


def is_utf8(b):
    try:
        b.decode("utf-8")
        return True
    except UnicodeDecodeError:
        return False


def show_first_difference(a, b):
    for i in range(min(len(a), len(b))):
        if a[i] != b[i]:
            print(f"    first difference at byte {i}: disk {a[i - 2:i + 6]!r} vs sent {b[i - 2:i + 6]!r}")
            return
    print(f"    same prefix, lengths differ: disk {len(a)} vs sent {len(b)}")


if __name__ == "__main__":
    sys.exit(main())
