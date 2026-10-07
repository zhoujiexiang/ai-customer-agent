#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
检索效果评测。

做两件事：
  1. 给标注好的测试集算召回质量（Hit@1 / Hit@3 / MRR）—— 这部分与阈值无关，
     衡量的是「向量检索本身找得准不准」
  2. 扫描不同阈值，算「答案覆盖率 / 噪声率 / 正确拒答率」的权衡曲线 ——
     这部分衡量的是「阈值卡在哪里最合适」

有意思的是这两件事是解耦的：检索排序错了，调阈值救不回来；
检索排序对了，阈值才是一个有意义的旋钮。

用法（后端需已启动）：
  python scripts/eval-retrieval.py
"""

import json
import sys
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ----------------------------------------------------------------------
# 测试集：问题 → 期望命中的文档
# 全部依据 docs/knowledge/ 下 8 篇政策文档人工标注
# ----------------------------------------------------------------------

ANSWERABLE = [
    ("退款一般几天到账？", "02-退款到账时效.md"),
    ("退款失败或者没到账怎么处理？", "02-退款到账时效.md"),
    ("退货运费由谁承担？", "03-运费承担规则.md"),
    ("大件商品的退货运费怎么算？", "03-运费承担规则.md"),
    ("七天无理由退货需要满足什么条件？", "01-退换货政策.md"),
    ("换货的流程是怎样的？", "01-退换货政策.md"),
    ("发票怎么开？可以补开吗？", "04-发票规则.md"),
    ("电子发票和纸质发票有什么区别？", "04-发票规则.md"),
    ("优惠券可以叠加使用吗？", "05-优惠券使用规则.md"),
    ("优惠券过期了还能恢复吗？", "05-优惠券使用规则.md"),
    ("你们配送到哪些地区？", "06-配送范围与时效.md"),
    ("下单后多久能发货？", "06-配送范围与时效.md"),
    ("商品降价了可以申请补差价吗？", "07-价保规则.md"),
    ("价保申请的有效期是多久？", "07-价保规则.md"),
    ("售后申请提交后多久会处理？", "08-售后处理时效.md"),
]

# 知识库里明确没有的问题：理想情况下应该全部被阈值挡掉，模型据此拒答
UNANSWERABLE = [
    "如何修改账号绑定的手机号？",
    "会员等级是怎么划分的？有哪些权益？",
    "支持花呗分期付款吗？",
    "怎么注销我的账号？",
]

TOP_K = 10
THRESHOLDS = [0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60]


# ----------------------------------------------------------------------
# HTTP
# ----------------------------------------------------------------------

def login():
    data = json.dumps({"username": "admin", "password": "admin123"}).encode("utf-8")
    req = urllib.request.Request(
        BASE + "/api/auth/login", data=data, headers={"Content-Type": "application/json"}
    )
    with opener.open(req, timeout=15) as resp:
        return json.loads(resp.read().decode("utf-8"))["data"]["token"]


def search(token, question, top_k=TOP_K):
    url = f"{BASE}/api/kb/search?q={urllib.parse.quote(question)}&topK={top_k}"
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with opener.open(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))["data"]["hits"]


def fetch_threshold(token):
    """当前生效的阈值从后端读，而不是在脚本里写死。
    写死过一次就踩了坑：配置从 0.35 调到 0.55，脚本里的文案没跟着改，
    报告最后印出一个早已过期的数字 —— 演示时被追问就露馅了。"""
    url = f"{BASE}/api/kb/search?q={urllib.parse.quote('退款')}&topK=1"
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with opener.open(req, timeout=30) as resp:
        return float(json.loads(resp.read().decode("utf-8"))["data"]["threshold"])


# ----------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------

def main():
    try:
        token = login()
    except Exception as exc:  # noqa: BLE001
        print(f"✗ 无法连接后端或登录失败：{exc}")
        sys.exit(1)

    print("检索效果评测")
    print(f"测试集：{len(ANSWERABLE)} 个可回答问题 + {len(UNANSWERABLE)} 个应拒答问题，"
          f"每次取 topK={TOP_K}\n")
    current_threshold = fetch_threshold(token)

    # ---------------- 1. 召回质量 ----------------
    print("=" * 76)
    print("  一、召回质量（与阈值无关，衡量向量检索本身的排序能力）")
    print("=" * 76)
    print(f"  {'问题':<24} {'期望文档':<22} {'排名':>4}  {'Top1 分数':>9}")
    print("  " + "-" * 72)

    hit1 = hit3 = 0
    mrr_sum = 0.0
    scores = []

    for question, expect in ANSWERABLE:
        hits = search(token, question)
        rank = None
        top_score = 0.0
        for i, h in enumerate(hits):
            if i == 0:
                top_score = h["score"]
            if h["docName"] == expect and rank is None:
                rank = i + 1
        if rank == 1:
            hit1 += 1
        if rank is not None and rank <= 3:
            hit3 += 1
        if rank is not None:
            mrr_sum += 1.0 / rank
        scores.append(top_score)

        label = question if len(question) <= 22 else question[:21] + "…"
        print(f"  {label:<24} {expect:<24} {str(rank or '-'):>4}  {top_score:>9.4f}")

    n = len(ANSWERABLE)
    print("  " + "-" * 72)
    print(f"  Hit@1 = {hit1}/{n} = {hit1 / n * 100:.1f}%    "
          f"Hit@3 = {hit3}/{n} = {hit3 / n * 100:.1f}%    "
          f"MRR = {mrr_sum / n:.4f}")
    print(f"  Top1 分数分布：min={min(scores):.3f}  平均={sum(scores) / len(scores):.3f}  "
          f"max={max(scores):.3f}")

    # ---------------- 2. 阈值权衡 ----------------
    print()
    print("=" * 76)
    print("  二、阈值权衡（阈值只决定哪些片段进 Prompt，不改变排序）")
    print("=" * 76)
    print(f"  {'阈值':>6}  {'答案覆盖率':>10}  {'噪声率':>8}  {'正确拒答率':>11}  说明")
    print("  " + "-" * 72)

    ans_hits = {q: search(token, q) for q, _ in ANSWERABLE}
    unans_hits = {q: search(token, q) for q in UNANSWERABLE}

    best = None
    for t in THRESHOLDS:
        # 答案覆盖率：期望文档被采用的问题占比
        covered = 0
        adopted_total = 0
        adopted_correct = 0
        for question, expect in ANSWERABLE:
            hits = ans_hits[question]
            adopted = [h for h in hits if h["score"] >= t]
            adopted_total += len(adopted)
            adopted_correct += sum(1 for h in adopted if h["docName"] == expect)
            if any(h["docName"] == expect for h in adopted):
                covered += 1
        coverage = covered / n * 100
        noise = (1 - adopted_correct / adopted_total) * 100 if adopted_total else 0.0

        # 正确拒答率：无答案问题中，所有命中都低于阈值
        rejected = sum(
            1 for q in UNANSWERABLE if all(h["score"] < t for h in unans_hits[q])
        )
        reject_rate = rejected / len(UNANSWERABLE) * 100

        # 综合分：覆盖率越高越好，噪声和漏答都要扣分
        score = coverage - noise * 0.5
        note = ""
        if best is None or score > best[0]:
            best = (score, t)
        print(f"  {t:>6.2f}  {coverage:>9.1f}%  {noise:>7.1f}%  {reject_rate:>10.1f}%")

    print("  " + "-" * 72)
    print(f"  当前后端生效的阈值（读自 /api/kb/search）为 {current_threshold:.2f}")
    print(f"  综合覆盖率与噪声，建议阈值：{best[1]:.2f}")
    print()
    print("  结论：阈值调高会同时降低噪声和答案覆盖率 —— 这是在「宁可拒答」")
    print("  和「尽量回答」之间的取舍，取决于业务对幻觉的容忍度。")

    # ---------------- 3. 应拒答问题的实际得分 ----------------
    print()
    print("=" * 76)
    print("  三、应拒答问题（知识库外）的最高得分 —— 越低说明越容易和真问题区分开")
    print("=" * 76)
    for q in UNANSWERABLE:
        hits = unans_hits[q]
        top = hits[0] if hits else None
        if top:
            print(f"  {q:<32} 最高分 {top['score']:.4f}  《{top['docName']}》")
        else:
            print(f"  {q:<32} 无命中")

    if hit1 / n >= 0.8:
        print("\n召回质量良好：Hit@1 ≥ 80%，排序能力可用，阈值调参有意义。")
    else:
        print("\n召回质量一般：建议先优化切片粒度或换向量模型，再谈阈值。")


if __name__ == "__main__":
    main()
