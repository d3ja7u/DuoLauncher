# DuoLauncher 匿名安装统计接收端

零依赖（仅 Python 标准库）的统计服务，用于接收启动器「匿名使用统计」的上报。

## 采集什么

启动器只发送：**随机生成的匿名 ID**、应用版本号 / versionCode、界面语言、Android API 等级、上报类型（首次安装 / 版本升级 / 每日活跃）。

**不发送、不存储**：IP 地址、设备型号、序列号、IMEI、Android ID、广告 ID、账号、通讯录、位置、已安装应用列表。服务端访问日志也刻意去掉了客户端 IP，只保留请求路径。

匿名 ID 是本地随机 UUID，卸载重装或用户在设置里关闭统计后重新开启都会生成新的，不与任何身份关联。

## 运行

```bash
python3 server.py                 # 默认监听 3000
PORT=8080 STATS_DATA_DIR=/var/lib/duo-stats python3 server.py
```

生产环境请用 Nginx / Caddy 反向代理并启用 HTTPS（Android 默认禁止明文 HTTP）：

```nginx
location / { proxy_pass http://127.0.0.1:3000; }
```

用 systemd / supervisord / Docker 保持进程常驻即可。数据保存在 `STATS_DATA_DIR/stats.db`（SQLite），请定期备份该文件或调用 `/export` 导出 JSONL。

## 端点

| 端点 | 用途 |
|---|---|
| `POST /ping` | 上报（也兼容 `/api/ping`），body 见 `server.py` 顶部注释 |
| `GET /stats` | 聚合统计 JSON：累计设备数、7/30 天活跃、版本与语言分布、每日趋势 |
| `GET /` | 人看的统计页面（每 60 秒自动刷新） |
| `GET /export` | 原始记录 JSONL，用于备份或自行分析 |
| `GET /health` | 健康检查 |

服务端对同一匿名 ID 在 60 秒内的重复上报只更新活跃时间、不重复计数，避免启动器频繁回到前台造成虚高。

## 换用自建服务

启动器 **设置 → 隐私与统计 → 统计上报地址** 可改成任意兼容 `POST /ping` 的地址；留空则完全不上报。把本目录部署到自己的服务器后，把地址填进去即可，客户端不需要升级。

> 通过 WorkBuddy「发布为应用」部署的实例运行在临时沙箱中，**不保证数据长期持久化**，适合试用；正式统计请自托管本脚本，或把 `/export` 的数据定期归档。
