#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
机器码白名单 CLI 管理脚本

用法:
    python3 admin.py add    <machine_code> [备注]    # 添加授权
    python3 admin.py remove <machine_code>           # 删除授权(彻底删除记录)
    python3 admin.py disable <machine_code>          # 禁用(保留记录,verify 返回 false)
    python3 admin.py enable  <machine_code>          # 重新启用
    python3 admin.py list                            # 列出全部
    python3 admin.py info   <machine_code>           # 看单条详情
"""
import os
import sqlite3
import sys
import time
from datetime import datetime

DB_PATH = os.environ.get(
    "LICENSE_DB",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "license.db"),
)


def conn():
    c = sqlite3.connect(DB_PATH)
    c.row_factory = sqlite3.Row
    return c


def init_if_needed():
    with conn() as c:
        c.execute(
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


def fmt_ts(ts):
    if not ts:
        return "-"
    return datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")


def cmd_add(code, note=""):
    now = int(time.time())
    with conn() as c:
        try:
            c.execute(
                "INSERT INTO licenses (machine_code, enabled, note, created_at, updated_at) "
                "VALUES (?, 1, ?, ?, ?)",
                (code, note, now, now),
            )
            print(f"[OK] 已添加: {code}  note={note!r}")
        except sqlite3.IntegrityError:
            # 已存在 → 重新启用并更新备注
            c.execute(
                "UPDATE licenses SET enabled = 1, note = ?, updated_at = ? "
                "WHERE machine_code = ?",
                (note, now, code),
            )
            print(f"[OK] 已存在,改为启用 + 更新备注: {code}  note={note!r}")


def cmd_remove(code):
    with conn() as c:
        cur = c.execute("DELETE FROM licenses WHERE machine_code = ?", (code,))
        if cur.rowcount > 0:
            print(f"[OK] 已删除: {code}")
        else:
            print(f"[WARN] 不存在: {code}")
            sys.exit(1)


def cmd_set_enabled(code, enabled):
    now = int(time.time())
    with conn() as c:
        cur = c.execute(
            "UPDATE licenses SET enabled = ?, updated_at = ? WHERE machine_code = ?",
            (1 if enabled else 0, now, code),
        )
        if cur.rowcount > 0:
            print(f"[OK] {'启用' if enabled else '禁用'}: {code}")
        else:
            print(f"[WARN] 不存在: {code}")
            sys.exit(1)


def cmd_list():
    with conn() as c:
        rows = c.execute(
            "SELECT machine_code, enabled, note, created_at, last_seen_at, hit_count "
            "FROM licenses ORDER BY created_at DESC"
        ).fetchall()
    if not rows:
        print("(空)")
        return
    print(f"{'状态':<6}{'机器码':<22}{'命中':<6}{'最近活跃':<22}{'添加时间':<22}备注")
    print("-" * 100)
    for r in rows:
        status = "启用" if r["enabled"] else "禁用"
        print(
            f"{status:<6}{r['machine_code']:<22}{r['hit_count']:<6}"
            f"{fmt_ts(r['last_seen_at']):<22}{fmt_ts(r['created_at']):<22}{r['note'] or ''}"
        )


def cmd_info(code):
    with conn() as c:
        row = c.execute(
            "SELECT * FROM licenses WHERE machine_code = ?", (code,)
        ).fetchone()
    if row is None:
        print(f"[WARN] 不存在: {code}")
        sys.exit(1)
    print(f"机器码:     {row['machine_code']}")
    print(f"状态:       {'启用' if row['enabled'] else '禁用'}")
    print(f"备注:       {row['note'] or ''}")
    print(f"添加时间:   {fmt_ts(row['created_at'])}")
    print(f"更新时间:   {fmt_ts(row['updated_at'])}")
    print(f"最近活跃:   {fmt_ts(row['last_seen_at'])}")
    print(f"命中次数:   {row['hit_count']}")


def usage():
    print(__doc__)
    sys.exit(1)


def main():
    init_if_needed()
    if len(sys.argv) < 2:
        usage()
    cmd = sys.argv[1]
    args = sys.argv[2:]
    if cmd == "add":
        if not args:
            usage()
        cmd_add(args[0], " ".join(args[1:]))
    elif cmd == "remove":
        if len(args) != 1:
            usage()
        cmd_remove(args[0])
    elif cmd == "disable":
        if len(args) != 1:
            usage()
        cmd_set_enabled(args[0], False)
    elif cmd == "enable":
        if len(args) != 1:
            usage()
        cmd_set_enabled(args[0], True)
    elif cmd == "list":
        cmd_list()
    elif cmd == "info":
        if len(args) != 1:
            usage()
        cmd_info(args[0])
    else:
        usage()


if __name__ == "__main__":
    main()
