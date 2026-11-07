#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DuoLauncher 匿名安装统计接收端（零依赖，仅 Python 标准库）。

只接收「随机匿名 ID + 应用版本 + 界面语言 + Android API 等级 + 上报类型」，
不接收也不落盘 IP、设备型号、广告 ID、通讯录、位置等任何可识别信息。

端点：
  POST /ping             上报一条记录（body 见下）
  GET  /stats            本地 SQLite 聚合统计 JSON
  GET  /                 统计看板（优先读取 index.html，使用官方云服务 SDK）
  GET  /cloud-config.js  看板用的 publicConfig（可公开的应用标识）
  GET  /export           原始记录 JSONL（备份用）
  GET  /health           健康检查（含云服务状态）

POST /ping 请求体示例：
  {"v":1,"id":"c0a8...","app":"1.5.6","code":143,"lang":"zh","api":33,"kind":"install"}

环境变量：
  PORT                    监听端口（默认 3000）
  STATS_DATA_DIR          SQLite 数据目录（默认脚本同级 data/）
  CLOUD_ENDPOINT          云服务数据面地址（默认读 cloud-config.json）
  CLOUD_PUBLISHABLE_KEY   云服务应用标识（默认读 cloud-config.json）
  CLOUD_MODE=off          关闭云端同步，变成纯本地接收端

数据双写：本地 SQLite 始终是可靠副本；配置云服务后，每条通过校验的上报会异步
同步到云数据库 stats_pings（服务端 → 数据面桥接；浏览器看板用官方 CDN SDK 读取）。
云端写入失败不影响本地记录，也不影响客户端返回。

自托管：python3 server.py，再用 Nginx/Caddy 反代并启用 HTTPS；或直接用 Docker 部署。
"""

import json
import os
import re
import sqlite3
import threading
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(os.environ.get("PORT", "3000"))
BASE = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.environ.get("STATS_DATA_DIR", os.path.join(BASE, "data"))
try:
    os.makedirs(DATA_DIR, exist_ok=True)
    DB_PATH = os.path.join(DATA_DIR, "stats.db")
except OSError:
    DB_PATH = "/tmp/duolauncher-stats.db"

LOCK = threading.Lock()
ID_RE = re.compile(r"^[A-Za-z0-9_-]{8,64}$")
KINDS = {"install", "upgrade", "heartbeat", "test"}
MAX_BODY = 4096
THROTTLE_SEC = 60  # 同一匿名 ID 60 秒内的重复上报只更新时间，不重复计数
CLOUD_REST = "/.cloud/database/rest/stats_pings"


def load_cloud_config():
    """publicConfig 由 workbuddy_cloud_service 激活后写入 cloud-config.json；
    环境变量只用于覆盖（家里自托管时可设 CLOUD_MODE=off 走纯本地）。"""
    cfg = {}
    try:
        with open(os.path.join(BASE, "cloud-config.json"), "r", encoding="utf-8") as f:
            cfg = json.load(f)
    except Exception:
        pass
    cfg["endpoint"] = (cfg.get("endpoint") or "").rstrip("/")
    cfg["publishableKey"] = cfg.get("publishableKey") or ""
    # 空环境变量不覆盖配置文件，方便 compose 里保留占位变量
    if os.environ.get("CLOUD_ENDPOINT", "").strip():
        cfg["endpoint"] = os.environ["CLOUD_ENDPOINT"].strip().rstrip("/")
    if os.environ.get("CLOUD_PUBLISHABLE_KEY", "").strip():
        cfg["publishableKey"] = os.environ["CLOUD_PUBLISHABLE_KEY"].strip()
    if os.environ.get("CLOUD_MODE", "").strip().lower() in ("off", "0", "false"):
        cfg["endpoint"] = ""
    return cfg


CLOUD = load_cloud_config()


def cloud_push(uid, app, code, lang, api, kind):
    """把一条已校验的上报同步进云数据库。

    浏览器看板使用官方 CDN SDK；Android 原生与 Python 服务端没有 SDK 形态，
    因此这里沿用 SDK 注入的 x-wb-webapp-access-key 头访问同一数据面。
    失败静默，本地 SQLite 始终是可靠副本，绝不因云端故障影响客户端。
    """
    ep = CLOUD.get("endpoint", "")
    key = CLOUD.get("publishableKey", "")
    if not ep or not key:
        return False
    row = {"anon_id": uid, "app": app, "code": code,
           "lang": lang, "api": api, "kind": kind}
    req = urllib.request.Request(
        ep + CLOUD_REST,
        data=json.dumps(row).encode("utf-8"),
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Prefer": "return=minimal",
            "x-wb-webapp-access-key": key,
            "Origin": ep,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return 200 <= resp.status < 300
    except Exception as exc:
        # 不打印配置内容或响应体，只留异常类型
        print("cloud push skipped: %s" % type(exc).__name__, flush=True)
        return False


def dashboard_html():
    try:
        with open(os.path.join(BASE, "index.html"), "r", encoding="utf-8") as f:
            return f.read()
    except Exception:
        return HTML


def db():
    conn = sqlite3.connect(DB_PATH, timeout=10)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    with LOCK, db() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS devices (
            id TEXT PRIMARY KEY,
            first_seen INTEGER NOT NULL,
            last_seen INTEGER NOT NULL,
            pings INTEGER NOT NULL DEFAULT 1,
            app TEXT, code INTEGER, lang TEXT, api INTEGER
        )""")
        c.execute("""CREATE TABLE IF NOT EXISTS events (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            id TEXT NOT NULL,
            ts INTEGER NOT NULL,
            day TEXT NOT NULL,
            kind TEXT, app TEXT, code INTEGER, lang TEXT, api INTEGER
        )""")
        c.execute("CREATE INDEX IF NOT EXISTS idx_events_day ON events(day)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_devices_seen ON devices(last_seen)")


def record(payload):
    """校验 + 落库。返回 (ok, message, totals)"""
    if not isinstance(payload, dict) or payload.get("v") != 1:
        return False, "unsupported payload version", None
    uid = str(payload.get("id", ""))
    if not ID_RE.match(uid):
        return False, "invalid anonymous id", None
    app = str(payload.get("app", ""))[:32]
    lang = str(payload.get("lang", ""))[:8]
    kind = str(payload.get("kind", ""))[:16]
    if kind not in KINDS:
        return False, "invalid kind", None
    try:
        code = int(payload.get("code", 0))
        api = int(payload.get("api", 0))
    except (TypeError, ValueError):
        code, api = 0, 0
    code = max(0, min(code, 1000000))
    api = max(0, min(api, 1000))

    now = int(time.time())
    day = datetime.fromtimestamp(now, timezone.utc).astimezone().strftime("%Y-%m-%d")
    push_row = None
    with LOCK, db() as c:
        row = c.execute("SELECT last_seen, pings FROM devices WHERE id=?", (uid,)).fetchone()
        if row and now - row["last_seen"] < THROTTLE_SEC:
            # 节流窗口内：只更新活跃时间，不写 event、不增加计数
            c.execute("UPDATE devices SET last_seen=?, app=?, code=?, lang=?, api=? WHERE id=?",
                      (now, app, code, lang, api, uid))
        else:
            # 只有真正落一条 event 时才同步云端，避免节流窗口内的重复计数
            push_row = (uid, app, code, lang, api, kind)
            c.execute("INSERT INTO events(id,ts,day,kind,app,code,lang,api) VALUES(?,?,?,?,?,?,?,?)",
                      (uid, now, day, kind, app, code, lang, api))
            if row:
                c.execute("""UPDATE devices SET last_seen=?, pings=pings+1,
                             app=?, code=?, lang=?, api=? WHERE id=?""",
                          (now, app, code, lang, api, uid))
            else:
                c.execute("""INSERT INTO devices(id,first_seen,last_seen,pings,app,code,lang,api)
                             VALUES(?,?,?,1,?,?,?,?)""",
                          (uid, now, now, app, code, lang, api))
        devices = c.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
        pings = c.execute("SELECT COUNT(*) FROM events").fetchone()[0]
    if push_row:
        # 异步推送：云端超时/故障不阻塞客户端，也不改变本地计数
        threading.Thread(target=cloud_push, args=push_row, daemon=True).start()
    return True, "ok", {"devices": devices, "pings": pings}


def stats():
    now = int(time.time())
    since7, since30 = now - 7 * 86400, now - 30 * 86400
    with LOCK, db() as c:
        devices = c.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
        pings = c.execute("SELECT COUNT(*) FROM events").fetchone()[0]
        d7 = c.execute("SELECT COUNT(*) FROM devices WHERE last_seen>=?", (since7,)).fetchone()[0]
        d30 = c.execute("SELECT COUNT(*) FROM devices WHERE last_seen>=?", (since30,)).fetchone()[0]
        versions = [dict(r) for r in c.execute(
            "SELECT app, COUNT(*) AS devices FROM devices GROUP BY app ORDER BY devices DESC LIMIT 20")]
        langs = [dict(r) for r in c.execute(
            "SELECT lang, COUNT(*) AS devices FROM devices GROUP BY lang ORDER BY devices DESC LIMIT 20")]
        days = [dict(r) for r in c.execute(
            "SELECT day, COUNT(DISTINCT id) AS devices, COUNT(*) AS pings FROM events "
            "GROUP BY day ORDER BY day DESC LIMIT 30")]
        recent = [dict(r) for r in c.execute(
            "SELECT day, app, kind, lang FROM events ORDER BY seq DESC LIMIT 10")]
    return {
        "ok": True,
        "generated_at": datetime.fromtimestamp(now, timezone.utc).astimezone().isoformat(timespec="seconds"),
        "devices": devices, "pings": pings,
        "active_7d": d7, "active_30d": d30,
        "versions": versions, "langs": langs, "days": days, "recent": recent,
    }


HTML = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DuoLauncher 匿名统计</title>
<style>
:root{color-scheme:light dark}
body{font:15px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;max-width:760px;margin:40px auto;padding:0 20px}
h1{font-size:22px} .sub{opacity:.65;font-size:13px;margin-bottom:28px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:28px}
.card{border:1px solid rgba(128,128,128,.3);border-radius:14px;padding:16px}
.card b{display:block;font-size:28px;line-height:1.2;margin-bottom:4px}
.card span{font-size:12px;opacity:.65}
table{width:100%;border-collapse:collapse;font-size:13px;margin-bottom:24px}
th,td{text-align:left;padding:7px 8px;border-bottom:1px solid rgba(128,128,128,.2)}
th{font-size:12px;opacity:.6;font-weight:600}
h2{font-size:16px;margin:26px 0 8px} code{background:rgba(128,128,128,.15);padding:2px 5px;border-radius:4px}
footer{margin-top:36px;font-size:12px;opacity:.6}
</style></head><body>
<h1>DuoLauncher 匿名安装统计</h1>
<div class="sub">仅统计随机匿名 ID 的数量，不采集设备标识、IP、位置或任何个人信息 · 数据每 60 秒刷新</div>
<div class="cards" id="cards"></div>
<h2>版本分布</h2><table id="versions"></table>
<h2>界面语言</h2><table id="langs"></table>
<h2>最近 30 天</h2><table id="days"></table>
<footer>关闭统计：启动器 设置 → 隐私与统计 → 匿名使用统计。原始数据可用 <code>/export</code> 备份。</footer>
<script>
function table(el,head,rows){document.getElementById(el).innerHTML='<tr>'+head.map(x=>'<th>'+x+'</th>').join('')+'</tr>'+
 rows.map(r=>'<tr>'+r.map(x=>'<td>'+(x??'')+'</td>').join('')+'</tr>').join('')||'<tr><td colspan="9">暂无数据</td></tr>';}
async function load(){
 try{const d=await (await fetch('/stats')).json();
  document.getElementById('cards').innerHTML=[
   ['累计安装设备',d.devices],['近 7 天活跃',d.active_7d],['近 30 天活跃',d.active_30d],['累计上报次数',d.pings]
  ].map(([k,v])=>'<div class="card"><b>'+v+'</b><span>'+k+'</span></div>').join('');
  table('versions',['版本','设备数'],d.versions.map(x=>[x.app||'未知',x.devices]));
  table('langs',['语言','设备数'],d.langs.map(x=>[x.lang||'未知',x.devices]));
  table('days',['日期','活跃设备','上报次数'],d.days.map(x=>[x.day,x.devices,x.pings]));
  document.querySelector('.sub').lastChild.textContent=' · 更新于 '+d.generated_at;
 }catch(e){}
}
load();setInterval(load,60000);
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "DuoStats/1.0"

    def log_message(self, fmt, *args):
        # 访问日志不记录客户端 IP，只留方法与路径
        try:
            print("%s %s" % (self.command, urlparse(self.path).path), flush=True)
        except Exception:
            pass

    def send(self, code, body, ctype="application/json; charset=utf-8"):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send(204, "")

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/") or "/"
        if path == "/health":
            return self.send(200, json.dumps({"ok": True, "cloud": bool(CLOUD.get("endpoint"))}))
        if path == "/stats":
            return self.send(200, json.dumps(stats(), ensure_ascii=False))
        if path == "/cloud-config.js":
            body = "window.publicConfig=%s;" % json.dumps({
                k: CLOUD.get(k, "") for k in ("endpoint", "oauthRelayBaseUrl", "publishableKey")
            }, ensure_ascii=False)
            return self.send(200, body, "application/javascript; charset=utf-8")
        if path in ("/", "/index.html"):
            return self.send(200, dashboard_html(), "text/html; charset=utf-8")
        if path == "/export":
            with LOCK, db() as c:
                rows = [dict(r) for r in c.execute("SELECT * FROM events ORDER BY seq")]
            body = "\n".join(json.dumps(r, ensure_ascii=False) for r in rows) + "\n"
            return self.send(200, body, "application/x-ndjson; charset=utf-8")
        self.send(404, json.dumps({"ok": False, "error": "not found"}))

    def do_POST(self):
        path = urlparse(self.path).path.rstrip("/") or "/"
        if path not in ("/ping", "/api/ping"):
            return self.send(404, json.dumps({"ok": False, "error": "not found"}))
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > MAX_BODY:
            return self.send(413, json.dumps({"ok": False, "error": "bad size"}))
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return self.send(400, json.dumps({"ok": False, "error": "bad json"}))
        ok, msg, totals = record(payload)
        if not ok:
            return self.send(400, json.dumps({"ok": False, "error": msg}))
        out = {"ok": True}
        out.update(totals or {})
        self.send(200, json.dumps(out))


if __name__ == "__main__":
    init_db()
    print("DuoLauncher stats server on 0.0.0.0:%s, db=%s, cloud=%s"
          % (PORT, DB_PATH, "on" if CLOUD.get("endpoint") else "off"), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
