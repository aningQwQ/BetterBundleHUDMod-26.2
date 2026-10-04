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

    private static final class Chunk {
        final ItemKey key;
        final int count;
        final int weight;

        Chunk(ItemKey key, int count, int weight) {
            this.key = key;
            this.count = count;
            this.weight = weight;
        }
    }

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

        List<PlannedMove> ordered = validPrefix(live, moves);
        if (ordered.isEmpty()) return PlanResult.fail("没有可安全执行的整理步骤");

        List<BagModel> locked = new ArrayList<>();
        for (BagModel b : vm.bags) {
            if (b.locked) locked.add(b);
        }
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

        // 每种物品的总数，以及“当前各袋里有多少”
        Map<ItemKey, Integer> totals = new LinkedHashMap<>();
        Map<ItemKey, Map<BagModel, Integer>> curCount = new LinkedHashMap<>();
        for (BagModel b : vm.bags) {
            for (BagEntry e : b.entries) {
                if (!e.movable() || e.count <= 0) continue;
                totals.merge(e.key, e.count, Integer::sum);
                curCount.computeIfAbsent(e.key, k -> new IdentityHashMap<>()).merge(b, e.count, Integer::sum);
            }
        }
        if (totals.isEmpty()) return empty;

        // home(t) = 当前含该物品最多的袋子（平手取袋序最小）
        Map<ItemKey, BagModel> home = new LinkedHashMap<>();
        for (ItemKey key : totals.keySet()) {
            Map<BagModel, Integer> cc = curCount.getOrDefault(key, Map.of());
            BagModel best = null;
            int bestN = -1;
            for (BagModel b : bags) {
                int n = cc.getOrDefault(b, 0);
                if (n > bestN) {
                    best = b;
                    bestN = n;
                }
            }
            home.put(key, best);
        }

        // 方案B：home 优先 + 固定溢出（少动、聚堆）。
        Map<BagModel, Map<ItemKey, Integer>> homeTarget =
                packByHome(bags, capBase, totals, perByKey, maxStackByKey, home);

        // 不动点校验：在结果上重算 home，一致则采纳；否则回退到 ID 规范形（稳定、不倒腾）。
        if (isFixedPoint(bags, homeTarget, home)) {
            return homeTarget;
        }
        return assignTargetsCanonical(bags, capBase, totals, perByKey, maxStackByKey);
    }

    /** home 优先、固定溢出顺序（从 home 起按袋序循环一圈）。 */
    private Map<BagModel, Map<ItemKey, Integer>> packByHome(
            List<BagModel> bags,
            Map<BagModel, Integer> capBase,
            Map<ItemKey, Integer> totals,
            Map<ItemKey, Integer> perByKey,
            Map<ItemKey, Integer> maxStackByKey,
            Map<ItemKey, BagModel> home) {

        // 类型顺序：先按 home 的袋序，再按物品 ID（确定性）
        List<ItemKey> keys = new ArrayList<>(totals.keySet());
        keys.sort(Comparator
                .comparingInt((ItemKey k) -> bags.indexOf(home.get(k)))
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

    /** 在目标摆放上重算 home，与给定 home 一致则为不动点。 */
    private boolean isFixedPoint(List<BagModel> bags,
                                 Map<BagModel, Map<ItemKey, Integer>> target,
                                 Map<ItemKey, BagModel> home) {
        for (Map.Entry<ItemKey, BagModel> en : home.entrySet()) {
            BagModel best = null;
            int bestN = -1;
            for (BagModel b : bags) {
                int n = target.getOrDefault(b, Map.of()).getOrDefault(en.getKey(), 0);
                if (n > bestN) {
                    best = b;
                    bestN = n;
                }
            }
            if (best != en.getValue()) return false;
        }
        return true;
    }

    /** 与当前摆放无关的 ID 规范形：按物品 ID 排序 + Worst-Fit。 */
    private Map<BagModel, Map<ItemKey, Integer>> assignTargetsCanonical(
            List<BagModel> bags,
            Map<BagModel, Integer> capBase,
            Map<ItemKey, Integer> totals,
            Map<ItemKey, Integer> perByKey,
            Map<ItemKey, Integer> maxStackByKey) {

        List<Map.Entry<ItemKey, Integer>> totalList = new ArrayList<>(totals.entrySet());
        totalList.sort(Comparator.comparing(e -> keyId(e.getKey())));

        List<Chunk> chunks = new ArrayList<>();
        for (Map.Entry<ItemKey, Integer> en : totalList) {
            ItemKey key = en.getKey();
            int total = en.getValue();
            int per = perByKey.getOrDefault(key, 1);
            int max = Math.max(1, maxStackByKey.getOrDefault(key, 64));
            int full = total / max;
            int rem = total % max;
            for (int i = 0; i < full; i++) chunks.add(new Chunk(key, max, max * per));
            if (rem > 0) chunks.add(new Chunk(key, rem, rem * per));
        }
        chunks.sort((a, b) -> {
            int byWeight = Integer.compare(b.weight, a.weight);
            if (byWeight != 0) return byWeight;
            return keyId(a.key).compareTo(keyId(b.key));
        });

        Map<BagModel, Map<ItemKey, Integer>> target = new IdentityHashMap<>();
        Map<BagModel, Integer> cap = new IdentityHashMap<>(capBase);
        for (BagModel b : bags) target.put(b, new LinkedHashMap<>());

        for (Chunk chunk : chunks) {
            BagModel best = null;
            int bestRemaining = -1;
            for (BagModel b : bags) {
                int c = cap.get(b);
                if (c < chunk.weight) continue;
                if (c > bestRemaining) {
                    best = b;
                    bestRemaining = c;
                }
            }
            if (best == null) continue;
            cap.put(best, cap.get(best) - chunk.weight);
            target.get(best).merge(chunk.key, chunk.count, Integer::sum);
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
        int guard = 0;
        while (guard++ < 512 && moves.size() < SortConfig.MAX_TOTAL_MOVES) {
            Diff diff = computeDiff(vm, target);
            if (diff.out.isEmpty() && diff.in.isEmpty()) return;

            // 直接：把源袋的整叠搬进有空间的接收袋（顶满）。遍历所有可接收袋，确保至少能放下 1 个。
            boolean moved = false;
            for (Map.Entry<BagModel, Map<ItemKey, Integer>> so : diff.out.entrySet()) {
                BagModel src = so.getKey();
                for (ItemKey key : so.getValue().keySet()) {
                    int per = Math.max(1, perByKey.getOrDefault(key, 1));
                    for (BagModel dst : diff.in.keySet()) {
                        if (diff.in.get(dst).getOrDefault(key, 0) <= 0) continue;
                        if (dst.freeWeight() < per) continue;
                        if (emitFill(src, key, dst, vm, perByKey, moves)) {
                            moved = true;
                            break;
                        }
                    }
                    if (moved) break;
                }
                if (moved) break;
            }
            if (moved) continue;

            // 僵局：某个“需要接收”的袋子是满的，把它里面“本就要搬走”的一叠挪到缓冲袋。
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
                if (emitFill(blocked, victim.key, buffer, vm, perByKey, moves)) {
                    moved = true;
                    break;
                }
            }
            if (!moved) return;
        }
    }

    private boolean emitFill(BagModel src, ItemKey key, BagModel dst,
                             InventoryModel vm, Map<ItemKey, Integer> perByKey,
                             List<PlannedMove> moves) {
        BagEntry se = src.findEntry(key);
        if (se == null || se.count <= 0) return false;
        int per = Math.max(1, perByKey.getOrDefault(key, 1));
        int take = Math.min(se.count, dst.freeWeight() / per);
        if (take <= 0) return false;   // 目标放不下哪怕 1 个 -> 不生成空动作
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
