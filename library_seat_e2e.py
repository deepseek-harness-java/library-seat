#!/usr/bin/env python3
"""library-seat E2E：通过业务应用 SSE 代理调用 DSH Agent，验证工具全链路。"""
import json, subprocess, sys

AGENT = "study-copilot"
URL = "http://127.0.0.1:18100/api/assistant/stream"

CASES = [
    ("T1 空位查询", "图书馆现在有哪些空座位？简洁回答", ["静音区", "空座"]),
    ("T2 座位预约", "我是学号 S2026002 的学生韩梅梅，帮我在图书馆静音区预约一个带电源的座位，今天 14:00 到 18:00，直接预约告诉我预约号", ["B3", "静音"]),
    ("T3 我的预约", "我是学号 S2026001 的学生，帮我查一下我的座位预约记录，简洁回答", ["S2026001"]),
    ("T4 签到", "我是学号 S2026001，帮我看看我有没有待签到的座位预约，简洁回答", ["S2026001"]),
    ("T5 使用统计", "图书馆今天座位使用情况怎么样？简洁回答", ["空闲", "使用"]),
]

def ask(message, timeout=170):
    payload = json.dumps({"message": message}, ensure_ascii=False)
    try:
        out = subprocess.run(
            ["curl", "-s", "--noproxy", "*", "-N", "-X", "POST", URL,
             "-H", "Content-Type: application/json", "-d", payload,
             "--max-time", str(timeout)],
            capture_output=True, text=True, timeout=timeout + 10).stdout
    except Exception as e:
        return "", f"curl 异常: {e}"
    text = []
    ev = ""
    for line in out.splitlines():
        line = line.rstrip("\r")
        if line.startswith("event:"):
            ev = line[6:].strip()
        elif line.startswith("data:"):
            s = line[5:].strip()
            if not s or s == "[DONE]" or ev != "chunk":
                continue
            try:
                j = json.loads(s)
                c = j.get("content", "")
                if c:
                    text.append(c)
            except Exception:
                pass
            ev = ""
    return "".join(text), out

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    cases = CASES if not only else [c for c in CASES if c[0].startswith(only)]
    passed, failed = 0, []
    for name, q, keys in cases:
        reply, raw = ask(q)
        ok = all(k in reply for k in keys)
        print(f"[{'PASS' if ok else 'FAIL'}] {name}\n  Q: {q}\n  A: {reply[:200]}")
        if ok:
            passed += 1
        else:
            failed.append(name)
            if not reply:
                print(f"  raw 首行: {raw.splitlines()[:3] if raw else '(空)'}")
    print(f"\n===== library-seat E2E: {passed}/{len(cases)} PASS =====")

if __name__ == "__main__":
    main()
