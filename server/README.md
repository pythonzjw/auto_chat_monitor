# 企微转发器 - 云端机器码授权服务

Flask + SQLite,只暴露一个 `GET /verify` 接口给 Android 客户端校验机器码;
本机用 `admin.py` 脚本管理白名单。

## 目录

```
server/
├── app.py                   # Flask 主程序,只对外暴露 /verify
├── admin.py                 # 管理 CLI: add/remove/enable/disable/list/info
├── requirements.txt         # 仅 flask
├── wework-license.service   # systemd unit
├── .gitignore               # 忽略 license.db
└── README.md
```

## 一次性部署到 47.116.98.81

```bash
# 1. 本机 — 打包并上传
cd /Users/zhengjiawei/Desktop/code/wework-collector
scp -r server root@47.116.98.81:/opt/wework-license

# 2. 服务器 — 装依赖
ssh root@47.116.98.81
apt update && apt install -y python3 python3-pip
pip3 install -r /opt/wework-license/requirements.txt

# 3. 注册 systemd 并启动
cp /opt/wework-license/wework-license.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now wework-license
systemctl status wework-license      # 应显示 active (running)

# 4. 防火墙开 5002 端口(阿里云安全组也要开)
# Ubuntu/Debian 自带 ufw:
ufw allow 5002/tcp
# CentOS/firewalld:
firewall-cmd --permanent --add-port=5002/tcp && firewall-cmd --reload

# 5. 自检
curl http://127.0.0.1:5002/healthz
# {"ok":true}
curl "http://127.0.0.1:5002/verify?code=TEST"
# {"msg":"机器码未在白名单,请联系管理员激活","ok":false}
```

## 日常运维

### 加白名单

用户从 App 拒绝弹框里"复制机器码",发给你后:

```bash
ssh root@47.116.98.81
cd /opt/wework-license
python3 admin.py add abc123def456 "张三-华为P40"
```

之后 6 小时内 App 会自动检测到授权(下次启动或下一次保活)。

### 其他常用命令

```bash
python3 admin.py list                      # 列出全部
python3 admin.py info     abc123def456     # 看单条详情(命中次数/最近活跃)
python3 admin.py disable  abc123def456     # 临时禁用,记录保留
python3 admin.py enable   abc123def456     # 重新启用
python3 admin.py remove   abc123def456     # 彻底删除记录
```

### 查日志

```bash
journalctl -u wework-license -f            # 实时跟随
journalctl -u wework-license --since today # 看今天
```

日志格式示例:
```
[INFO] [通过] abc123def456
[INFO] [拒绝] 未知机器码: deadbeef
[INFO] [拒绝] 已禁用机器码: abc123def456
```

### 重启 / 停止

```bash
systemctl restart wework-license
systemctl stop wework-license
```

### 备份数据

```bash
# license.db 是唯一需要备份的文件
cp /opt/wework-license/license.db /opt/wework-license/license.db.bak.$(date +%F)
```

## 接口契约

```
GET /verify?code=<machine_code>
→ 200 {"ok": true,  "msg": "授权有效"}
→ 200 {"ok": false, "msg": "机器码未在白名单..."}
→ 400 {"ok": false, "msg": "缺少 code 参数"}
```

Android 端收到 `ok=false` 立即拦截 + 弹拒绝框;`ok=true` 写本地缓存,
6 小时内不再联网,6 小时后自动复检。

如果以后要改字段名(比如 `success`/`message`),客户端只需改
`LicenseManager.kt` 里 `VerifyResponse` 数据类一处。

## 注意

- **HTTP 不是 HTTPS**。如果要上 HTTPS,在前面套 nginx + Let's Encrypt,
  并把 `LICENSE_BASE_URL` 改成 `https://...`;客户端 Manifest 的
  `usesCleartextTraffic="true"` 也可以去掉。
- Flask 自带 dev server 对当前规模(每设备每 6h 调一次)足够;
  如果设备数破万再考虑前面套 nginx 或换 gunicorn。
- `license.db` 默认在 `/opt/wework-license/license.db`,通过环境变量
  `LICENSE_DB` 可改。
