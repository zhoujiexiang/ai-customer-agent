#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Agent 端到端冒烟测试。

覆盖四条链路：
  1. 纯知识库问答（RAG）
  2. 只读工具调用（查物流）
  3. 写操作 + 二次确认（申请退款，自动点确认）
  4. 写操作 + 用户取消（改地址，自动点取消）

用法：
  python scripts/verify-agent.py            # 全量
  python scripts/verify-agent.py 3          # 只跑第 3 个场景

依赖：后端已在 8080 端口启动，数据库中有种子订单数据。
"""

import json
import subprocess
import sys
import threading
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8080"

# 环境里可能有 http_proxy，会把 127.0.0.1 也代理走，这里显式绕过
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")


def reset_demo_orders():
    """
    把演示订单恢复成初始状态。

    场景 3 会把订单改成「退款中」，场景 4 会尝试改地址，
    不重置的话脚本只能跑一次 —— 第二次跑会因为「已经是退款中」而失败，
    这是业务规则正确工作，但会让冒烟测试失去意义。
    docker 不可用时静默跳过，此时脚本仍可跑，只是断言会自动放宽。
    """
    sql = (
        "UPDATE mock_order SET status='已发货' "
        "WHERE order_no='202610010001';"
        "UPDATE mock_order SET receiver_address='广州市天河区体育西路 12 号维多利广场 B 塔 903' "
        "WHERE order_no='202610010005';"
    )
    try:
        subprocess.run(
            ["docker", "exec", "agent-postgres", "psql", "-U", "agent", "-d", "agent_db", "-c", sql],
            check=True, capture_output=True, timeout=30,
        )
        return True
    except Exception:  # noqa: BLE001
        return False


# ----------------------------------------------------------------------
# HTTP 辅助
# ----------------------------------------------------------------------

def post_json(path, payload, timeout=30):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        BASE + path, data=data, headers={"Content-Type": "application/json"}
    )
    with opener.open(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def open_stream(question, session_id=None, use_rag=True, enable_tools=True):
    payload = {
        "question": question,
        "sessionId": session_id,
        "useRag": use_rag,
        "enableTools": enable_tools,
    }
    req = urllib.request.Request(
        BASE + "/api/chat/stream",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    return opener.open(req, timeout=300)


# ----------------------------------------------------------------------
# 场景执行
# ----------------------------------------------------------------------

class Run:
    """收集一轮对话的全部事件，并提供摘要输出。"""

    def __init__(self, title):
        self.title = title
        self.events = []
        self.text = []
        self.tool_calls = []
        self.tool_results = []
        self.confirms = []
        self.confirm_results = []
        self.rag = None
        self.done = None
        self.error = None

    def on_event(self, name, data):
        self.events.append(name)
        if name == "delta":
            self.text.append(data.get("text", ""))
        elif name == "rag":
            self.rag = data
        elif name == "tool_call":
            self.tool_calls.append(data)
        elif name == "tool_result":
            self.tool_results.append(data)
        elif name == "tool_confirm":
            self.confirms.append(data)
        elif name == "tool_confirm_result":
            self.confirm_results.append(data)
        elif name == "done":
            self.done = data
        elif name == "error":
            self.error = data.get("message")

    @property
    def answer(self):
        return "".join(self.text)

    def event_sequence(self):
        out = []
        for name in self.events:
            if name == "delta":
                if not out or out[-1][0] != "delta":
                    out.append(("delta", 1))
                else:
                    out[-1] = ("delta", out[-1][1] + 1)
            else:
                out.append((name, None))
        return out


def run_scenario(title, question, confirm_action=None, session_id=None,
                 use_rag=True, enable_tools=True, verbose=True):
    run = Run(title)
    if verbose:
        print(f"\n{'=' * 74}")
        print(f"  {title}")
        print(f"  提问：{question}")
        print(f"{'=' * 74}")

    # 写操作：收到 tool_confirm 后延迟一小会儿再回执，模拟用户点击
    def maybe_confirm(name, data):
        if name == "tool_confirm" and confirm_action is not None:
            confirm_id = data["confirmId"]
            delay = 0.8

            def worker():
                try:
                    resp = post_json(
                        "/api/chat/confirm",
                        {"confirmId": confirm_id, "approved": confirm_action},
                    )
                    if verbose:
                        print(f"  ↳ 已回执 确认={'同意' if confirm_action else '取消'} "
                              f"accepted={resp['data']['accepted']}")
                except Exception as exc:  # noqa: BLE001
                    print(f"  ↳ 回执失败：{exc}")

            threading.Timer(delay, worker).start()

    with open_stream(question, session_id=session_id,
                     use_rag=use_rag, enable_tools=enable_tools) as resp:
        event_name = None
        for raw in resp:
            line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
            if line.startswith("event:"):
                event_name = line[6:].strip()
            elif line.startswith("data:"):
                try:
                    data = json.loads(line[5:].strip())
                except ValueError:
                    continue
                run.on_event(event_name, data)
                maybe_confirm(event_name, data)

    if not verbose:
        return run

    # ---- 输出 ----
    seq = "  →  ".join(
        f"{n}×{c}" if c else n for n, c in run.event_sequence()
    )
    print(f"\n  事件序列：{seq}")

    if run.rag:
        r = run.rag
        if "error" in r:
            print(f"  检索异常：{r['error']}")
        else:
            print(f"  检索：{r['retrieveMs']}ms  topK={r['topK']}  阈值={r['threshold']}  "
                  f"命中={r['hitCount']}  采用={r['usedCount']}")
            for hit in r["hits"][:3]:
                flag = "✓" if hit["used"] else "✗"
                print(f"    {flag} [{hit['index']}] {hit['score']:.4f}  《{hit['docName']}》")

    for call in run.tool_calls:
        tag = "写操作" if call["write"] else "只读"
        print(f"  工具调用：{call['label']}({call['name']}) [{tag}] 参数={call['arguments']}")

    for conf in run.confirms:
        print(f"  等待确认：{conf['preview']}  （{conf['timeoutSeconds']}s 超时）")

    for res in run.confirm_results:
        print(f"  确认结果：{res['status']}  耗时 {res['elapsedMs']}ms")

    for res in run.tool_results:
        mark = "OK " if res["success"] else "ERR"
        print(f"  工具返回：[{mark}] {res['summary']}  ({res['ms']}ms)")

    if run.done:
        d = run.done
        print(f"  指标：首字 {d['firstTokenMs']}ms | 总耗时 {d['totalMs']}ms | "
              f"模型 {d['llmMs']}ms | tokens {d['promptTokens']}→{d['completionTokens']} | "
              f"决策 {d['agentSteps']} 轮 | 工具 {d['toolCallCount']} 次")

    if run.error:
        print(f"  ✗ 错误：{run.error}")

    print(f"\n  回答：\n    " + run.answer.replace("\n", "\n    ")[:600])
    return run


# ----------------------------------------------------------------------
# 断言
# ----------------------------------------------------------------------

FAILED = []


def check(label, condition, detail=""):
    mark = "PASS" if condition else "FAIL"
    print(f"  [{mark}] {label}" + (f"  {detail}" if detail else ""))
    if not condition:
        FAILED.append(label)


def db_query(sql):
    try:
        out = subprocess.run(
            ["docker", "exec", "agent-postgres", "psql", "-U", "agent", "-d", "agent_db", "-t", "-A", "-c", sql],
            check=True, capture_output=True, timeout=30,
        )
        return out.stdout.decode("utf-8", errors="replace").strip()
    except Exception:  # noqa: BLE001
        return None


def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None

    print("Agent 端到端冒烟测试")

    try:
        with opener.open(BASE + "/api/chat/status", timeout=10) as resp:
            st = json.loads(resp.read().decode("utf-8"))["data"]
        print(f"后端状态：ready={st['ready']}  model={st['llmModel']}  "
              f"embedding={st['embeddingDimension']}维")
    except Exception as exc:  # noqa: BLE001
        print(f"✗ 无法连接后端 {BASE}：{exc}")
        sys.exit(1)

    db_ok = reset_demo_orders()
    print(f"演示数据重置：{'已恢复到初始状态' if db_ok else '跳过（docker 不可用）'}")

    # ---------------- 场景 1：纯知识库问答 ----------------
    if only in (None, "1"):
        r1 = run_scenario("场景 1 · 知识库问答（不调工具）", "退款一般几天到账？")
        print()
        check("返回了 rag 事件", r1.rag is not None and "hits" in (r1.rag or {}))
        check("检索命中数 > 0", bool(r1.rag and r1.rag.get("hitCount", 0) > 0),
              f"hitCount={r1.rag.get('hitCount') if r1.rag else None}")
        check("有采用（高于阈值）的片段", bool(r1.rag and r1.rag.get("usedCount", 0) > 0))
        check("没有调用工具", len(r1.tool_calls) == 0)
        check("回答非空", len(r1.answer) > 10, f"{len(r1.answer)} 字")
        check("回答带引用角标", "[1]" in r1.answer or "[2]" in r1.answer)
        check("收到 done 事件", r1.done is not None)

    # ---------------- 场景 2：只读工具调用 ----------------
    if only in (None, "2"):
        r2 = run_scenario("场景 2 · 只读工具（查物流）",
                          "帮我查一下订单 202610010003 的物流到哪了")
        print()
        check("调用了工具", len(r2.tool_calls) > 0)
        check("调用的是 query_logistics",
              any(c["name"] == "query_logistics" for c in r2.tool_calls),
              str([c["name"] for c in r2.tool_calls]))
        check("工具返回成功",
              len(r2.tool_results) > 0 and r2.tool_results[0]["success"])
        check("工具结果已回灌给模型（有最终回答）", len(r2.answer) > 5)
        check("决策轮数 ≥ 2（先调工具再回答）",
              bool(r2.done and r2.done.get("agentSteps", 0) >= 2),
              f"agentSteps={r2.done.get('agentSteps') if r2.done else None}")

    # ---------------- 场景 3：写操作 + 确认 ----------------
    if only in (None, "3"):
        r3 = run_scenario("场景 3 · 写操作二次确认（申请退款 → 用户同意）",
                          "订单 202610010001 我要退款，商品有质量问题",
                          confirm_action=True)
        print()
        check("发起了写操作", any(c["write"] for c in r3.tool_calls),
              str([(c["name"], c["write"]) for c in r3.tool_calls]))
        check("推送了 tool_confirm 事件", len(r3.confirms) > 0)
        check("确认结果已回传", len(r3.confirm_results) > 0)
        check("确认状态为 CONFIRMED",
              bool(r3.confirm_results and r3.confirm_results[0]["status"] == "CONFIRMED"))
        apply_result = next((r for r in r3.tool_results if r["name"] == "apply_refund"), None)
        check("写操作工具已执行", apply_result is not None)
        if apply_result:
            if apply_result["success"]:
                check("退款申请成功", True, apply_result["summary"])
            else:
                # 数据没重置成功时，第二次跑会命中「已经是退款中」这条业务规则
                check("重复申请被业务规则正确拒绝",
                      "退款中" in apply_result["summary"] or "已退款" in apply_result["summary"],
                      apply_result["summary"])
        check("模型给出了最终回答", len(r3.answer) > 10, f"{len(r3.answer)} 字")

        if db_ok:
            row = db_query(
                "SELECT status FROM mock_order WHERE order_no='202610010001'"
            )
            print(f"  数据库核对：订单 202610010001 状态 = {row}")
            check("订单状态已真实落库为「退款中」", row == "退款中", f"实际={row}")

    # ---------------- 场景 4：写操作 + 取消 ----------------
    if only in (None, "4"):
        r4 = run_scenario("场景 4 · 写操作二次确认（改地址 → 用户取消）",
                          "把订单 202610010005 的收货地址改成杭州市西湖区文三路 100 号",
                          confirm_action=False)
        print()
        check("推送了 tool_confirm 事件", len(r4.confirms) > 0)
        check("确认状态为 CANCELLED",
              bool(r4.confirm_results and r4.confirm_results[0]["status"] == "CANCELLED"))
        check("工具未被执行（无成功结果）",
              not any(r["success"] for r in r4.tool_results))
        check("模型告知用户已取消",
              any(k in r4.answer for k in ["取消", "未执行", "没有执行", "已终止", "未做修改"]),
              f"回答片段：{r4.answer[:80]}")

        if db_ok:
            row = db_query(
                "SELECT receiver_address FROM mock_order WHERE order_no='202610010005'"
            )
            print(f"  数据库核对：订单 202610010005 地址 = {row}")
            check("取消后地址确实未被修改",
                  bool(row) and "杭州市西湖区文三路" not in row, f"实际={row}")

    # ---------------- 汇总 ----------------
    print(f"\n{'=' * 74}")
    if FAILED:
        print(f"结果：{len(FAILED)} 项未通过")
        for item in FAILED:
            print(f"  ✗ {item}")
        sys.exit(1)
    print("结果：全部通过 ✓")
    print(f"{'=' * 74}")


if __name__ == "__main__":
    main()
