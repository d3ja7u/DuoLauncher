# DuoLauncher 匿名安装统计接收端

零依赖（仅 Python 标准库）的统计服务，用于接收启动器「匿名使用统计」的上报。
自 v1.5.6 起可**同时**写入 WorkBuddy 云数据库，也可以部署在家里爱快软路由的 Docker 里完全自托管。

## 采集什么

启动器只发送：**随机生成的匿名 ID**、应用版本号 / versionCode、界面语言、Android API 等级、上报类型（首次安装 / 版本升级 / 每日活跃）。

**不发送、不存储**：IP 地址、设备型号、序列号、IMEI、Android ID、广告 ID、账号、通讯录、位置、已安装应用列表。服务端访问日志也刻意去掉了客户端 IP，只保留请求路径。

匿名 ID 是本地随机 UUID，卸载重装或用户在设置里关闭统计后重新开启都会生成新的，不与任何身份关联。

## 两种数据落点

| 形态 | 数据存哪 | 适合 | 额度 / 成本 |
|---|---|---|---|
| 托管云服务 | 云数据库 `stats_pings`（本地 SQLite 同时留副本） | 零运维、换设备不丢数据 | 体验版 **10 个应用 / 单应用 5,000 资源点·月**，按月刷新，用尽自动暂停、不扣费 |
| 家里自托管 | 本地 SQLite（`CLOUD_MODE=off` 可完全不联云端） | 数据完全在自己手里、无额度上限 | 无平台费用，需自己管 HTTPS、备份和域名 |

> 云服务体验版在官网标注为**限时免费**，官方没有"永久免费无限用"的承诺。因此长期跑统计，家里 Docker 自托管 + 云端只作可选备份，是更稳妥的组合。

## 云服务接入（已完成）

| 部件 | 说明 |
|---|---|
| 应用 | `DuoLauncher 匿名统计`（`wbapp_XbUMmu9mtBzj4MwApgfuiT`），已激活，`normal` / `assigned` |
| 数据表 | `stats_pings`：`anon_id / app / code / lang / api / kind / created_at`，带格式与枚举 CHECK 约束 |
| 权限 | 已开启 RLS；`anon`、`authenticated` 只允许 `INSERT` 和 `SELECT`，**没有** UPDATE / DELETE 策略 |
| 聚合 | `stats_summary()` 只返回聚合数字与分布，看板拿不到任何原始匿名 ID |
| 看板 | `index.html` 使用官方 CDN SDK，从 `cloud-config.js` 的 `publicConfig` 初始化；云端失败自动回退本地 `/stats` |
| 接收端 | `server.py` 的 `/ping` 异步双写到云端；云端超时/故障静默跳过，不影响本地记录与客户端返回 |
| 配置 | `cloud-config.json` / `.js` 是云服务 `publicConfig` 生成物，只含可公开应用标识，不含密钥 |

`cloud_push()` 是**服务端 → 数据面**的桥接：浏览器端有官方 SDK，Android 原生与 Python 服务端没有对应 SDK 形态，所以沿用 SDK 注入的 `x-wb-webapp-access-key` 头访问同一数据面。前端看板严格使用官方 SDK，没有手写数据面请求。

## 端点

| 端点 | 用途 |
|---|---|
| `POST /ping` | 上报（也兼容 `/api/ping`），body 见 `server.py` 顶部注释 |
| `GET /` | 统计看板（云端优先，本地兜底）；`?selftest=1` 显示云端连通性自检按钮 |
| `GET /cloud-config.js` | 看板用的 `publicConfig` |
| `GET /stats` | 本地 SQLite 聚合 JSON |
| `GET /export` | 原始记录 JSONL，用于备份或自行分析 |
| `GET /health` | 健康检查，含 `cloud` 开关状态 |

服务端对同一匿名 ID 在 60 秒内的重复上报只更新活跃时间、不重复计数。

## 本地运行 / 验证

```bash
python3 server.py                      # 默认 3000
PORT=8080 STATS_DATA_DIR=/var/lib/duo-stats python3 server.py
CLOUD_MODE=off python3 server.py       # 纯本地模式
```

```bash
curl http://127.0.0.1:3000/health
curl -X POST http://127.0.0.1:3000/ping -H 'Content-Type: application/json' \
  -d '{"v":1,"id":"selftest-0001","app":"1.5.6","code":143,"lang":"zh","api":33,"kind":"test"}'
curl http://127.0.0.1:3000/stats
```

## Docker 部署（爱快 / 任意 Docker 主机）

```bash
cp .env.example .env
docker compose up -d                          # 仅接收端
docker compose --profile tunnel up -d          # 接收端 + Cloudflare Tunnel
```

爱快的 Docker 插件若不支持 compose，可直接：

```bash
docker build -t duolauncher-stats:local .
docker run -d --name duolauncher-stats --restart unless-stopped \
  -p 3000:3000 -v /docker/duolauncher-stats/data:/data \
  -e CLOUD_MODE=off duolauncher-stats:local
```

数据落在挂载卷 `./data/stats.db`，定期备份该文件即可。

### 爱快软路由上的准备步骤

1. 固件 **3.5.0 及以上**（4.0 亦可），绑定爱快云账号 → 插件市场安装 **Docker 插件**；4.0 的应用市场容器应用同样基于 Docker。
2. 系统设置 → 磁盘管理 → 磁盘分区：准备一个**「普通存储」分区**（建议 ≥5 GB，如挂载 `/docker`），Docker 服务设置里选中它；国内拉取镜像慢可配镜像加速。
3. 把本目录放到 `/docker/duolauncher-stats`，按上面的 compose 或 `docker run` 启动。
4. 按需选择下面一种外网暴露方式，再在启动器里改上报地址。

### 域名 + 公网：三条路线

| 路线 | 做法 | 适用 |
|---|---|---|
| **A. Cloudflare Tunnel（推荐）** | 在 CF Zero Trust 建 Tunnel，Public Hostname 指向 `http://stats:3000`，把 token 填进 `.env` 的 `TUNNEL_TOKEN`，`--profile tunnel` 启动 | 家宽 80/443 被封、没有可用入站端口；自动 HTTPS，无需端口映射和 DDNS |
| **B. 爱快端口映射 + DDNS** | 爱快「动态域名」绑定自己的域名；网络设置 → 端口映射，外网 `8443` → 内网 `主机IP:3000`；前面挂 Caddy/Nginx 终止 TLS | 有可用公网入站端口，想自己掌控证书 |
| **C. 纯局域网** | 不映射外网，`CLOUD_MODE=off`，只在家里 WiFi 下上报 | 只要本地留档，不在乎外网数据 |

Caddy 方案 B 的最小示例（用自有证书，避免家宽 80 端口被封导致 HTTP-01 签发失败）：

```caddyfile
stats.example.com:8443 {
    tls /certs/fullchain.pem /certs/privkey.pem
    reverse_proxy 127.0.0.1:3000
}
```

> **必须 HTTPS**：Android 默认禁止明文 HTTP，本应用也没有开启 `usesCleartextTraffic`。上报地址和看板地址都要写成 `https://...`，否则客户端会静默失败。

### 客户端怎么填

启动器 **设置 → 隐私与统计 → 统计上报地址**：

| 部署方式 | 上报地址 | 看板地址 |
|---|---|---|
| 托管云服务（默认） | `https://duolauncher-stats.app.workbuddy.host/ping?sharecode=AKJCYWSUYS` | `https://duolauncher-stats.app.workbuddy.host/?sharecode=AKJCYWSUYS` |
| 家里 Cloudflare Tunnel | `https://stats.你的域名/ping` | `https://stats.你的域名/` |
| 家里端口映射 | `https://stats.你的域名:8443/ping` | `https://stats.你的域名:8443/` |

自托管时**不需要** `sharecode` 参数。地址留空则完全不上报。客户端不需要升级，改地址即可切换后端。

