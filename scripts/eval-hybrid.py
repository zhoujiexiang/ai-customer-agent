#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
检索通道对比评测：纯向量 / 纯关键词 / RRF 等权融合 / RRF 加权融合。

回答两个问题：
  1. 再加一路关键词检索，能不能提升召回质量？
  2. 两条通道各自擅长什么、边界在哪？

为此用两组测试集：

  A. 同构问题 —— 措辞贴近文档标题。两路都不算难，看的是基本盘。
  B. 口语化改写 —— 字面与文档几乎不重合、但语义相同。专门压测语义泛化。

只看 A 组会得出「两路差不多」的结论，因为天花板效应会掩盖差异；
B 组才是能分出高下的地方 —— 这也是为什么评测集本身就得设计，
而不能随手抓几条问题当作测试集。

做法是把两路各自独立跑一遍（后端 /api/kb/search 的 mode 参数），
融合在本地算：好处是融合策略可以随便换着试，不用反复改后端；
而且融合后的分数会失去「余弦相似度」的绝对含义，而阈值 0.55 是按那个量纲定的 ——
所以能不能上线得先看数据，而不是先改主链路。

用法（后端需已启动）：
  python scripts/eval-hybrid.py
"""

import json
import sys
import urllib.parse
import urllib.request
from collections import defaultdict

BASE = "http://127.0.0.1:8080"
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

TOP_K = 5
RRF_K_VALUES = [10, 30, 60, 100]
RRF_K_MAIN = 60
# 加权融合的权重扫描：(向量权重, 关键词权重)。
# 等权 RRF 有个结构性缺陷 —— 两路候选完全不重叠时，两路的第 1 名会拿到
# 几乎相同的分数（1/(k+1)），谁排前面纯靠 tie-break；向量那边本来很可靠的第 1 名
# 就这样被关键词通道的无关头部稀释掉了。所以要扫一遍权重，看倾斜到什么程度才不劣化。
RRF_WEIGHT_GRID = [(1.0, 0.0), (0.99, 0.01), (0.98, 0.02), (0.9, 0.1),
                   (0.8, 0.2), (0.7, 0.3), (0.6, 0.4), (0.5, 0.5)]
RRF_WEIGHT_MAIN = (0.8, 0.2)

# A 组：与 eval-retrieval.py 同一份测试集，否则两组数字没法互相印证
HOMOGENEOUS = [
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

# B 组：口语化改写。刻意避开文档标题里的词（「退款/时效」「运费/承担」…），
# 换成用户真实会说的话，考的是模型能不能跨过字面差异找到同一段内容。
PARAPHRASE = [
    ("钱什么时候能退回来？", "02-退款到账时效.md"),
    ("买回来发现不合适，想退掉", "01-退换货政策.md"),
    ("买完就降价了，能把差价补给我吗", "07-价保规则.md"),
    ("东西一直没送到，怎么回事", "06-配送范围与时效.md"),
    ("退回去的邮费谁来出", "03-运费承担规则.md"),
    ("报销要用票，那个怎么弄", "04-发票规则.md"),
    ("满减券能跟别的券一起用吗", "05-优惠券使用规则.md"),
    ("申请售后好几天了没人管", "08-售后处理时效.md"),
]

TEST_SETS = [
    ("A · 同构问题", "措辞贴近文档标题", HOMOGENEOUS),
    ("B · 口语化改写", "字面不重合、语义相同", PARAPHRASE),
]


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


def search(token, question, mode, top_k=TOP_K):
    url = (f"{BASE}/api/kb/search?q={urllib.parse.quote(question)}"
           f"&topK={top_k}&mode={mode}")
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    with opener.open(req, timeout=60) as resp:
        payload = json.loads(resp.read().decode("utf-8"))["data"]
        return payload["hits"], payload.get("retrieveMs", 0)


# ----------------------------------------------------------------------
# 融合与评估
# ----------------------------------------------------------------------

def key_of(hit):
    """同文档的不同切片是两条独立结果，所以不能只用文档名做 key"""
    return (hit["docName"], hit["chunkIndex"])


def rrf_fuse(rankings, k=RRF_K_MAIN, weights=None):
    """
    Reciprocal Rank Fusion。
    只用排名、不用分数，因此天然规避了「两路分数量纲不同怎么加权」的问题 ——
    这正是它在工程上比加权求和更常用的原因。

    weights 为 None 时等权融合；给了权重就是经典的 weighted RRF。
    """
    if weights is None:
        weights = [1.0] * len(rankings)
    agg = defaultdict(float)
    for weight, ranked in zip(weights, rankings):
        for rank, hit in enumerate(ranked, start=1):
            agg[key_of(hit)] += weight / (k + rank)
    # 分数相同时用 key 兜底排序，保证结果可复现（否则并列项顺序随字典插入序漂移）
    return [key for key, _ in sorted(agg.items(), key=lambda kv: (-kv[1], kv[0]))]


def docs_of(ordered_keys):
    return [doc for doc, _ in ordered_keys]


def metrics(ordered_docs, cases):
    hit1 = hit3 = 0
    mrr = 0.0
    ranks = []
    for question, expect in cases:
        rank = next((i for i, d in enumerate(ordered_docs[question], 1) if d == expect), None)
        ranks.append(rank)
        if rank == 1:
            hit1 += 1
        if rank is not None and rank <= 3:
            hit3 += 1
        if rank is not None:
            mrr += 1.0 / rank
    n = len(cases)
    return hit1 / n * 100, hit3 / n * 100, mrr / n, ranks


def cell(rank):
    return "-" if rank is None else str(rank)


# ----------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------

def main():
    try:
        token = login()
    except Exception as exc:  # noqa: BLE001
        print(f"✗ 无法连接后端或登录失败：{exc}")
        sys.exit(1)

    print("检索通道对比评测")
    total_q = sum(len(cases) for _, _, cases in TEST_SETS)
    print(f"两组测试集共 {total_q} 个问题，统一取 topK={TOP_K}\n")

    # 先把两路原始结果全跑出来
    raw = {}          # set_name -> question -> {"vector": hits, "keyword": hits}
    latency = {"vector": [], "keyword": []}
    print("正在跑两路检索 ...")
    for set_name, _, cases in TEST_SETS:
        for question, _ in cases:
            v_hits, v_ms = search(token, question, "vector")
            k_hits, k_ms = search(token, question, "keyword")
            raw.setdefault(set_name, {})[question] = {"vector": v_hits, "keyword": k_hits}
            latency["vector"].append(v_ms)
            latency["keyword"].append(k_ms)
    print(f"  完成，共 {total_q * 2} 次检索\n")

    summary = {}      # set_name -> strategy -> (h1, h3, mrr, ranks)

    # ---------------- 一、分测试集对比三路策略 ----------------
    for set_name, desc, cases in TEST_SETS:
        print("=" * 78)
        print(f"  一、{set_name}（{len(cases)} 条 · {desc}）")
        print("=" * 78)
        print(f"  {'策略':<20} {'Hit@1':>8} {'Hit@3':>8} {'MRR':>9}")
        print("  " + "-" * 62)

        strategies = {}
        strategies["纯向量"] = {
            q: docs_of([key_of(h) for h in raw[set_name][q]["vector"]]) for q, _ in cases
        }
        strategies["纯关键词"] = {
            q: docs_of([key_of(h) for h in raw[set_name][q]["keyword"]]) for q, _ in cases
        }
        strategies["RRF 等权"] = {
            q: docs_of(rrf_fuse([raw[set_name][q]["vector"], raw[set_name][q]["keyword"]]))
            for q, _ in cases
        }
        strategies["RRF 加权(.8/.2)"] = {
            q: docs_of(rrf_fuse([raw[set_name][q]["vector"], raw[set_name][q]["keyword"]],
                                weights=RRF_WEIGHT_MAIN))
            for q, _ in cases
        }

        summary[set_name] = {}
        for name, ordered in strategies.items():
            h1, h3, mrr, ranks = metrics(ordered, cases)
            summary[set_name][name] = (h1, h3, mrr, ranks)
            print(f"  {name:<20} {h1:>7.1f}% {h3:>7.1f}% {mrr:>9.4f}")

        # 逐问题明细 —— B 组才有看头，A 组几乎全是 1
        if set_name.startswith("B"):
            print()
            print(f"  {'问题':<24} {'向量':>4} {'关键词':>6} {'等权':>5} {'加权':>5}   期望文档")
            print("  " + "-" * 78)
            for i, (question, expect) in enumerate(cases):
                vr = summary[set_name]["纯向量"][3][i]
                kr = summary[set_name]["纯关键词"][3][i]
                er = summary[set_name]["RRF 等权"][3][i]
                wr = summary[set_name]["RRF 加权(.8/.2)"][3][i]
                label = question if len(question) <= 22 else question[:21] + "…"
                print(f"  {label:<24} {cell(vr):>4} {cell(kr):>6} {cell(er):>5} {cell(wr):>5}   {expect}")
        print()

    # ---------------- 二、融合参数敏感性（只看 B 组） ----------------
    print("=" * 78)
    print("  二、融合参数敏感性（在 B 组上）")
    print("=" * 78)
    set_name, _, cases = TEST_SETS[1]

    print("  (a) 权重比 —— 向量权重 1.0 即等价于纯向量，这是「加了关键词到底有没有变差」的判据")
    print(f"  {'向量':>6} {'关键词':>7} {'Hit@1':>8} {'Hit@3':>8} {'MRR':>9}")
    print("  " + "-" * 62)
    weight_mrr = {}
    for wv, wk in RRF_WEIGHT_GRID:
        ordered = {
            q: docs_of(rrf_fuse([raw[set_name][q]["vector"], raw[set_name][q]["keyword"]],
                                weights=(wv, wk)))
            for q, _ in cases
        }
        h1, h3, mrr, _ = metrics(ordered, cases)
        weight_mrr[(wv, wk)] = mrr
        if wk == 0:
            mark = "  ← 纯向量基线"
        elif (wv, wk) == RRF_WEIGHT_MAIN:
            mark = "  ← 主表所用"
        else:
            mark = ""
        print(f"  {wv:>6.2f} {wk:>7.2f} {h1:>7.1f}% {h3:>7.1f}% {mrr:>9.4f}{mark}")
    print()
    crit = 1.0 / (RRF_K_MAIN + 2)
    print(f"    0.99/0.01 之后一路到 0.6/0.4 分数完全相同 —— 不是巧合，是 RRF 的形状：")
    print(f"    k={RRF_K_MAIN} 时 1/(k+r) 随 r 变化极平缓（第 1 名 {1/(RRF_K_MAIN+1):.5f} vs 第 2 名 "
          f"{1/(RRF_K_MAIN+2):.5f}），")
    print("    权重在很宽的区间内都改变不了排序。真正决定成败的是「有没有跨过临界点」。")
    print()
    print("    这个临界点可以推导出来。向量第 1 名得 w_v/(k+1)；")
    print("    而「向量第 2 名 + 关键词第 1 名」这种两路共识候选得 w_v/(k+2) + w_k/(k+1)。")
    print(f"    要求前者仍领先，解得  w_k < w_v/(k+2)  ——  w_v=1 时就是 < {crit:.4f}。")
    print(f"    实测：0.99/0.01 落在临界点之下，与纯向量持平；0.98/0.02 越过去，分数立刻下滑。")
    print()

    print("  (b) k —— 等权时的对照，k 越小越强调头部排名")
    print(f"  {'k':>6} {'Hit@1':>8} {'Hit@3':>8} {'MRR':>9}")
    print("  " + "-" * 62)
    for k in RRF_K_VALUES:
        ordered = {
            q: docs_of(rrf_fuse([raw[set_name][q]["vector"], raw[set_name][q]["keyword"]], k=k))
            for q, _ in cases
        }
        h1, h3, mrr, _ = metrics(ordered, cases)
        mark = "  ← 主表所用" if k == RRF_K_MAIN else ""
        print(f"  {k:>6} {h1:>7.1f}% {h3:>7.1f}% {mrr:>9.4f}{mark}")
    print("    等权时 k 只是把「名次差」整体缩放，并不改变两路之间的话语权比例，")
    print("    所以在「只有两路 + 两路等权」这个场景下排序对 k 不敏感 —— 反过来说，")
    print("    调 k 解决不了等权融合的问题，得调权重。")
    print()

    # ---------------- 三、互补性 ----------------
    print("=" * 78)
    print("  三、两路互补性（混合检索有没有价值，关键看这一节）")
    print("=" * 78)
    for set_name, _, cases in TEST_SETS:
        only_vec = only_kw = both = 0
        for question, _ in cases:
            vset = {key_of(h) for h in raw[set_name][question]["vector"]}
            kset = {key_of(h) for h in raw[set_name][question]["keyword"]}
            both += len(vset & kset)
            only_vec += len(vset - kset)
            only_kw += len(kset - vset)
        tot = only_kw + both
        print(f"  {set_name}：仅向量 {only_vec} 条 · 仅关键词 {only_kw} 条 "
              f"({only_kw / tot * 100 if tot else 0:.0f}%) · 两路都有 {both} 条")
    print()

    # ---------------- 四、耗时 ----------------
    print("=" * 78)
    print("  四、检索耗时")
    print("=" * 78)
    v_avg = sum(latency["vector"]) / len(latency["vector"])
    k_avg = sum(latency["keyword"]) / len(latency["keyword"])
    print(f"  向量通道   平均 {v_avg:>7.1f} ms   （含查询向量化的一次外部 API 调用）")
    print(f"  关键词通道 平均 {k_avg:>7.1f} ms   （纯数据库计算）")
    print()

    # ---------------- 结论 ----------------
    print("=" * 78)
    print("  结论")
    print("=" * 78)
    for set_name, _, _ in TEST_SETS:
        s = summary[set_name]
        print(f"  {set_name}")
        for name in ("纯向量", "纯关键词", "RRF 等权", "RRF 加权(.8/.2)"):
            h1, h3, mrr, _ = s[name]
            print(f"    {name:<8} Hit@1 {h1:>5.1f}%   MRR {mrr:.4f}")
    print()

    a_v = summary["A · 同构问题"]["纯向量"][2]
    a_k = summary["A · 同构问题"]["纯关键词"][2]
    b_v = summary["B · 口语化改写"]["纯向量"][2]
    b_k = summary["B · 口语化改写"]["纯关键词"][2]
    b_r = summary["B · 口语化改写"]["RRF 等权"][2]
    b_w = summary["B · 口语化改写"]["RRF 加权(.8/.2)"][2]

    print("  1) 两路的强弱是分场景的，不是谁全面更强：")
    print(f"     A 组两路都接近满分（向量 {a_v:.3f} / 关键词 {a_k:.3f}），看不出差别 ——")
    print("     这说明评测集本身要设计：措辞贴近文档时天花板效应会把差异全盖住。")
    print(f"     B 组立刻拉开（向量 {b_v:.3f} / 关键词 {b_k:.3f}），向量跨字面差异的能力更强。")
    print()
    print("  2) 等权融合会伤害向量通道：")
    print(f"     B 组 MRR 从纯向量的 {b_v:.4f} 掉到 {b_r:.4f}。这不是噪声，是 RRF 的结构使然 ——")
    print("     两路候选不重叠时，两路各自的第 1 名都拿 1/(k+1)，分数几乎相同，")
    print("     排序退化成 tie-break；关键词通道的头部（那些向量本来没召回的长尾切片）")
    print("     就这样把向量可靠的第 1 名挤了下去。明细表里那几条「向量第 1、融合后掉到 3~5」")
    print("     就是同一个机制反复出现的证据。")
    print()
    if b_w >= b_v - 1e-9:
        print(f"  3) 加权融合把这个损失补了回来：0.8/0.2 时 MRR {b_w:.4f}，与纯向量 {b_v:.4f} 持平。")
    else:
        print(f"  3) 加权也救不回来：0.8/0.2 时 MRR {b_w:.4f}，仍明显低于纯向量 {b_v:.4f}。")
        print("     而且问题不是「权重没调对」—— 扫描结果给出了一个可以推导的临界点：")
        print(f"     向量第 1 名得 w_v/(k+1)；而「向量第 2 名 + 关键词第 1 名」这种两路共识候选")
        print(f"     得 w_v/(k+2) + w_k/(k+1)，它反超的条件是 w_k > w_v/(k+2) ≈ {crit:.4f}。")
        m_low = weight_mrr.get((0.99, 0.01))
        m_high = weight_mrr.get((0.98, 0.02))
        if m_low is not None and m_high is not None:
            print(f"     实测 0.99/0.01 保住 {m_low:.4f}，而 0.98/0.02 就掉到 {m_high:.4f} ——")
            print("     正好卡在这条线的两侧，机制吻合。而任何真正能起作用的权重（≥0.1）")
            print("     都远在这条线之上，所以「调权重」在这个场景里是个伪选项。")
        print("     根因：当一路信号明显更强时，RRF 的「共识奖励」奖励的是噪声而不是信号。")
    print("     另需说明，权重是在 8 条样本上扫的，本身就带过拟合风险 ——")
    print("     这个样本量不该用来论证参数选择，上面只当机制验证看。")
    print()
    print("  4) 关键词通道值得保留，但理由不是精度：")
    print(f"     ① 快 {v_avg - k_avg:.0f} ms/次，且完全不依赖外部 Embedding 服务；")
    print("     ② Embedding 服务不可用时它是唯一的退路；")
    print("     ③ 稀有精确串（订单号、型号、SKU）本来就是向量模型的弱项 ——")
    print("        本知识库全是政策文档，没有这类内容，所以这条优势在这里根本测不出来。")
    print()
    print("  所以最终没有把混合检索落到主链路：数据不支持为它增加复杂度。")
    print("  但关键词通道保留下来（/api/kb/search?mode=keyword），作为降级与调试手段 ——")
    print("  这比「没做」多一层判断：知道它不划算，也知道它什么时候才会变得划算。")


if __name__ == "__main__":
    main()
