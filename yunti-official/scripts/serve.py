#!/usr/bin/env python3
"""本地官网预览：静态文件与 /api 网关代理。端口占用时直接退出。"""

from __future__ import annotations

import argparse
import os
import sys
import urllib.error
import urllib.request
import webbrowser
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Timer
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent


class Handler(SimpleHTTPRequestHandler):
    api_base = "http://127.0.0.1:9090/api"

    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path.startswith("/api/"):
            self.proxy()
            return
        if path == "/":
            self.path = "/index.html"
        else:
            target = (ROOT / path.lstrip("/")).resolve()
            if not target.is_relative_to(ROOT):
                self.send_error(403)
                return
            if path not in ("/index.html", "/demo.html", "/legal.html") and not path.startswith("/assets/"):
                self.send_error(404)
                return
            if not target.is_file():
                self.send_error(404)
                return
        super().do_GET()

    def proxy(self):
        url = self.api_base.rstrip("/") + self.path[len("/api"):]
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=5) as upstream:
                body = upstream.read()
                self.send_response(upstream.status)
                self.send_header("Content-Type", upstream.headers.get("Content-Type", "application/json"))
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        except urllib.error.HTTPError as error:
            self.send_error(error.code)
        except urllib.error.URLError:
            self.send_error(502, "API gateway unavailable")


def main():
    parser = argparse.ArgumentParser(description="云梯官网本地预览")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=int(os.environ.get("YUNTI_OFFICIAL_PORT", "5180")))
    parser.add_argument("--api-base", default=os.environ.get("YUNTI_API_BASE", "http://127.0.0.1:9090/api"))
    parser.add_argument("--no-open", action="store_true")
    args = parser.parse_args()
    Handler.api_base = args.api_base
    try:
        server = ThreadingHTTPServer((args.host, args.port), Handler)
    except OSError as error:
        print(f"预览端口不可用：{error}。请使用 --port 更换端口。", file=sys.stderr)
        return 1
    url = f"http://{args.host}:{args.port}/"
    print(f"官网预览：{url}（/api → {args.api_base}）")
    if not args.no_open:
        Timer(0.5, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
