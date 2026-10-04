package betterbundle.sort.plan;

import betterbundle.sort.SortConfig;
import betterbundle.sort.model.BagEntry;
import betterbundle.sort.model.BagModel;
import betterbundle.sort.model.InventoryModel;
import betterbundle.sort.model.ItemKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规划层：就地聚堆（一步合并、无腾挪、无均衡）。
 *
 * <p>规则：对出现多次的物品，把它散落在各袋的**整叠**，搬进“已装有该物品、且空余足够”的袋子。
 * 只做“一步、无连锁、收益明确”的合并：
 * <ul>
 *   <li>不搬动无关物品、不做多米诺撤离、不做全局重排、不做水位均衡；</li>
 *   <li>目标袋放不下整叠就跳过（宁可不动，也不拆散/细碎搬运）；</li>
 *   <li>没有可合并的（例如每种物品本来就只在一处）→ 0 步。</li>
 * </ul>
 *
 * <p>因此动作数 ≈ “能整叠合并的副本数”，少且可预期；空袋/半满袋原样保留，天然给写入留余量。
 */
public final class BundlePacker {

    private int nextId;

    public PlanResult plan(InventoryModel live) {
        nextId = 0;
        InventoryModel vm = clone(live);

        Map<ItemKey, Integer> perByKey = new LinkedHashMap<>();
        collectKeyInfo(vm, perByKey);

        List<PlannedMove> moves = new ArrayList<>();
        packLocal(vm, perByKey, moves);

        List<BagModel> locked = new ArrayList<>();
        for (BagModel b : vm.bags) {
            if (b.locked) locked.add(b);
        }

        if (moves.isEmpty()) {
            return PlanResult.ok(new SortPlan(new ArrayList<>(), locked)); // 无需整理
        }

        List<PlannedMove> ordered = validPrefix(live, moves);
        if (ordered.isEmpty()) return PlanResult.fail("空间不足或受限，无法进一步整理");
        return PlanResult.ok(new SortPlan(ordered, locked));
    }

    private void collectKeyInfo(InventoryModel vm, Map<ItemKey, Integer> perByKey) {
        for (BagModel b : vm.bags) {
            for (BagEntry e : b.entries) {
                if (!e.movable() || e.count <= 0) continue;
                int per = Math.max(1, e.weight / e.count);
                perByKey.putIfAbsent(e.key, per);
            }
        }
    }

    // ---------- 就地聚堆 ----------

    private void packLocal(InventoryModel vm, Map<ItemKey, Integer> perByKey, List<PlannedMove> moves) {
        // 出现多次（多个条目）的物品才需要合并
        Map<ItemKey, Integer> entryCount = new LinkedHashMap<>();
        for (BagModel b : vm.bags) {
            for (BagEntry e : b.entries) {
                if (!e.movable() || e.count <= 0) continue;
                entryCount.merge(e.key, 1, Integer::sum);
            }
        }

        List<ItemKey> keys = new ArrayList<>(entryCount.keySet());
        keys.removeIf(k -> entryCount.getOrDefault(k, 0) < 2);
        // 多副本优先处理；再按物品 ID 稳定
        keys.sort((a, b) -> {
            int byCount = Integer.compare(entryCount.getOrDefault(b, 0), entryCount.getOrDefault(a, 0));
            if (byCount != 0) return byCount;
            return keyId(a).compareTo(keyId(b));
        });

        for (ItemKey key : keys) {
            int per = Math.max(1, perByKey.getOrDefault(key, 1));
            boolean progressed = true;
            while (progressed && moves.size() < SortConfig.MAX_TOTAL_MOVES) {
                progressed = false;

                // 宿主：已装有该物品、空余最大的袋子
                BagModel host = null;
                int hostFree = -1;
                for (BagModel b : vm.bags) {
                    if (b.locked) continue;
                    if (b.findEntry(key) == null) continue;
                    int free = b.freeWeight();
                    if (free >= per && free > hostFree) {
                        host = b;
                        hostFree = free;
                    }
                }
                if (host == null) break;

                // 找一个“整叠能放进宿主”的源条目
                BagEntry srcEntry = null;
                BagModel srcBag = null;
                for (BagModel b : vm.bags) {
                    if (b == host || b.locked) continue;
                    BagEntry e = b.findEntry(key);
                    if (e == null || e.count <= 0) continue;
                    if (e.weight <= hostFree) {
                        srcEntry = e;
                        srcBag = b;
                        break;
                    }
                }
                if (srcEntry == null) break;

                moves.add(new PlannedMove(nextId++,
                        MoveAction.to(srcBag.invSlot, key, srcEntry.count, host.invSlot)));
                applyExtract(srcBag, key, srcEntry.count, host);   // 整叠放入（源袋余量理论上为 0）
                progressed = true;
            }
        }
    }

    // ---------- 工具 ----------

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

    /** 逐条重放，返回能安全执行的最长前缀。 */
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

    private static String keyId(ItemKey key) {
        var rep = key.representative();
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rep.getItem())
                + "#" + net.minecraft.world.item.ItemStack.hashItemAndComponents(rep);
    }
}
