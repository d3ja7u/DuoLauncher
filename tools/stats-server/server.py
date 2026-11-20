#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DuoLauncher 匿名安装统计接收端（零依赖，仅 Python 标准库）。

只接收「随机匿名 ID + 应用版本 + 界面语言 + Android API 等级 + 通用机型名 + 上报类型」，
不接收也不落盘 IP、序列号、IMEI、Android ID、广告 ID、通讯录、位置等任何可识别信息。
机型仅取 Build.MODEL（如 Titan 2 Elite），是通用型号名，不构成对个人设备的唯一识别。

端点：
  POST /ping             上报一条记录（body 见下）
  GET  /stats            本地 SQLite 聚合统计 JSON
  GET  /                 统计看板（优先读取 index.html，使用官方云服务 SDK）
  GET  /cloud-config.js  看板用的 publicConfig（可公开的应用标识）
  GET  /export           原始记录 JSONL（备份用）
  GET  /health           健康检查（含云服务状态）

POST /ping 请求体示例：
  {"v":1,"id":"c0a8...","app":"1.5.6","code":143,"lang":"zh","api":33,"model":"Titan 2 Elite","kind":"install"}

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
# 自托管看板的访问口令（?sharecode=xxx）。留空 = 不校验；托管云服务不需要，平台自带分享码
SHARECODE = os.environ.get("SHARECODE", "").strip()
# 自托管「数据管理」页的独立管理口令。留空 = 关闭导出/导入/清理接口。
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "").strip()


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
        # 纯本地模式：连应用标识一起清掉，看板不会尝试初始化云 SDK
        cfg["endpoint"] = ""
        cfg["publishableKey"] = ""
    return cfg


CLOUD = load_cloud_config()


def cloud_push(uid, app, code, lang, api, model, kind):
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
           "lang": lang, "api": api, "model": model, "kind": kind}
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
            app TEXT, code INTEGER, lang TEXT, api INTEGER, model TEXT
        )""")
        c.execute("""CREATE TABLE IF NOT EXISTS events (
            seq INTEGER PRIMARY KEY AUTOINCREMENT,
            id TEXT NOT NULL,
            ts INTEGER NOT NULL,
            day TEXT NOT NULL,
            kind TEXT, app TEXT, code INTEGER, lang TEXT, api INTEGER, model TEXT
        )""")
        # 老库升级：早期表没有 model 列，补齐后旧记录该列为空，不影响聚合。
        for tbl in ("devices", "events"):
            try:
                c.execute("ALTER TABLE %s ADD COLUMN model TEXT" % tbl)
            except Exception:
                pass
        c.execute("CREATE INDEX IF NOT EXISTS idx_events_day ON events(day)")
        c.execute("CREATE INDEX IF NOT EXISTS idx_devices_seen ON devices(last_seen)")
        try:
            c.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_events_uniq ON events(id,ts,kind)")
        except Exception:
            pass


def record(payload):
    """校验 + 落库。返回 (ok, message, totals)"""
    if not isinstance(payload, dict) or payload.get("v") != 1:
        return False, "unsupported payload version", None
    uid = str(payload.get("id", ""))
    if not ID_RE.match(uid):
        return False, "invalid anonymous id", None
    app = str(payload.get("app", ""))[:32]
    lang = str(payload.get("lang", ""))[:8]
    model = str(payload.get("model", ""))[:48]
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
            c.execute("UPDATE devices SET last_seen=?, app=?, code=?, lang=?, api=?, model=? WHERE id=?",
                      (now, app, code, lang, api, model, uid))
        else:
            # 只有真正落一条 event 时才同步云端，避免节流窗口内的重复计数
            push_row = (uid, app, code, lang, api, model, kind)
            c.execute("INSERT INTO events(id,ts,day,kind,app,code,lang,api,model) VALUES(?,?,?,?,?,?,?,?,?)",
                      (uid, now, day, kind, app, code, lang, api, model))
            if row:
                c.execute("""UPDATE devices SET last_seen=?, pings=pings+1,
                             app=?, code=?, lang=?, api=?, model=? WHERE id=?""",
                          (now, app, code, lang, api, model, uid))
            else:
                c.execute("""INSERT INTO devices(id,first_seen,last_seen,pings,app,code,lang,api,model)
                             VALUES(?,?,?,1,?,?,?,?,?)""",
                          (uid, now, now, app, code, lang, api, model))
        devices = c.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
        pings = c.execute("SELECT COUNT(*) FROM events").fetchone()[0]
    if push_row:
        # 异步推送：云端超时/故障不阻塞客户端，也不改变本地计数
        threading.Thread(target=cloud_push, args=push_row, daemon=True).start()
    return True, "ok", {"devices": devices, "pings": pings}


def ver_key(app):
    """版本号排序键：1.5.16 → (1, 5, 16)；解析不了的一律排最后。"""
    parts = []
    for seg in str(app or "").split("."):
        parts.append(int(seg) if seg.isdigit() else -1)
    return tuple(parts) if parts and parts[0] >= 0 else (-1,)


def stats():
    now = int(time.time())
    since7, since30 = now - 7 * 86400, now - 30 * 86400
    with LOCK, db() as c:
        devices = c.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
        pings = c.execute("SELECT COUNT(*) FROM events").fetchone()[0]
        d7 = c.execute("SELECT COUNT(*) FROM devices WHERE last_seen>=?", (since7,)).fetchone()[0]
        d30 = c.execute("SELECT COUNT(*) FROM devices WHERE last_seen>=?", (since30,)).fetchone()[0]
        # 版本分布：用「设备当前版本」定设备数，用事件定上报次数。
        # 早先的实现在这里加了 LIMIT 8，导致刚发布的新版本（设备少）被挤出榜单，
        # 明明有设备在跑 1.5.16 却看不到——现在不截断，且按版本号倒序。
        dev_cnt, ev_cnt = {}, {}
        for r in c.execute("SELECT app, COUNT(*) AS n FROM devices WHERE app<>'' GROUP BY app"):
            dev_cnt[r["app"]] = r["n"]
        for r in c.execute("SELECT app, COUNT(*) AS n FROM events WHERE app<>'' GROUP BY app"):
            ev_cnt[r["app"]] = r["n"]
        versions = [
            {"app": app, "devices": dev_cnt.get(app, 0), "n": ev_cnt.get(app, 0)}
            for app in sorted(set(dev_cnt) | set(ev_cnt), key=ver_key, reverse=True)
        ]
        langs = [dict(r) for r in c.execute(
            "SELECT lang, COUNT(*) AS devices FROM devices GROUP BY lang ORDER BY devices DESC")]
        apis = [dict(r) for r in c.execute(
            "SELECT api, COUNT(*) AS devices FROM devices GROUP BY api ORDER BY api DESC")]
        models = [dict(r) for r in c.execute(
            "SELECT COALESCE(NULLIF(model,''),'-') AS model, COUNT(*) AS devices "
            "FROM devices GROUP BY 1 ORDER BY devices DESC LIMIT 30")]
        days = [dict(r) for r in c.execute(
            "SELECT day, COUNT(DISTINCT id) AS devices, COUNT(*) AS pings FROM events "
            "GROUP BY day ORDER BY day DESC LIMIT 30")]
        recent = [dict(r) for r in c.execute(
            "SELECT day, app, kind, lang FROM events ORDER BY seq DESC LIMIT 10")]
        # 设备明细：匿名 ID 只给前 8 位，无法反推真实身份
        tracks = {}
        for r in c.execute("SELECT id, ts, app, kind FROM events ORDER BY seq"):
            tracks.setdefault(r["id"], []).append(
                {"ts": r["ts"], "app": r["app"], "kind": r["kind"]})
        device_list = []
        for r in c.execute(
                "SELECT id, first_seen, last_seen, pings, app, code, lang, api, model FROM devices "
                "ORDER BY last_seen DESC"):
            device_list.append({
                "id": r["id"][:8], "first": r["first_seen"], "last": r["last_seen"],
                "pings": r["pings"], "ver": r["app"], "code": r["code"],
                "lang": r["lang"], "api": r["api"], "model": r["model"] or "",
                "track": tracks.get(r["id"], [])[-12:],
            })
        last = c.execute("SELECT MAX(last_seen) FROM devices").fetchone()[0] or 0
    return {
        "ok": True,
        "generated_at": datetime.fromtimestamp(now, timezone.utc).astimezone().isoformat(timespec="seconds"),
        "devices": devices, "pings": pings,
        "active_7d": d7, "active_30d": d30, "last": last,
        "versions": versions, "langs": langs, "apis": apis, "models": models,
        "days": days, "recent": recent, "device_list": device_list,
    }


def rebuild_devices():
    """按 events 重建 devices；导入/清理后调用，保证设备数与明细一致。"""
    with LOCK, db() as c:
        c.execute("DELETE FROM devices")
        for r in c.execute(
                "SELECT id, ts, app, code, lang, api, model FROM events ORDER BY ts, seq"):
            row = c.execute("SELECT pings FROM devices WHERE id=?", (r["id"],)).fetchone()
            if row:
                c.execute("""UPDATE devices SET last_seen=?, pings=pings+1,
                             app=?, code=?, lang=?, api=?, model=? WHERE id=?""",
                          (r["ts"], r["app"], r["code"], r["lang"], r["api"],
                           r["model"], r["id"]))
            else:
                c.execute("""INSERT INTO devices
                             (id, first_seen, last_seen, pings, app, code, lang, api, model)
                             VALUES(?,?,?,1,?,?,?,?,?)""",
                          (r["id"], r["ts"], r["ts"], r["app"], r["code"],
                           r["lang"], r["api"], r["model"]))


def admin_ok(query):
    """管理口令校验。未设置 ADMIN_PASSWORD 时一律拒绝，避免误开写接口。"""
    if not ADMIN_PASSWORD:
        return False
    q = dict(p.split("=", 1) for p in (query or "").split("&") if "=" in p)
    try:
        from urllib.parse import unquote_plus
        return unquote_plus(q.get("password", "")) == ADMIN_PASSWORD
    except Exception:
        return False


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
<div class="sub">仅统计随机匿名 ID 的数量与通用机型名，不采集唯一标识、IP、位置或任何个人信息 · 手动刷新（已停止自动轮询）<span id="ts"></span></div>
<div class="cards" id="cards"></div>
<h2>版本分布</h2><table id="versions"></table>
<h2>界面语言</h2><table id="langs"></table>
<h2>设备机型</h2><table id="models"></table>
<h2>最近 30 天</h2><table id="days"></table>
<footer>关闭统计：启动器 设置 → 隐私与统计 → 匿名使用统计。原始数据可用 <code>/export</code> 备份。</footer>
<script>
function table(el,head,rows){document.getElementById(el).innerHTML='<tr>'+head.map(x=>'<th>'+x+'</th>').join('')+'</tr>'+
 rows.map(r=>'<tr>'+r.map(x=>'<td>'+(x??'')+'</td>').join('')+'</tr>').join('')||'<tr><td colspan="9">暂无数据</td></tr>';}
async function load(){
 try{
  const sc=new URLSearchParams(location.search).get('sharecode')||'';
  const res=await fetch('/stats'+(sc?'?sharecode='+encodeURIComponent(sc):''));
  if(!res.ok){
   document.getElementById('cards').innerHTML='<div class="card"><b>'+res.status+'</b><span>口令无效或缺失，请在地址后加 ?sharecode=…</span></div>';
   return;
  }
  const d=await res.json();
  document.getElementById('cards').innerHTML=[
   ['累计安装设备',d.devices],['近 7 天活跃',d.active_7d],['近 30 天活跃',d.active_30d],['累计上报次数',d.pings]
  ].map(([k,v])=>'<div class="card"><b>'+v+'</b><span>'+k+'</span></div>').join('');
  table('versions',['版本','设备数'],d.versions.map(x=>[x.app||'未知',x.devices]));
  table('langs',['语言','设备数'],d.langs.map(x=>[x.lang||'未知',x.devices]));
  table('models',['机型','设备数'],(d.models||[]).map(x=>[x.model||'未知',x.devices]));
  table('days',['日期','活跃设备','上报次数'],d.days.map(x=>[x.day,x.devices,x.pings]));
  document.getElementById('ts').textContent=' · 更新于 '+d.generated_at;
 }catch(e){document.getElementById('cards').innerHTML='<div class="card"><b>!</b><span>加载失败</span></div>';}
}
load();
</script></body></html>"""


ADMIN_HTML = """<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>DuoLauncher 数据管理</title><style>
:root{color-scheme:dark}*{box-sizing:border-box}
body{margin:0;padding:24px 20px 40px;background:#0b0d12;color:#e8ecf3;
font:14px/1.6 -apple-system,system-ui,"PingFang SC",sans-serif}
h1{font-size:18px;margin:0 0 6px}h2{font-size:15px;margin:24px 0 8px;color:#cdd7e5}
.sub,hint{color:#8b95a7;font-size:12px;margin-bottom:18px}
section{background:#151a23;border:1px solid #232b38;border-radius:12px;padding:14px 16px;margin-bottom:14px}
input[type=password],input[type=file]{width:100%;font:inherit;font-size:13px;padding:9px 12px;
border-radius:8px;border:1px solid #2c3543;background:#0f141c;color:#e8ecf3}
button{font:inherit;font-size:13px;padding:8px 15px;border-radius:8px;border:1px solid #2c3543;
background:#1a212c;color:#e8ecf3;cursor:pointer;margin-right:8px}
button:hover{background:#222b38}button.primary{background:#1f6feb;border-color:#1f6feb}
.ok{color:#63c08c;font-size:12px;white-space:pre-line}.err{color:#e0913f;font-size:12px}
a{color:#7fb2ff}footer{margin-top:24px;font-size:12px;color:#7f8b9e}
</style></head><body>
<h1>DuoLauncher 数据管理</h1><div class="sub">导出备份 · 导入回灌 · 清理测试数据 · 需管理口令 · <a href="./">← 返回看板</a></div>
<section><h2>管理口令</h2><input type="password" id="pw" placeholder="管理口令" autocomplete="current-password"></section>
<section><h2>导出备份</h2><div class="hint">导出全部原始记录（JSONL），导入前建议先备份。</div>
<button id="exp">下载备份</button><span id="msg-exp"></span></section>
<section><h2>导入回灌</h2><div class="hint">按匿名 ID + 时间戳去重，保留原时间。</div>
<input type="file" id="file" accept=".ndjson,.jsonl,.json,.txt"><br><br>
<button class="primary" id="imp">开始导入</button><span id="msg-imp"></span></section>
<section><h2>清理数据</h2><div class="hint">按匿名 ID 或上报类型删除，不可撤销；清理后自动重建设备表。</div>
<input type="text" id="ids" placeholder="匿名 ID，多个用逗号分隔"><br><br>
<input type="text" id="kinds" placeholder="类型，如 test，多个用逗号分隔"><br><br>
<button id="purge">删除</button><span id="msg-purge"></span></section>
<footer>返回 <a href="./">统计看板</a> · 导入/清理前会自动备份数据库。</footer>
<script>
var pw=document.getElementById('pw');
function val(id){return document.getElementById(id).value.trim()}
function say(id,m,ok){var e=document.getElementById(id);e.textContent=m;e.className=ok?'ok':'err'}
function pass(){var v=pw.value.trim();if(!v){say('msg-exp','请先填写管理口令',false);return ''}return v}
document.getElementById('exp').onclick=function(){var p=pass();if(!p)return;
 fetch('./admin/export?password='+encodeURIComponent(p),{cache:'no-store'}).then(function(r){
  if(r.status===401)throw new Error('口令不正确');if(!r.ok)throw new Error('HTTP '+r.status);
  return r.blob().then(function(b){return{b:b,n:r.headers.get('X-Row-Count')}})
 }).then(function(o){var a=document.createElement('a');a.href=URL.createObjectURL(o.b);
  a.download='duostats-export.ndjson';document.body.appendChild(a);a.click();a.remove();
  setTimeout(function(){URL.revokeObjectURL(a.href)},4000);
  say('msg-exp','已导出 '+(o.n||'?')+' 行',true)
 }).catch(function(e){say('msg-exp',e.message,false)})};
document.getElementById('imp').onclick=function(){var p=pass();if(!p)return;
 var f=document.getElementById('file').files[0];if(!f){say('msg-imp','请先选择文件',false);return}
 var b=this;b.disabled=true;say('msg-imp','导入中…',true);f.text().then(function(t){
  return fetch('./admin/import?password='+encodeURIComponent(p),{method:'POST',
   headers:{'Content-Type':'application/x-ndjson; charset=utf-8'},body:t})
 }).then(function(r){if(r.status===401)throw new Error('口令不正确');if(!r.ok)return r.text().then(function(t){throw new Error('HTTP '+r.status+' '+t.slice(0,100))});return r.json()})
 .then(function(d){say('msg-imp','新增 '+d.inserted+'，跳过 '+d.skipped+'，无效 '+d.invalid+'；记录 '+d.events_before+'→'+d.events+'，设备 '+d.devices,true)})
 .catch(function(e){say('msg-imp',e.message,false)}).then(function(){b.disabled=false})};
document.getElementById('purge').onclick=function(){var p=pass();if(!p)return;
 var ids=val('ids').split(',').map(function(x){return x.trim()}).filter(Boolean);
 var kinds=val('kinds').split(',').map(function(x){return x.trim()}).filter(Boolean);
 if(!ids.length&&!kinds.length){say('msg-purge','请填写 ID 或类型',false);return}
 if(!confirm('确认删除匹配的记录？此操作不可撤销。'))return;
 fetch('./admin/purge?password='+encodeURIComponent(p),{method:'POST',
  headers:{'Content-Type':'application/json'},body:JSON.stringify({ids:ids,kinds:kinds})})
 .then(function(r){if(r.status===401)throw new Error('口令不正确');if(!r.ok)return r.text().then(function(t){throw new Error('HTTP '+r.status+' '+t.slice(0,100))});return r.json()})
 .then(function(d){say('msg-purge','已删除 '+d.deleted+' 条；剩余 '+d.events+' 条 / '+d.devices+' 台',true)})
 .catch(function(e){say('msg-purge',e.message,false)})};
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    server_version = "DuoStats/1.0"

    def log_message(self, fmt, *args):
        # 访问日志不记录客户端 IP，只留方法与路径
        try:
            print("%s %s" % (self.command, urlparse(self.path).path), flush=True)
        except Exception:
            pass

    def send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        data = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        for k, v in (extra or {}).items():
            self.send_header(k, str(v))
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send(204, "")

    def do_GET(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        # 自托管时给「看板 + 明细」加一道门槛（SHARECODE 留空则不启用）。
        # 上报端点 /ping 永不加锁，否则已发布的客户端会全部上报失败。
        if SHARECODE and path in ("/stats", "/export"):
            from urllib.parse import unquote_plus
            q = dict(p.split("=", 1) for p in (u.query or "").split("&") if "=" in p)
            if unquote_plus(q.get("sharecode", "")) != SHARECODE:
                return self.send(403, json.dumps({"error": "forbidden"}))
        if path == "/health":
            return self.send(200, json.dumps({"ok": True, "cloud": bool(CLOUD.get("endpoint"))}))
        if path == "/admin":
            return self.send(200, ADMIN_HTML, "text/html; charset=utf-8")
        if path == "/admin/export":
            if not admin_ok(u.query):
                return self.send(401, json.dumps({"error": "unauthorized"}))
            with LOCK, db() as c:
                rows = [dict(r) for r in c.execute(
                    "SELECT seq,id,ts,day,kind,app,code,lang,api,model FROM events ORDER BY seq")]
            body = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
            return self.send(200, body, "application/x-ndjson; charset=utf-8",
                             {"X-Row-Count": str(len(rows))})
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

    def _read_body(self, limit):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        if length <= 0 or length > limit:
            return None
        return self.rfile.read(length).decode("utf-8", "replace")

    def _backup_db(self):
        try:
            import shutil
            shutil.copyfile(DB_PATH, "%s.bak-%s" % (
                DB_PATH, datetime.now().strftime("%Y%m%d-%H%M%S")))
        except Exception:
            pass

    def _admin_import(self, u):
        if not admin_ok(u.query):
            return self.send(401, json.dumps({"error": "unauthorized"}))
        raw = self._read_body(16 * 1024 * 1024)
        if raw is None:
            return self.send(413, json.dumps({"error": "bad size"}))
        rows = []
        try:
            txt = raw.strip()
            if txt.startswith("["):
                rows = json.loads(txt)
            else:
                rows = [json.loads(x) for x in txt.splitlines() if x.strip()]
        except Exception:
            return self.send(400, json.dumps({"error": "bad json"}))
        if not isinstance(rows, list):
            return self.send(400, json.dumps({"error": "rows must be array"}))
        self._backup_db()
        inserted = skipped = invalid = 0
        with LOCK, db() as c:
            before = c.execute("SELECT COUNT(*) FROM events").fetchone()[0]
            for r in rows:
                if not isinstance(r, dict):
                    invalid += 1
                    continue
                uid = str(r.get("id", "")).strip()
                try:
                    ts = int(r.get("ts", 0))
                except (TypeError, ValueError):
                    invalid += 1
                    continue
                if not uid or ts <= 0:
                    invalid += 1
                    continue
                try:
                    cur = c.execute(
                        "INSERT OR IGNORE INTO events"
                        "(id,ts,day,kind,app,code,lang,api,model)"
                        " VALUES(?,?,?,?,?,?,?,?,?)",
                        (uid, ts, str(r.get("day", ""))[:10] or datetime.fromtimestamp(
                            ts, timezone.utc).astimezone().strftime("%Y-%m-%d"),
                         str(r.get("kind", ""))[:16], str(r.get("app", ""))[:32],
                         int(r.get("code", 0) or 0), str(r.get("lang", ""))[:8],
                         int(r.get("api", 0) or 0), str(r.get("model", ""))[:48]))
                except Exception:
                    invalid += 1
                    continue
                if cur.rowcount:
                    inserted += 1
                else:
                    skipped += 1
        rebuild_devices()
        with LOCK, db() as c:
            events = c.execute("SELECT COUNT(*) FROM events").fetchone()[0]
            devices = c.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
            days = c.execute("SELECT COUNT(DISTINCT day) FROM events").fetchone()[0]
        return self.send(200, json.dumps({
            "inserted": inserted, "skipped": skipped, "invalid": invalid,
            "events_before": before, "events": events, "devices": devices, "days": days}))

    def _admin_purge(self, u):
        if not admin_ok(u.query):
            return self.send(401, json.dumps({"error": "unauthorized"}))
        raw = self._read_body(1024 * 1024)
        try:
            d = json.loads(raw or "{}")
        except Exception:
            return self.send(400, json.dumps({"error": "bad json"}))
        ids = [str(x)[:64] for x in (d.get("ids") or []) if str(x).strip()]
        kinds = [str(x)[:16] for x in (d.get("kinds") or []) if str(x).strip()]
        if not ids and not kinds:
            return self.send(400, json.dumps({"error": "nothing to delete"}))
        self._backup_db()
        clauses, args = [], []
        if ids:
            clauses.append("id IN (%s)" % ",".join("?" * len(ids)))
            args.extend(ids)
        if kinds:
            clauses.append("kind IN (%s)" % ",".join("?" * len(kinds)))
            args.extend(kinds)
        with LOCK, db() as c:
            cur = c.execute("DELETE FROM events WHERE " + " OR ".join(clauses), tuple(args))
            deleted = cur.rowcount
        rebuild_devices()
        with LOCK, db() as c:
            events = c.execute("SELECT COUNT(*) FROM events").fetchone()[0]
            devices = c.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
        return self.send(200, json.dumps({"deleted": deleted, "events": events, "devices": devices}))

    def do_POST(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        if path == "/admin/import":
            return self._admin_import(u)
        if path == "/admin/purge":
            return self._admin_purge(u)
        if path not in ("/ping", "/api/ping"):
            return self.send(404, json.dumps({"ok": False, "error": "not found"}))
        raw = self._read_body(MAX_BODY)
        if raw is None:
            return self.send(413, json.dumps({"ok": False, "error": "bad size"}))
        try:
            payload = json.loads(raw)
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
