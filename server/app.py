#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
企微转发器 - 云端机器码授权服务

只暴露一个接口:
    GET /verify?code=<machine_code>
    → {ok: bool, msg: str}

数据持久化在 license.db (SQLite),与 app.py 同目录。
"""
import os
import sqlite3
import time
from contextlib import contextmanager

from flask import Flask, jsonify, request

DB_PATH = os.environ.get(
    "LICENSE_DB",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "license.db"),
)
HOST = os.environ.get("LICENSE_HOST", "0.0.0.0")
PORT = int(os.environ.get("LICENSE_PORT", "5002"))

app = Flask(__name__)


@contextmanager
def db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()


def init_db():
    with db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS licenses (
                machine_code TEXT PRIMARY KEY,
                enabled      INTEGER NOT NULL DEFAULT 1,
                note         TEXT,
                created_at   INTEGER NOT NULL,
                updated_at   INTEGER NOT NULL,
                last_seen_at INTEGER,
                hit_count    INTEGER NOT NULL DEFAULT 0
            )
            """
        )


@app.route("/verify", methods=["GET"])
def verify():
    code = (request.args.get("code") or "").strip()
    if not code:
        return jsonify(ok=False, msg="缺少 code 参数"), 400
    if len(code) > 128:
        return jsonify(ok=False, msg="机器码长度异常"), 400

    now = int(time.time())
    with db() as conn:
        row = conn.execute(
            "SELECT enabled FROM licenses WHERE machine_code = ?", (code,)
        ).fetchone()
        if row is None:
            app.logger.info("[拒绝] 未知机器码: %s", code)
            return jsonify(ok=False, msg="机器码未在白名单,请联系管理员激活")
        if row["enabled"] == 0:
            app.logger.info("[拒绝] 已禁用机器码: %s", code)
            return jsonify(ok=False, msg="机器码已被禁用")
        # 通过 — 更新心跳
        conn.execute(
            "UPDATE licenses SET last_seen_at = ?, hit_count = hit_count + 1 "
            "WHERE machine_code = ?",
            (now, code),
        )
        app.logger.info("[通过] %s", code)
        return jsonify(ok=True, msg="授权有效")


@app.route("/healthz", methods=["GET"])
def healthz():
    return jsonify(ok=True)


if __name__ == "__main__":
    init_db()
    # 简单 dev server 即可,授权调用 QPS 极低(每设备 6h 一次)
    # 上生产可在 systemd 前面套 nginx,或换 gunicorn
    app.run(host=HOST, port=PORT, threaded=True)
