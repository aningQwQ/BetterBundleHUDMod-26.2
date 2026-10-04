package betterbundle.sort.plan;

import betterbundle.sort.SortConfig;
import betterbundle.sort.model.BagEntry;
import betterbundle.sort.model.BagModel;
import betterbundle.sort.model.InventoryModel;
import betterbundle.sort.model.ItemKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局终态规划器。
 *
 * <p>1) 目标打包：把每种物品按「每袋单类型上限 = maxStack」切成满堆 chunk + 一个 partial，
 * 用 best-fit-decreasing 把 chunk 装进袋子（优先放回它原来所在的袋子以减少搬运），得到理想终态。
 * 2) 差异：算出每个袋子各物品的「应搬出 / 应搬入」。
 * 3) 执行序列：只要某接收袋有空间就直接搬入（顶满）；全部卡住时，把某个满袋里“本就要搬走”的
 * 物品先挪到有空间的缓冲袋，打破僵局。整个过程在虚拟模型上逐步落子，保证每一步都真的可执行。
 */
public final class BundlePacker {

    private int nextId;

    public PlanResult plan(InventoryModel live) {
        nextId = 0;
        InventoryModel vm = clone(live);

        Map<ItemKey, Integer> perByKey = new LinkedHashMap<>();
        Map<ItemKey, Integer> maxStackByKey = new LinkedHashMap<>();
        collectKeyInfo(vm, perByKey, maxStackByKey);

        Map<BagModel, Map<ItemKey, Integer>> target = assignTargets(vm, perByKey, maxStackByKey);

        List<PlannedMove> moves = new ArrayList<>();
        realize(vm, target, perByKey, moves);

        List<BagModel> locked = new ArrayList<>();
        for (BagModel b : vm.bags) {
            if (b.locked) locked.add(b);
        }

        // 当前摆放已等于目标（无需搬动）→ “成功但空计划”，UI 显示“无需整理”。
        // 若与目标仍有差异却一步都推不动 → 明确报“受限”，避免把卡死误报成“无需整理”。
        if (moves.isEmpty()) {
            Diff d = computeDiff(vm, target);
            if (d.out.isEmpty() && d.in.isEmpty()) {
                return PlanResult.ok(new SortPlan(new ArrayList<>(), locked));
            }
            return PlanResult.fail("空间不足或受限，无法进一步整理");
        }

        List<PlannedMove> ordered = validPrefix(live, moves);
        if (ordered.isEmpty()) return PlanResult.fail("没有可安全执行的整理步骤");

        return PlanResult.ok(new SortPlan(ordered, locked));
    }

    // ---------- 1) 目标打包 ----------

    private void collectKeyInfo(InventoryModel vm,
                                Map<ItemKey, Integer> perByKey,
                                Map<ItemKey, Integer> maxStackByKey) {
        for (BagModel b : vm.bags) {
            for (BagEntry e : b.entries) {
                if (!e.movable() || e.count <= 0) continue;
                int per = Math.max(1, e.weight / e.count);
                perByKey.putIfAbsent(e.key, per);
                maxStackByKey.putIfAbsent(e.key, e.maxStack);
            }
        }
    }

    private Map<BagModel, Map<ItemKey, Integer>> assignTargets(
            InventoryModel vm,
            Map<ItemKey, Integer> perByKey,
            Map<ItemKey, Integer> maxStackByKey) {

        List<BagModel> bags = new ArrayList<>();
        Map<BagModel, Integer> capBase = new IdentityHashMap<>();
        for (BagModel b : vm.bags) {
            if (b.locked) continue;
            // 内嵌收纳袋等不可动条目仍占用该袋容量，先扣除。
            int reserved = 0;
            for (BagEntry e : b.entries) {
                if (!e.movable()) reserved += e.weight;
            }
            int c = SortConfig.W_FULL - reserved;
            if (c <= 0) continue;
            bags.add(b);
            capBase.put(b, c);
        }

        Map<BagModel, Map<ItemKey, Integer>> empty = new IdentityHashMap<>();
        if (bags.isEmpty()) return empty;

        // 总数 + 当前各袋数量
        Map<ItemKey, Integer> totals = new LinkedHashMap<>();
        Map<BagModel, Map<ItemKey, Integer>> state = new IdentityHashMap<>();
        for (BagModel b : bags) state.put(b, new LinkedHashMap<>());
        for (BagModel b : bags) {
            for (BagEntry e : b.entries) {
                if (!e.movable() || e.count <= 0) continue;
                totals.merge(e.key, e.count, Integer::sum);
                state.get(b).merge(e.key, e.count, Integer::sum);
            }
        }
        if (totals.isEmpty()) return empty;

        // 单一算法：home 归位 + 迭代到不动点。
        // 反复“按当前摆放算 home -> 按 home 打包”，直到再算一次结果不变（f(T)=T）。
        // 达到不动点后，整理完再整理就是 0 步，无需第二套算法兜底。
        Map<BagModel, Map<ItemKey, Integer>> target = null;
        for (int iter = 0; iter < 8; iter++) {
            Map<ItemKey, BagModel> home = computeHome(bags, capBase, totals, perByKey, state);
            target = packByHome(bags, capBase, totals, perByKey, maxStackByKey, home);
            Map<ItemKey, BagModel> home2 = computeHome(bags, capBase, totals, perByKey, target);
            if (home.equals(home2)) {
                return target;
            }
            state = target;
        }
        return target;   // 未收敛（罕见）：取最后一版，行为仍有界
    }

    /**
     * home(t) = 在“已经含有该物品”的袋子里，选**剩余空间最大**的那个
     * （这样它能把其余副本也收进来，尽量聚成一袋）；平手时取数量最多、再平手取袋序最小。
     */
    private Map<ItemKey, BagModel> computeHome(
            List<BagModel> bags,
            Map<BagModel, Integer> capBase,
            Map<ItemKey, Integer> totals,
            Map<ItemKey, Integer> perByKey,
            Map<BagModel, Map<ItemKey, Integer>> state) {

        // 每个袋子在 state 下的已用重量
        Map<BagModel, Integer> used = new IdentityHashMap<>();
        for (BagModel b : bags) {
            int w = 0;
            for (Map.Entry<ItemKey, Integer> en : state.getOrDefault(b, Map.of()).entrySet()) {
                int per = Math.max(1, perByKey.getOrDefault(en.getKey(), 1));
                w += en.getValue() * per;
            }
            used.put(b, w);
        }

        Map<ItemKey, BagModel> home = new LinkedHashMap<>();
        for (ItemKey key : totals.keySet()) {
            int total = totals.getOrDefault(key, 0);
            int per = Math.max(1, perByKey.getOrDefault(key, 1));

            BagModel absorber = null;   // 持有袋中能“吸收其余全部副本”的
            int absorberFree = -1;
            BagModel majority = null;
            int majorityN = -1;

            for (BagModel b : bags) {
                int n = state.getOrDefault(b, Map.of()).getOrDefault(key, 0);
                if (n <= 0) continue;
                int free = Math.max(0, capBase.get(b) - used.get(b));
                if (free >= (total - n) * per && free > absorberFree) {
                    absorber = b;
                    absorberFree = free;
                }
                if (n > majorityN) {
                    majority = b;
                    majorityN = n;
                }
            }

            if (absorber != null) {
                home.put(key, absorber);
                continue;
            }

            // 没有持有袋能吸收：优先合并进“有空位的非持有袋”（把同类聚成一袋）。
            BagModel consolidate = null;
            for (BagModel b : bags) {
                if (state.getOrDefault(b, Map.of()).getOrDefault(key, 0) > 0) continue;
                int free = Math.max(0, capBase.get(b) - used.get(b));
                if (free >= total * per) {
                    consolidate = b;
                    break;
                }
            }
            if (consolidate != null) home.put(key, consolidate);
            else if (majority != null) home.put(key, majority);
            else home.put(key, bags.get(0));
        }
        return home;
    }

    /** home 优先、固定溢出顺序（从 home 起按袋序循环一圈）。 */
    private Map<BagModel, Map<ItemKey, Integer>> packByHome(
            List<BagModel> bags,
            Map<BagModel, Integer> capBase,
            Map<ItemKey, Integer> totals,
            Map<ItemKey, Integer> perByKey,
            Map<ItemKey, Integer> maxStackByKey,
            Map<ItemKey, BagModel> home) {

        // 类型顺序：先按“总重量从大到小”（大类型优先占满整袋、聚堆），
        // 再按 home 袋序、物品 ID（确定性）。
        List<ItemKey> keys = new ArrayList<>(totals.keySet());
        keys.sort(Comparator
                .comparingInt((ItemKey k) -> -(totals.getOrDefault(k, 0)
                        * Math.max(1, perByKey.getOrDefault(k, 1))))
                .thenComparingInt(k -> bags.indexOf(home.get(k)))
                .thenComparing(BundlePacker::keyId));

        Map<BagModel, Map<ItemKey, Integer>> target = new IdentityHashMap<>();
        Map<BagModel, Integer> cap = new IdentityHashMap<>(capBase);
        for (BagModel b : bags) target.put(b, new LinkedHashMap<>());

        for (ItemKey key : keys) {
            int remaining = totals.getOrDefault(key, 0);
            if (remaining <= 0) continue;
            int per = Math.max(1, perByKey.getOrDefault(key, 1));
            int max = Math.max(1, maxStackByKey.getOrDefault(key, 64));

            BagModel h = home.get(key);
            int start = h != null ? Math.max(0, bags.indexOf(h)) : 0;
            for (int i = 0; i < bags.size() && remaining > 0; i++) {
                BagModel b = bags.get((start + i) % bags.size());
                int roomByMax = max - target.get(b).getOrDefault(key, 0);
                int capItems = cap.get(b) / per;
                int take = Math.min(remaining, Math.min(capItems, roomByMax));
                if (take <= 0) continue;
                cap.put(b, cap.get(b) - take * per);
                target.get(b).merge(key, take, Integer::sum);
                remaining -= take;
            }
        }
        return target;
    }

    /** 物品的稳定标识（用于与当前摆放无关的排序）。 */
    private static String keyId(ItemKey key) {
        var rep = key.representative();
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rep.getItem())
                + "#" + net.minecraft.world.item.ItemStack.hashItemAndComponents(rep);
    }

    // ---------- 3) 差异 + 落子 ----------

    private void realize(InventoryModel vm,
                         Map<BagModel, Map<ItemKey, Integer>> target,
                         Map<ItemKey, Integer> perByKey,
                         List<PlannedMove> moves) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        int guard = 0;
        while (guard++ < 512 && moves.size() < SortConfig.MAX_TOTAL_MOVES) {
            Diff diff = computeDiff(vm, target);
            if (diff.out.isEmpty() && diff.in.isEmpty()) return;
            if (!seen.add(stateSig(vm))) return;   // 状态重复 -> 防环，停止

            // 直接：搬进有空间的接收袋（要求“离目标差距”严格变小）。
            boolean moved = false;
            for (Map.Entry<BagModel, Map<ItemKey, Integer>> so : diff.out.entrySet()) {
                BagModel src = so.getKey();
                for (ItemKey key : so.getValue().keySet()) {
                    int per = Math.max(1, perByKey.getOrDefault(key, 1));
                    for (BagModel dst : diff.in.keySet()) {
                        if (diff.in.get(dst).getOrDefault(key, 0) <= 0) continue;
                        if (dst.freeWeight() < per) continue;
                        if (tryFill(src, key, dst, vm, perByKey, moves, diff, false)) {
                            moved = true;
                            break;
                        }
                    }
                    if (moved) break;
                }
                if (moved) break;
            }
            if (moved) continue;

            // 僵局：把某个满袋里“本就要搬走”的一叠挪到缓冲袋。
            // 允许“平移”（差距不变）的临时步来打破僵局；由上方的访问状态检测防止死循环。
            for (Map.Entry<BagModel, Map<ItemKey, Integer>> ie : diff.in.entrySet()) {
                BagModel blocked = ie.getKey();
                if (blocked.freeWeight() > 0) continue;
                BagEntry victim = null;
                for (BagEntry e : blocked.entries) {
                    if (!e.movable() || e.count <= 0) continue;
                    if (diff.out.getOrDefault(blocked, Map.of()).getOrDefault(e.key, 0) <= 0) continue;
                    victim = e;
                    break;
                }
                if (victim == null) continue;
                BagModel buffer = findBuffer(vm, diff, victim, blocked);
                if (buffer == null) continue;
                if (tryFill(blocked, victim.key, buffer, vm, perByKey, moves, diff, true)) {
                    moved = true;
                    break;
                }
            }
            if (!moved) return;
        }
    }

    /** 当前摆放的签名（用于检测重复状态、防止来回死循环）。 */
    private String stateSig(InventoryModel vm) {
        StringBuilder sb = new StringBuilder();
        for (BagModel b : vm.bags) {
            List<String> parts = new ArrayList<>();
            for (BagEntry e : b.entries) {
                if (e.movable() && e.count > 0) parts.add(keyId(e.key) + ":" + e.count);
            }
            parts.sort(null);
            sb.append(String.join(",", parts)).append('|');
        }
        return sb.toString();
    }

    /**
     * 尝试一次搬运。
     * allowFlat=false：只接受让「离目标差距」严格变小的动作（正常直填）。
     * allowFlat=true ：允许差距不变（临时平移），用于打破僵局。
     */
    private boolean tryFill(BagModel src, ItemKey key, BagModel dst,
                            InventoryModel vm, Map<ItemKey, Integer> perByKey,
                            List<PlannedMove> moves, Diff diff, boolean allowFlat) {
        BagEntry se = src.findEntry(key);
        if (se == null || se.count <= 0) return false;
        int per = Math.max(1, perByKey.getOrDefault(key, 1));
        int take = Math.min(se.count, dst.freeWeight() / per);
        if (take <= 0) return false;

        int over = diff.out.getOrDefault(src, Map.of()).getOrDefault(key, 0);
        int need = diff.in.getOrDefault(dst, Map.of()).getOrDefault(key, 0);
        int delta = (Math.abs(over - take) - over) + (Math.abs(need - take) - need);
        if (allowFlat ? delta > 0 : delta >= 0) return false;   // 距离不能变大

        moves.add(new PlannedMove(nextId++,
                MoveAction.to(src.invSlot, key, se.count, dst.invSlot)));
        applyExtract(src, key, take, dst);
        return true;
    }

    private BagModel findReceiver(Diff diff, ItemKey key) {
        BagModel best = null;
        for (BagModel b : diff.in.keySet()) {
            if (diff.in.get(b).getOrDefault(key, 0) <= 0) continue;
            if (b.freeWeight() <= 0) continue;
            if (best == null || b.freeWeight() < best.freeWeight()) best = b;
        }
        return best;
    }

    private BagModel findBuffer(InventoryModel vm, Diff diff, BagEntry victim, BagModel exclude) {
        BagModel wants = null;   // 正好需要该物品的袋子（缓冲同时就是进度）
        BagModel empty = null;
        for (BagModel b : vm.bags) {
            if (b == exclude || b.locked) continue;
            if (b.freeWeight() < victim.weight) continue;
            if (diff.in.getOrDefault(b, Map.of()).getOrDefault(victim.key, 0) > 0) {
                if (wants == null || b.freeWeight() < wants.freeWeight()) wants = b;
            } else if (b.isEmptyBag()) {
                if (empty == null) empty = b;
            }
        }
        return wants != null ? wants : empty;
    }

    // ---------- 差异计算 ----------

    private static final class Diff {
        final Map<BagModel, Map<ItemKey, Integer>> out = new IdentityHashMap<>();
        final Map<BagModel, Map<ItemKey, Integer>> in = new IdentityHashMap<>();
    }

    private Diff computeDiff(InventoryModel vm, Map<BagModel, Map<ItemKey, Integer>> target) {
        Diff d = new Diff();
        for (BagModel b : vm.bags) {
            Map<ItemKey, Integer> tgt = target.getOrDefault(b, Map.of());

            Map<ItemKey, Integer> cur = new LinkedHashMap<>();
            for (BagEntry e : b.entries) {
                if (!e.movable() || e.count <= 0) continue;
                cur.merge(e.key, e.count, Integer::sum);
            }

            LinkedHashMap<ItemKey, Integer> keys = new LinkedHashMap<>(cur);
            for (ItemKey k : tgt.keySet()) keys.putIfAbsent(k, 0);

            Map<ItemKey, Integer> outMap = new LinkedHashMap<>();
            Map<ItemKey, Integer> inMap = new LinkedHashMap<>();
            for (Map.Entry<ItemKey, Integer> en : keys.entrySet()) {
                int have = cur.getOrDefault(en.getKey(), 0);
                int want = tgt.getOrDefault(en.getKey(), 0);
                if (have > want) outMap.put(en.getKey(), have - want);
                else if (want > have) inMap.put(en.getKey(), want - have);
            }
            if (!outMap.isEmpty()) d.out.put(b, outMap);
            if (!inMap.isEmpty()) d.in.put(b, inMap);
        }
        return d;
    }

    private int currentCount(BagModel bag, ItemKey key) {
        int n = 0;
        for (BagEntry e : bag.entries) {
            if (e.movable() && e.sameKey(key)) n += e.count;
        }
        return n;
    }

    // ---------- 虚拟应用 / 校验 ----------

    private void applyExtract(BagModel src, ItemKey key, int count, BagModel dst) {
        BagEntry se = src.findEntry(key);
        if (se == null) return;
        int moved = Math.min(count, se.count);
        if (moved <= 0) return;
        int w = (int) Math.ceil((double) se.weight * moved / se.count);
        if (w <= 0) w = 1;

        se.count -= moved;
        se.weight = Math.max(0, se.weight - w);
        if (se.count <= 0) src.entries.remove(se);
        src.usedWeight = Math.max(0, src.usedWeight - w);

        BagEntry de = dst.findEntry(key);
        if (de != null) {
            de.count += moved;
            de.weight += w;
        } else {
            BagEntry ne = new BagEntry(se.stack.copy());
            ne.count = moved;
            ne.weight = w;
            dst.entries.add(ne);
        }
        dst.usedWeight += w;
    }

    private List<PlannedMove> validPrefix(InventoryModel live, List<PlannedMove> planned) {
        InventoryModel vm = clone(live);
        List<PlannedMove> valid = new ArrayList<>();
        for (PlannedMove m : planned) {
            MoveAction a = m.action();
            BagModel src = vm.bySlot(a.srcBagSlot());
            if (src == null) break;
            BagEntry se = src.findEntry(a.key());
            if (se == null || se.count < a.count()) break;
            int per = Math.max(1, se.weight / se.count);

            boolean hasRoom = false;
            for (int dstSlot : a.dstBagSlots()) {
                BagModel dst = vm.bySlot(dstSlot);
                if (dst != null && !dst.locked && dst != src && dst.freeWeight() / per >= 1) {
                    hasRoom = true;
                    break;
                }
            }
            if (!hasRoom) break;

            for (int dstSlot : a.dstBagSlots()) {
                BagModel dst = vm.bySlot(dstSlot);
                if (dst == null || dst.locked || dst == src) continue;
                se = src.findEntry(a.key());
                if (se == null) break;
                per = Math.max(1, se.weight / se.count);
                int take = Math.min(se.count, dst.freeWeight() / per);
                if (take <= 0) continue;
                applyExtract(src, a.key(), take, dst);
            }
            valid.add(m);
        }
        return valid;
    }

    private InventoryModel clone(InventoryModel live) {
        InventoryModel model = new InventoryModel();
        for (BagModel b : live.bags) {
            model.bags.add(new BagModel(b.invSlot, b.bundleStack));
        }
        return model;
    }
}
