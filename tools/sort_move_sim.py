#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Better Bundle —— 「一键整理」步数 vs 袋子占用率 模拟器 / 测试用例

用途
----
把当前 `BundlePacker`（三层装箱 L1 同种 → L2 非预留 worst-fit → L3 全部 best-fit，
含预留袋与 realize 腾挪/缓冲）抽象复刻成纯 Python（无 Minecraft 依赖），
用来估算：**整理步数 f(p) 随袋子占用率 p 的近似曲线**，并对比几种装箱策略。

用法
----
    /home/xuyingjun/code/python/bin/python tools/sort_move_sim.py            # 跑扫描 + 出图
    /home/xuyingjun/code/python/bin/python tools/sort_move_sim.py --selftest # 只跑自检

输出
----
    tools/sort_moves_K{K}_keys{N}.png
    tools/sort_moves_K{K}_keys{N}.csv

说明
----
- 重量单位制与 Java 一致：一个袋子 = 64；物品单件重量 = ceil(64*count/maxStack)。
- Java 端用 `IdentityHashMap` 的迭代顺序，Python 端按 bag 下标顺序；具体某一步的
  选择可能略有出入，但步数量级/曲线形状一致。
"""

import argparse
import csv
import math
import random
from dataclasses import dataclass

W_FULL = 64
RESERVE_RATIO = 0.25
MAX_TOTAL_MOVES = 256
REALIZE_GUARD = 512


# ---------------------------------------------------------------- 基础模型

def ceil_div(a, b):
    return -(-a // b)


def jround(x):
    """Java Math.round（正数：floor(x+0.5)）。"""
    return math.floor(x + 0.5)


@dataclass
class Entry:
    key: int
    count: int
    max_stack: int
    weight: int

    @staticmethod
    def make(key, count, max_stack, weight=None):
        w = weight if weight is not None else ceil_div(W_FULL * count, max_stack)
        return Entry(key, count, max_stack, w)


class Bag:
    __slots__ = ("idx", "entries", "used")

    def __init__(self, idx):
        self.idx = idx
        self.entries = []
        self.used = 0

    def free(self):
        return max(0, W_FULL - self.used)

    def find_entry(self, key):
        for e in self.entries:
            if e.key == key:
                return e
        return None

    def has_key(self, key):
        for e in self.entries:
            if e.key == key and e.count > 0:
                return True
        return False

    def clone(self):
        nb = Bag(self.idx)
        nb.entries = [Entry(e.key, e.count, e.max_stack, e.weight) for e in self.entries]
        nb.used = self.used
        return nb


def clone_bags(bags):
    return [b.clone() for b in bags]


def max_acceptable(bag, key, key_max):
    """还能放多少个 key（与 BundleContentsHelper.maxAcceptable 的整数等价形式）。"""
    return (bag.free() * key_max[key]) // W_FULL


def add_to_bag(bag, key, take, key_max):
    e = bag.find_entry(key)
    if e is None:
        bag.entries.append(Entry.make(key, take, key_max[key]))
    else:
        e.count += take
        e.weight = ceil_div(W_FULL * e.count, e.max_stack)
    bag.used = sum(x.weight for x in bag.entries)


def reserve_slots(bags, key_max):
    """与 BundlePacker.pickReserved / BundlePanelInteraction.pickReservedSlots 一致。"""
    k = len(bags)
    if k <= 1:
        return set()
    r = jround(k * RESERVE_RATIO)
    r = max(1, min(r, k - 1))
    order = sorted(range(k), key=lambda i: -bags[i].free())  # 稳定：并列按原序
    return set(order[:r])


# ---------------------------------------------------------------- 写入策略

def write_insert(bags, key, count, strategy, key_max):
    """把 count 个 key 按 strategy 写入袋子（增量，模拟实际塞入）。"""
    rem = count
    cands = [(b, max_acceptable(b, key, key_max)) for b in bags]
    cands = [(b, c) for b, c in cands if c > 0]
    same = [(b, c) for b, c in cands if b.has_key(key)]
    rest = [(b, c) for b, c in cands if not b.has_key(key)]

    if strategy == "worst":
        rest.sort(key=lambda bc: -bc[1])
        ordered = same + rest
    elif strategy == "best":
        rest.sort(key=lambda bc: bc[1])
        ordered = same + rest
    elif strategy == "reserve":
        reserved = reserve_slots(bags, key_max)
        nonres = [(b, c) for b, c in rest if b.idx not in reserved]
        res = [(b, c) for b, c in rest if b.idx in reserved]
        nonres.sort(key=lambda bc: -bc[1])  # L2 worst-fit
        res.sort(key=lambda bc: bc[1])      # L3 best-fit
        ordered = same + nonres + res
    else:
        raise ValueError(strategy)

    for b, c in ordered:
        if rem <= 0:
            break
        take = min(c, rem)
        add_to_bag(b, key, take, key_max)
        rem -= take
    return rem  # 放不下的剩余


def build_arrangement(K, target_occ, strategy, key_max, rng, max_ops=100000):
    bags = [Bag(i) for i in range(K)]
    keys = list(key_max.keys())
    total_cap = W_FULL * K
    ops = 0
    while ops < max_ops:
        used = sum(b.used for b in bags)
        if used >= target_occ * total_cap:
            break
        key = rng.choice(keys)
        maxs = key_max[key]
        cnt = rng.randint(1, maxs)
        before = used
        write_insert(bags, key, cnt, strategy, key_max)
        ops += 1
        if sum(b.used for b in bags) == before:
            break  # 满，放不下了
    used = sum(b.used for b in bags)
    return bags, used / total_cap


# ---------------------------------------------------------------- 整理规划（复刻 BundlePacker）
#
# 策略：
#   worst   —— L1 同种 → 全部 worst-fit
#   best    —— L1 同种 → 全部 best-fit
#   reserve —— L1 同种 → 非预留 worst-fit → 全部 best-fit   （当前实现）

def collect_key_info(bags):
    per, mx = {}, {}
    for b in bags:
        for e in b.entries:
            if e.count <= 0:
                continue
            if e.key not in per:
                per[e.key] = max(1, e.weight // e.count)
                mx[e.key] = e.max_stack
    return per, mx


def _pick(bags, cap, w, mode, exclude):
    best, bestv = None, None
    for b in bags:
        if exclude is not None and b.idx in exclude:
            continue
        c = cap[b.idx]
        if c < w:
            continue
        if mode == "worst":
            if bestv is None or c > bestv:
                best, bestv = b, c
        else:  # best-fit
            if bestv is None or c < bestv:
                best, bestv = b, c
    return best


def assign_targets(bags, per_by, max_by, strategy, key_max):
    totals = {}
    current_keys = {b.idx: set() for b in bags}
    for b in bags:
        for e in b.entries:
            if e.count <= 0:
                continue
            totals[e.key] = totals.get(e.key, 0) + e.count
            current_keys[b.idx].add(e.key)
    if not bags or not totals:
        return {}

    reserved = reserve_slots(bags, key_max) if strategy == "reserve" else set()

    chunks = []
    for key in sorted(totals.keys()):
        total = totals[key]
        per = per_by.get(key, 1)
        maxs = max(1, max_by.get(key, 64))
        full, rem = divmod(total, maxs)
        for _ in range(full):
            chunks.append((key, maxs, maxs * per))
        if rem > 0:
            chunks.append((key, rem, rem * per))
    chunks.sort(key=lambda c: (-c[2], c[0]))

    target = {b.idx: {} for b in bags}
    cap = {b.idx: W_FULL for b in bags}  # capBase（无内嵌袋障碍时 = 64）
    for key, cnt, w in chunks:
        best = None
        # L1 同种
        for b in bags:
            if cap[b.idx] >= w and key in current_keys[b.idx]:
                best = b
                break
        if best is None:
            if strategy == "worst":
                best = _pick(bags, cap, w, "worst", None)
            elif strategy == "best":
                best = _pick(bags, cap, w, "best", None)
            else:  # reserve
                best = _pick(bags, cap, w, "worst", reserved)
                if best is None:
                    best = _pick(bags, cap, w, "best", None)
        if best is None:
            continue  # 放不下：保持原位
        cap[best.idx] -= w
        target[best.idx][key] = target[best.idx].get(key, 0) + cnt
    return target


def compute_diff(bags, target):
    out, inn = {}, {}
    for b in bags:
        cur = {}
        for e in b.entries:
            if e.count <= 0:
                continue
            cur[e.key] = cur.get(e.key, 0) + e.count
        tgt = target.get(b.idx, {})
        keys = list(cur.keys()) + [k for k in tgt if k not in cur]
        o, i = {}, {}
        for k in keys:
            have, want = cur.get(k, 0), tgt.get(k, 0)
            if have > want:
                o[k] = have - want
            elif want > have:
                i[k] = want - have
        if o:
            out[b.idx] = o
        if i:
            inn[b.idx] = i
    return out, inn


def state_sig(bags):
    parts = []
    for b in bags:
        ps = sorted(f"{e.key}:{e.count}" for e in b.entries if e.count > 0)
        parts.append(",".join(ps))
    return "|".join(parts)


def apply_extract(src, key, count, dst, key_max):
    se = src.find_entry(key)
    if se is None:
        return
    moved = min(count, se.count)
    if moved <= 0:
        return
    w = ceil_div(se.weight * moved, se.count)
    if w <= 0:
        w = 1
    se.count -= moved
    se.weight = max(0, se.weight - w)
    if se.count <= 0:
        src.entries.remove(se)
    src.used = max(0, src.used - w)

    de = dst.find_entry(key)
    if de is not None:
        de.count += moved
        de.weight += w
    else:
        dst.entries.append(Entry.make(key, moved, key_max[key], weight=w))
    dst.used += w


def try_fill(src, key, dst, per_by, moves, out, inn, allow_flat, key_max):
    se = src.find_entry(key)
    if se is None or se.count <= 0:
        return False
    per = max(1, per_by.get(key, 1))
    take = min(se.count, dst.free() // per)
    if take <= 0:
        return False
    over = out.get(src.idx, {}).get(key, 0)
    need = inn.get(dst.idx, {}).get(key, 0)
    delta = (abs(over - take) - over) + (abs(need - take) - need)
    if allow_flat:
        if delta > 0:
            return False
    else:
        if delta >= 0:
            return False
    moves.append((src.idx, key, se.count, dst.idx))
    apply_extract(src, key, take, dst, key_max)
    return True


def find_buffer(bags, inn, victim, exclude):
    p = max(1, victim.weight // max(1, victim.count))
    wants, best = None, None
    for b in bags:
        if b is exclude:
            continue
        free = b.free()
        if free < p:
            continue
        if inn.get(b.idx, {}).get(victim.key, 0) > 0:
            if wants is None or free > wants.free():
                wants = b
        else:
            if best is None or free > best.free():
                best = b
    return wants if wants is not None else best


def realize(bags, target, per_by, moves, key_max):
    byidx = {b.idx: b for b in bags}
    seen = set()
    guard = 0
    while guard < REALIZE_GUARD and len(moves) < MAX_TOTAL_MOVES:
        guard += 1
        out, inn = compute_diff(bags, target)
        if not out and not inn:
            return
        sig = state_sig(bags)
        if sig in seen:
            return
        seen.add(sig)

        moved = False
        # 直接顶填
        for src_i, keys in out.items():
            for key in list(keys.keys()):
                per = max(1, per_by.get(key, 1))
                for dst_i in inn.keys():
                    if inn[dst_i].get(key, 0) <= 0:
                        continue
                    if byidx[dst_i].free() < per:
                        continue
                    if try_fill(byidx[src_i], key, byidx[dst_i], per_by, moves, out, inn, False, key_max):
                        moved = True
                        break
                if moved:
                    break
            if moved:
                break
        if moved:
            continue

        # 僵局：接收袋太满 -> 把本就要搬走的条目先挪到缓冲袋
        for blocked_i, needs in inn.items():
            if moved:
                break
            blocked = byidx[blocked_i]
            for key, need in list(needs.items()):
                if moved or need <= 0:
                    continue
                perN = max(1, per_by.get(key, 1))
                if blocked.free() >= perN:
                    continue
                victim = None
                for e in blocked.entries:
                    if e.count > 0 and out.get(blocked_i, {}).get(e.key, 0) > 0:
                        victim = e
                        break
                if victim is None:
                    continue
                buffer = find_buffer(bags, inn, victim, blocked)
                if buffer is None:
                    continue
                if try_fill(blocked, victim.key, buffer, per_by, moves, out, inn, True, key_max):
                    moved = True
        if not moved:
            return


def valid_prefix(live, planned, key_max):
    vm = clone_bags(live)
    byidx = {b.idx: b for b in vm}
    valid = []
    for src_i, key, count, dst_i in planned:
        src = byidx.get(src_i)
        if src is None:
            break
        se = src.find_entry(key)
        if se is None or se.count < count:
            break
        per = max(1, se.weight // se.count)
        dst = byidx.get(dst_i)
        if dst is None or dst is src or dst.free() // per < 1:
            break
        take = min(se.count, dst.free() // per)
        if take > 0:
            apply_extract(src, key, take, dst, key_max)
        valid.append((src_i, key, count, dst_i))
    return valid


def plan_moves(live, strategy, key_max):
    """返回 (执行步数, 状态, ordered)。状态 ∈ {ok, 无需整理, 受限, 无安全步骤}。"""
    bags = clone_bags(live)
    per_by, max_by = collect_key_info(bags)
    target = assign_targets(bags, per_by, max_by, strategy, key_max)
    moves = []
    realize(bags, target, per_by, moves, key_max)

    if not moves:
        out, inn = compute_diff(bags, target)
        if not out and not inn:
            return 0, "无需整理", []
        return 0, "受限", []

    ordered = valid_prefix(live, moves, key_max)
    if not ordered:
        return 0, "无安全步骤", []
    return len(ordered), "ok", ordered


def apply_ordered(live, ordered, key_max):
    out = clone_bags(live)
    byidx = {b.idx: b for b in out}
    for src_i, key, count, dst_i in ordered:
        src, dst = byidx[src_i], byidx[dst_i]
        se = src.find_entry(key)
        if se is None:
            continue
        per = max(1, se.weight // se.count)
        take = min(se.count, dst.free() // per)
        if take > 0:
            apply_extract(src, key, take, dst, key_max)
    return out


PERTURB_GROUP = 5  # 「每种物品 5 个一起」搬动


def perturb(bags, frac, key_max, rng):
    """扰动：随机取 frac 比例的物品，按“每种物品最多 5 个一捆”搬到别的袋子。"""
    if frac <= 0:
        return
    total = sum(e.count for b in bags for e in b.entries if e.count > 0)
    quota = int(total * frac)
    guard = 0
    while quota > 0 and guard < 100000:
        guard += 1
        pool = [(b, e) for b in bags for e in b.entries if e.count > 0]
        if not pool:
            break
        b, e = rng.choice(pool)
        g = min(PERTURB_GROUP, e.count, quota)
        per = ceil_div(W_FULL, key_max[e.key])
        e.count -= g
        e.weight = max(0, e.weight - per * g)
        if e.count <= 0:
            b.entries.remove(e)
        b.used = max(0, b.used - per * g)
        room = [x for x in bags if x is not b and x.free() >= per * g]
        if room:
            add_to_bag(rng.choice(room), e.key, g, key_max)
        else:
            add_to_bag(b, e.key, g, key_max)  # 没地方就放回
        quota -= g


def sort_twice(live, strategy, key_max, perturb_frac=0.0, rng=None):
    """返回 (首次步数, 二次步数)。
    二次 = 整理一次 → 扰动 perturb_frac 比例的条目 → 再整理的步数。"""
    steps, status, ordered = plan_moves(live, strategy, key_max)
    after = apply_ordered(live, ordered, key_max) if ordered else clone_bags(live)
    if perturb_frac > 0 and rng is not None:
        perturb(after, perturb_frac, key_max, rng)
    steps2, _, _ = plan_moves(after, strategy, key_max)
    return steps, steps2


# ---------------------------------------------------------------- 场景 / 扫描

def default_key_max(n_big=8, n_mid=3, n_small=1):
    km = {}
    k = 0
    for _ in range(n_big):
        km[k] = 64
        k += 1
    for _ in range(n_mid):
        km[k] = 16
        k += 1
    for _ in range(n_small):
        km[k] = 1  # 不可堆叠
        k += 1
    return km


def sweep(K, seeds, occ_grid, key_max, arrange, sorters, perturb_frac=0.0):
    """返回 {sorter: [(实际占用, 首整理步数, 再整理步数), ...]}。"""
    data = {s: [] for s in sorters}
    for p in occ_grid:
        for seed in range(seeds):
            rng = random.Random((K, round(p * 1000), seed))
            bags, occ = build_arrangement(K, p, arrange, key_max, rng)
            for s in sorters:
                s1, s2 = sort_twice(bags, s, key_max, perturb_frac, rng)
                data[s].append((occ, s1, s2))
    return data


def fit_curve(xs, ys):
    """拟合 f(p) ≈ A/(1−p) + B·p + C·p²，返回 (A, B, C, R²)；无 numpy 时返回 None。"""
    try:
        import numpy as np
    except Exception:
        return None
    p = np.asarray(xs, dtype=float)
    y = np.asarray(ys, dtype=float)
    m = p < 0.999  # 排除装满（1-p=0）
    p, y = p[m], y[m]
    if len(p) < 4:
        return None
    A = np.vstack([1 / (1 - p), p, p * p]).T
    try:
        coef, *_ = np.linalg.lstsq(A, y, rcond=None)
    except Exception:
        return None
    pred = A @ coef
    r2 = 1 - float(np.sum((y - pred) ** 2)) / max(1e-9, float(np.sum((y - y.mean()) ** 2)))
    return float(coef[0]), float(coef[1]), float(coef[2]), r2


def aggregate(rows, buckets=20):
    """按占用率分桶取均值，返回 (xs, 首整理均值, 二次整理均值)。"""
    bins = [[] for _ in range(buckets)]
    for occ, s1, s2 in rows:
        b = min(buckets - 1, max(0, int(occ * buckets)))
        bins[b].append((s1, s2))
    xs, y1, y2 = [], [], []
    for i in range(buckets):
        if bins[i]:
            xs.append((i + 0.5) / buckets)
            y1.append(sum(a for a, _ in bins[i]) / len(bins[i]))
            y2.append(sum(b for _, b in bins[i]) / len(bins[i]))
    return xs, y1, y2


# ---------------------------------------------------------------- 自检 / 主程序

def selftest():
    km = {0: 64, 1: 64}
    # 1) 空模型：0 步
    assert plan_moves([Bag(0)], "reserve", km)[0] == 0
    # 2) 单袋（无处可搬）：0 步且指定“无需整理”
    b = Bag(0)
    add_to_bag(b, 0, 32, km)
    assert plan_moves([b], "reserve", km)[:2] == (0, "无需整理")
    # 3) 两袋、同类各一半：应合并成 1 步（把一袋搬进另一袋）
    b0, b1 = Bag(0), Bag(1)
    add_to_bag(b0, 0, 16, km)
    add_to_bag(b1, 0, 16, km)
    assert plan_moves([b0, b1], "reserve", km)[0] == 1, plan_moves([b0, b1], "reserve", km)
    # 4) 预留不算：K=8 时 R=2
    bags = [Bag(i) for i in range(8)]
    assert len(reserve_slots(bags, km)) == 2
    print("selftest OK")


def manual_run(K, strategy, key_max, rng, threshold=2, max_ops=3000):
    """从空袋开始逐次塞入；每次塞入后若「整理要 >= threshold 步」就手动整理一次。"""
    bags = [Bag(i) for i in range(K)]
    keys = list(key_max.keys())
    sorts = inserts = fails = 0
    worst_gap = 0  # 两次整理之间最多的塞入次数
    gap = 0
    while fails < 5 and inserts < max_ops:
        key = rng.choice(keys)
        cnt = rng.randint(1, key_max[key])
        before = sum(b.used for b in bags)
        write_insert(bags, key, cnt, strategy, key_max)
        if sum(b.used for b in bags) == before:
            fails += 1
            continue
        inserts += 1
        gap += 1
        steps, _, ordered = plan_moves(bags, strategy, key_max)
        if steps >= threshold:
            sorts += 1
            worst_gap = max(worst_gap, gap)
            gap = 0
            bags = apply_ordered(bags, ordered, key_max)
    return sorts, inserts, worst_gap


def manual_mode(K, km, seeds, threshold):
    print(f"[手动整理需求] K={K}  seeds={seeds}  阈值=整理需≥{threshold}步才算要整理")
    print(f"{'塞法':<24} | 平均塞入次数 | 手动整理次数 | 每 N 次塞入整理一次 | 最长不整理间隔")
    for name, strat, _ in [("旧·最空法 (worst-fit)", "worst", None),
                           ("当前·留空法 (reserve)", "reserve", None),
                           ("最紧法 (best-fit)", "best", None)]:
        tot_s = tot_i = tot_g = 0
        for seed in range(seeds):
            rng = random.Random((K, seed, 7))
            s, i, g = manual_run(K, strat, km, rng, threshold)
            tot_s += s
            tot_i += i
            tot_g = max(tot_g, g)
        per100 = 100.0 * tot_s / max(1, tot_i)
        per_sort = tot_i / tot_s if tot_s else float("inf")
        print(f"{name:<24} | {tot_i/seeds:12.1f} | {tot_s/seeds:12.2f} | "
              f"{per_sort:19.1f} | {tot_g:12d}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--K", type=int, default=8, help="袋子数量")
    ap.add_argument("--seeds", type=int, default=200)
    ap.add_argument("--buckets", type=int, default=50)
    ap.add_argument("--step", type=int, default=2, help="占用率扫描步长（百分点）")
    ap.add_argument("--n64", type=int, default=8, help="maxStack=64 的物品种类数")
    ap.add_argument("--n16", type=int, default=3, help="maxStack=16 的物品种类数")
    ap.add_argument("--n1", type=int, default=1, help="maxStack=1(不可堆叠) 的物品种类数")
    ap.add_argument("--simple", action="store_true", help="只画 3 条主曲线并中文批注")
    ap.add_argument("--perturb", type=float, default=0.0,
                    help="整理后扰动该比例的条目，再整理；统计的是「再整理」步数（如 0.2）")
    ap.add_argument("--manual", action="store_true", help="测「新塞入是否已乱到需要手动整理」")
    ap.add_argument("--threshold", type=int, default=2, help="手动整理阈值：整理需≥该步数才算")
    args = ap.parse_args()

    if args.selftest:
        selftest()
        return

    if args.manual:
        manual_mode(args.K, default_key_max(args.n64, args.n16, args.n1), args.seeds, args.threshold)
        return

    km = default_key_max(args.n64, args.n16, args.n1)
    K = args.K
    occ_grid = [x / 100 for x in range(5, 100, max(1, args.step))]

    if args.simple:
        runs = [
            # (曲线名, 排列生成策略, 整理策略)
            ("旧·最空法 (worst-fit)", "worst", "worst"),
            ("当前·留空法 (reserve)", "reserve", "reserve"),
            ("最紧法 (best-fit)", "best", "best"),
        ]
    else:
        runs = [
            # (曲线名, 排列生成策略, 整理策略)
            ("worst->worst (old)", "worst", "worst"),
            ("worst->best (regress)", "worst", "best"),
            ("worst->reserve (trans)", "worst", "reserve"),
            ("reserve->reserve (cur)", "reserve", "reserve"),
            ("best->best (self)", "best", "best"),
        ]

    R = len(reserve_slots([Bag(i) for i in range(K)], km))
    mode_txt = f"整理→动{args.perturb:.0%}→再整理" if args.perturb > 0 else "整理步数"
    print(f"K={K}  R(reserve)={R}  seeds={args.seeds}  keys={len(km)}  [{mode_txt}]")
    probes = [0.1, 0.3, 0.5, 0.7, 0.9]
    print(f"{'curve':<24} | " + ("再整理" if args.perturb > 0 else "整理")
          + " steps at p = " + " ".join(f"{p:.1f}" for p in probes))

    tag = ("simple_" if args.simple else "") + f"K{K}_keys{len(km)}"
    if args.perturb > 0:
        tag += f"_perturb{int(round(args.perturb * 100))}"
    csv_path = f"tools/sort_moves_{tag}.csv"
    png_path = f"tools/sort_moves_{tag}.png"

    results = {}
    with open(csv_path, "w", newline="") as f:
        wr = csv.writer(f)
        wr.writerow(["curve", "occupancy", "first_steps", "second_steps"])
        for name, arrange, sorter in runs:
            rows = sweep(K, args.seeds, occ_grid, km, arrange, [sorter], args.perturb)[sorter]
            xs, y1, y2 = aggregate(rows, args.buckets)
            yv = y2 if args.perturb > 0 else y1
            results[name] = (xs, y1, y2)
            for x, a, b in zip(xs, y1, y2):
                wr.writerow([name, f"{x:.4f}", f"{a:.3f}", f"{b:.3f}"])
            def near(p):
                return min(range(len(xs)), key=lambda i: abs(xs[i] - p))
            line = " ".join(f"{yv[near(p)]:6.1f}" for p in probes)
            # 在分桶均值（= E[步数 | p] 的估计）上拟合，样本量 = seeds × 网格点数
            fit = fit_curve(xs, yv)
            fit_txt = (f"   f(p) ≈ {fit[0]:.2f}/(1-p) + {fit[1]:.2f}·p + {fit[2]:.2f}·p²  "
                       f"(R²={fit[3]:.3f}, 每桶≈{max(1, len(rows)//max(1,len(xs)))}样本)"
                       if fit else "")
            print(f"{name:<24} | {line}{fit_txt}")

    try:
        import matplotlib
        matplotlib.use("Agg")
        from matplotlib import font_manager
        for fp in ("/usr/share/fonts/google-noto-sans-cjk-fonts/NotoSansCJK-Regular.ttc",
                   "/usr/share/fonts/wqy-zenhei-fonts/wqy-zenhei.ttc"):
            try:
                font_manager.fontManager.addfont(fp)
            except Exception:
                pass
        matplotlib.rcParams["font.sans-serif"] = ["Noto Sans CJK SC", "WenQuanYi Zen Hei", "DejaVu Sans"]
        matplotlib.rcParams["axes.unicode_minus"] = False
        import matplotlib.pyplot as plt

        if args.simple:
            fig, ax = plt.subplots(figsize=(10, 6))
            colors = {"旧·最空法 (worst-fit)": "#2e7d32",
                      "当前·留空法 (reserve)": "#ef6c00",
                      "最紧法 (best-fit)": "#c62828"}
            for name, (xs, y1, y2) in results.items():
                yv = y2 if args.perturb > 0 else y1
                ax.plot(xs, yv, marker="o", linewidth=2, color=colors.get(name), label=name)
            if args.perturb > 0:
                ax.set_title(f"整理后动 {args.perturb:.0%}，再整理要搬几步？（K={K} 个袋子）", fontsize=13)
                ax.set_ylabel("「再整理」的平均步数")
            else:
                ax.set_title(f"整理一次要搬几步？——三种「塞法」对比（K={K} 个袋子）", fontsize=13)
                ax.set_ylabel("一键整理的平均步数")
            ax.set_xlabel("袋子装满程度  p（0=全空，1=全满）")
            ax.grid(True, alpha=0.3)
            ax.legend(fontsize=11)
            if args.perturb > 0:
                top = ("把 20% 的物品按「每种 5 个一捆」打散后再整理：三条几乎重合 →\n"
                       "打散会破坏聚堆，重新聚堆的成本远大于「塞法」的差别")
            else:
                ann = {
                    "旧·最空法 (worst-fit)": ("天生就整齐", 0.62, 8),
                    "当前·留空法 (reserve)": ("留空袋给工具", 0.7, 16),
                    "最紧法 (best-fit)": ("塞得最紧", 0.5, 26),
                }
                for name, (txt, tx, ty) in ann.items():
                    ax.annotate(txt, xy=(tx, ty), fontsize=10, color=colors.get(name))
                top = "越接近装满，越要「先把挡路的挪开」→ 步数飙涨（三条都如此）"
            ax.text(0.02, 0.98, top, transform=ax.transAxes, va="top", fontsize=9,
                    color="#555555")
            fig.tight_layout()
        else:
            fig, ax = plt.subplots(1, 2, figsize=(13, 5.2))
            for name, (xs, y1, y2) in results.items():
                ax[0].plot(xs, y1, marker="o", label=name)
                ax[1].plot(xs, y2, marker="o", label=name)
            ax[0].set_title("first sort steps")
            ax[1].set_title("second sort steps (idempotence check)")
            for a in ax:
                a.set_xlabel("bag occupancy  p = used / (K*64)")
                a.set_ylabel("steps (avg)")
                a.grid(True, alpha=0.3)
                a.legend(fontsize=8)
            fig.suptitle(f"Better Bundle: sort steps vs occupancy (K={K})")
            fig.tight_layout()
        fig.savefig(png_path, dpi=140)
        print("saved", png_path)
    except Exception as e:  # noqa
        print("plot skipped:", e)


if __name__ == "__main__":
    main()
