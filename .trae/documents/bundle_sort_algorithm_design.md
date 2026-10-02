# 收纳袋（Bundle）一键整理 —— 数据结构与核心算法设计（已对齐版）

> 目标读者：实现本功能的智能体 / 开发者。
> 范围：仅客户端 UI + 网络包编排，不修改服务端，不做实时后台整理。
> 触发：用户点击面板上的「一键整理」按钮。

---

## 0. 已对齐的决策（Q&A 定稿，优先级最高）

以下为与需求方逐条确认后的最终口径，覆盖早前需求书中不一致的描述：

| # | 决策项 | 最终口径 |
|---|---|---|
| D1 | 空袋 / 内嵌袋 | **背包里的空收纳袋可用**（作为整理目标，放入后即变非空）；**袋子内容里内嵌的收纳袋条目一律不动**（不取出、不改动，只占位）。 |
| D2 | 整理目标 | **只重排袋内物品**：让同种物品聚在一起（跨袋合并同类条目）并填缝（把条目塞进更紧的袋子/袋子里的空隙）。**不把背包散落的非袋子物品收进袋子**。 |
| D3 | 不可堆叠物品 | **参与规划，按真实重量 64/64 计算**（单个即占满整袋）。 |
| D4 | 打断行为 | **不拦截任何输入**；玩家任何点击/拖拽/关窗/按键 → **立即中止，不回滚**。 |
| D5 | 遮罩 | **不要遮罩、不要锁定 UI**。 |
| D6 | 最大槽位 | **实测结论（见 §0.1）：`BundleContents` 没有条目数上限**，唯一硬约束是总重量 `≤ 1`（64/64）。`12/11` 只是 tooltip 网格显示上限，不是存储上限。 |
| D7 | 进度展示 | **在「一键整理」按钮上显示进度**，完成/失败后恢复按钮。 |
| D8 | 回滚范围 | 仅在**服务端确认超时（熔断）**时回滚；玩家主动打断不回滚（D4）。 |
| D9 | 中转 | 无 Swap，所有腾挪经**光标（cursor）**作为唯一中转，顺序等待确认。 |

### 0.1 实测结论（26.2 反编译字节码，权威）

已用 `javap` 反编译 `net.minecraft.world.item.component.BundleContents` 与 `net.minecraft.world.item.BundleItem`，确认：

1. **没有条目数/槽位上限。** `BundleContents.size()` 直接返回 `items.size()`；`BundleContents$Mutable.indexIsOutsideAllowedBounds(i)` 仅判断 `i<0 || i>=size`，选中索引可以是任意合法下标。因此 `ServerboundSelectBundleItemPacket` 的索引不受 12 限制。
2. **`12/11` 是纯显示值。** `BundleItem.MAX_SHOWN_GRID_ITEMS=12`、`OVERFLOWING_MAX_SHOWN_GRID_ITEMS=11`、网格 `4×3`；`BundleContents.getNumberOfItemsToShow()` 仅在 `size>12` 时返回 11，纯粹用于 tooltip 网格换行。
3. **唯一硬约束是总重量 ≤ 1。** `BundleContents.computeContentWeight()` 把每个条目的 `getWeight(item) × count` 累加，逻辑上限为 `Fraction.ONE`（=64/64）。
4. **内嵌袋（bundle in bundle）是允许的，且很有分量。** `BundleContents.BUNDLE_IN_BUNDLE_WEIGHT = Fraction.getFraction(1, 16)`；`getWeight()` 对含 `BUNDLE_CONTENTS` 的物品返回 `其内部内容重量 + 1/16`。即**空袋被塞进另一个袋时重量 = 4/64**（不是需求书说的 1/64）；装满的袋再嵌套必然超重放不进去。
5. 单条目重量：普通物品 = `1 / maxStackSize`，再乘 `count`。故 `16堆叠=4/64`、`64堆叠=1/64`、不可堆叠=64/64。

**对本设计的影响**：
- `W_FULL = 64` 是唯一容量约束；规划器中 `freeSlots()` 不再作为硬性拒绝条件（不限制条目数），只保留重量约束；UI 的「槽位 x/12」改为显示**条目数 `x`（无分母）**。
- 内嵌袋按 `innerWeight + 4`（64 单位制）计重，并作为**不可移动障碍**（D1）。
- `resolveMaxSlots()` 不再需要，删除。

---

## 1. 总体架构

```
betterbundle
├─ gui/                    现有：渲染 + 交互
│   ├─ BundlePanelRenderer         (+ 容量/槽位/锁死标注 + 整理按钮进度)
│   ├─ BundlePanelInteraction      (手动接口保留)
│   └─ SortButton                  (新增：一键整理按钮 + 进度态)
├─ sort/                   新增：算法与执行
│   ├─ model/  ItemClass, ItemKey, StackView, BagEntry, BagModel,
│   │           InventoryModel, BundleSnapshotBuilder
│   ├─ plan/   MoveAction, PlannedMove, SortPlan, SortError,
│   │           ConstrainedTbfdPlanner
│   ├─ dag/    DependencyGraph
│   ├─ exec/   SortStateMachine, CircuitBreaker, HistoryStack, RollbackController
│   └─ net/    BundlePacketSender, ServerConfirmTracker
└─ mixin/                  现有 + 新增
    └─ ClientPacketListenerMixin (新增：监听 SetSlot/SetContent，用于确认与熔断)
```

数据流：

```
点击「一键整理」
  └─ BundleSnapshotBuilder.build(player)              // 只采集袋内条目
       └─ ConstrainedTbfdPlanner.plan(model) ── DAG ──> SortPlan / SortError
            └─ SortStateMachine.start(plan)            // 按钮进入进度态
                 └─ 每 2~5 tick 执行 1 个逻辑动作
                      ├─ BundlePacketSender 发包（经光标中转）
                      ├─ ServerConfirmTracker 等待服务端确认
                      │    ├─ 确认 → 下一动作
                      │    └─ 超时 1s → 熔断 → RollbackController
                      └─ 任意玩家输入 → abort()（立即停止，不回滚）
```

---

## 2. 数据结构设计

> 全部为**规划期内存模型**。执行期只依据真实快照做前置校验，绝不单方面改写客户端 `ItemStack`（防幽灵物品）。

### 2.1 基础类型

```pseudo
const W_FULL = 64                       // 袋子总重量单位（唯一容量约束）
// 无条目数上限（实测 §0.1）：只要重量放得下，就能新增条目

enum ItemClass {
    NESTED_BUNDLE,   // 袋子内容里的收纳袋条目：障碍，禁止移动
    NON_STACKABLE,   // maxStack == 1（非袋）
    STACK16,         // maxStack in 2..16
    STACK64,         // maxStack in 17..64
}

// 合并身份：同物品且同组件（附魔/耐久/自定义数据不同不可合并）
record ItemKey(Item item, DataComponentPatch components)

// 袋内条目（bundle 内容中的一个 stack）
final class BagEntry {
    ItemKey key;
    int count;
    int maxStack;
    int weight;              // floor(64 * count / maxStack)
    int sourceIndex;         // 最近一次快照中的内容索引（仅参考，执行时动态重解析）
    boolean nestedBundle;    // true = 内嵌收纳袋：障碍
    boolean movable() { return !nestedBundle; }
}

// 虚拟袋子（含非空袋与可作为目标的空袋）
final class BagModel {
    final int invSlot;              // 该 bundle 物品所在背包槽（发包用）
    final ItemStack bundleStack;
    final List<BagEntry> entries;   // 顺序 = 内容索引
    int usedWeight;                 // Σ weight
    int slotCount;                  // entries.size()
    boolean locked;                 // 仅用于展示/排除目标，不代表整体中止
    String lockReason;

    boolean isEmptyBag() { return entries.isEmpty(); }
    int freeWeight() { return W_FULL - usedWeight; }
    int freeSlots()  { return Integer.MAX_VALUE; } // 无条目上限，恒可新增条目
    R findEntry(ItemKey k) { entries.firstWhere(e -> e.key.equals(k)) }
    int sameKeyCount(ItemKey k) { entries.count(e -> e.key.equals(k)) }
}

// 背包模型（只关心袋子，不采集 loose 物品 —— 见 D2）
final class InventoryModel {
    List<BagModel> bags;     // 非空袋 + 空袋（空袋 = 可用目标）
    int cursorSlot = -1;     // 光标；内部保留常量，用于表示中转
}
```

### 2.2 重量计算

- `weight = floor(64 * count / maxStack)`；`1/16→4`、`1/64→1`、`1/1→64`。
- **内嵌袋**：`weight = innerContentWeight + 4`（实测 `BUNDLE_IN_BUNDLE_WEIGHT=1/16`）；空内嵌袋 = 4。
- 部分堆叠按实际数量计，避免把半组当满组。
- 合并两条 `BagEntry` 时用 `long` 分子累加再取整（建议统一用「分子 ×64」内部表示），防止多次取整漂移。

### 2.3 障碍物规则（D1）

```pseudo
function classify(ItemStack s):
    if isBundle(s) && bundleContents(s).isEmpty(): return EMPTY_BUNDLE   // 可作目标
    if isBundle(s):                                return OPAQUE_BUNDLE  // 可作目标
    return byMaxStack(s.getMaxStackSize())

// BagEntry 层面（袋子内容里出现的条目）：
//   if isBundle(entry.stack): entry.nestedBundle = true   // 内嵌袋 → 不可动、不可作为合并源/目标
//   else: movable = true
```

---

## 3. 规划层：带障碍物的分层最佳适应递减（Constrained TBFD）

> 因 D2，规划输入 = 所有袋子中**可移动的 BagEntry**；目标 = 所有袋子（含空袋）的容量。背包散落物品不参与。

### 3.1 输入输出

```pseudo
record PlannedMove(MoveId id, MoveAction action) {}
class SortPlan {
    List<PlannedMove> orderedMoves;   // 已拓扑排序
    List<BagModel>    lockedBags;     // 用于展示锁死警告
    int               totalMoves;
}
```

### 3.2 主流程

```pseudo
function plan(InventoryModel m):
    // ---- 1. 初始化虚拟状态 ----
    for b in m.bags:
        b.usedWeight = Σ e.weight
        b.slotCount  = b.entries.size()

    vm    = m.deepCopy()
    moves = []
    dag   = new DependencyGraph()

    // ---- 2. 收集可移动条目（排除内嵌袋），按重量递减分层 ----
    allEntries = vm.bags.flatMap(b -> b.entries.filter(movable))
                              .sortedByDesc(e -> e.weight)
    // 记录每个条目的“理想归属键”，用于判断是否需要移动

    // ---- 3. 分层最佳适应递减 ----
    for e in allEntries:
        if isAlreadyOptimal(vm, e): continue
        placeEntry(e, vm, moves, dag)

    // ---- 4. 依赖拓扑排序 + 环检测 ----
    order = dag.topoSort()
    if order == null: return SortError("检测到循环依赖（死锁），已中止整理")

    // ---- 5. 执行期可行性复核（虚拟模型走一遍）----
    if !simulateExecutable(order.map(id -> moves[id]), vm):
        return SortError("空间不足或中转受阻，已中止整理")

    return SortPlan(reorder(moves, order), vm.lockedBags(), moves.size())
```

### 3.3 目标选择（最佳适应）

```pseudo
function placeEntry(entry, vm, moves, dag) -> OK | FAILED:
    w = entry.weight
    src = vm.bagContaining(entry)

    // 候选目标：非锁定、非内嵌袋、且能容纳 w
    cands = vm.bags.filter(b -> !b.locked && b.freeWeight() >= w)
                    .filter(b -> b != src || b.freeSlots() >= 0)   // 袋内合并亦允许
    // 1) 同种优先：把 entry 合并进已有同 key 的袋子（减少条目、聚合同类）
    same = cands.filter(b -> b.hasSameKey(entry.key) && b != src)
    if same.nonEmpty():
        best = bestFit(same, after = b.freeWeight() - w)   // 放下后剩余最小
        return moveEntry(entry, src, best, vm, moves, dag)

    // 2) 填缝优先：放进去以后剩余重量最小的非空袋（把条目塞进更紧的袋）
    nonEmpty = cands.filter(b -> !b.isEmptyBag())
    if nonEmpty.nonEmpty() && src.isEmptyAfterRemoval(entry):
        // 仅为“清空源袋”或“填缝”而移动，避免无意义搬运
        best = bestFit(nonEmpty, after = b.freeWeight() - w)
        if best.freeSlots() > 0 || best.hasSameKey(entry.key):
            return moveEntry(entry, src, best, vm, moves, dag)

    // 3) 空袋兜底：只有当源袋需要腾空槽位给同类合并时才用空袋
    empty = cands.filter(b -> b.isEmptyBag())
    if empty.nonEmpty() && shouldUseEmptyBag(entry, vm):
        return moveEntry(entry, src, empty.first(), vm, moves, dag)

    // 4) 需要腾空间：撤离目标袋中最轻的可移动条目
    //    （目标 = 同种袋或最紧袋；撤离产生依赖边）
    return tryEvacuateAndPlace(entry, vm, moves, dag, depth = 0)
```

### 3.4 撤离与依赖边（无 Swap，经光标）

```pseudo
function tryEvacuateAndPlace(entry, vm, moves, dag, depth):
    if depth > MAX_EVAC_DEPTH: return FAILED
    target = chooseTargetNeedingRoom(vm, entry)
    if target == null: return FAILED

    entries = target.entries.filter(movable).sortedAsc(weight)   // 轻的先搬
    if entries.isEmpty(): return FAILED                          // 整袋被内嵌袋占死
    victim = entries.first()

    // 为 victim 找落点；优先同种袋，其次最佳适应，最后空袋
    dest = findDestination(vm, victim, exclude = target)
    if dest == null:
        if tryEvacuateAndPlace(victim, vm, moves, dag, depth + 1) == FAILED:
            return FAILED
        dest = findDestination(vm, victim, exclude = target)
    if dest == null: return FAILED

    mvE = new PlannedMove(newId(), new Extract(target.invSlot, victim.key, victim.count, dest))
    moves.add(mvE)
    // 边：先搬出 victim，才能往 target 放 entry
    pendingInserts(target).forEach(mvI -> dag.addEdge(mvE.id, mvI.id))
    applyVirtualMove(vm, target, victim, dest)
    return placeEntry(entry, vm, moves, dag)     // 腾出后重试
```

### 3.5 同类合并（核心收益）

```pseudo
function mergeFragments(vm, moves, dag):
    for key in distinctKeys(vm.bags) where key not nestedBundle:
        owners = vm.bags.filter(b -> b.hasSameKey(key))
        if owners.size() <= 1: continue
        sink = argmax(owners, b -> b.freeWeight() + capacityFor(key))  // 承袋者
        for src in owners.filter(b -> b != sink):
            e = src.findEntry(key)
            mvE = new PlannedMove(newId(), new Extract(src.invSlot, key, e.count, sink))
            moves.add(mvE)
            applyVirtualMove(vm, src, e, sink)
            // 同袋多次取出：执行期动态重解析索引（§5.4），无需强边
```

---

## 4. 编排层：依赖图（DAG）与死锁检测

### 4.1 依赖图

```pseudo
final class DependencyGraph {
    Map<MoveId, PlannedMove> nodes;
    Map<MoveId, Set<MoveId>> edges;   // u -> v : u 必须在 v 之前

    void addEdge(MoveId before, MoveId after) {
        if (before == after) return;
        edges.computeIfAbsent(before).add(after);
    }

    List<MoveId> topoSort() {          // Kahn；稳定顺序 = 生成顺序
        indeg = computeInDegree();
        queue = nodes.filter(indeg == 0) as deque;
        out = [];
        while queue.nonEmpty():
            u = queue.poll(); out.add(u);
            for v in edges[u]: if (--indeg[v] == 0) queue.add(v);
        return (out.size() == nodes.size()) ? out : null;   // null = 有环
    }
}
```

### 4.2 环 / 死锁语义

- **环**：`topoSort()` 返回 `null` → 直接中止并提示 `"检测到循环依赖（死锁），已中止整理"`。
- **资源受阻**：拓扑有序但某步无合法落点（目标袋被内嵌袋占死、所有袋锁定、光标物品无处安放）→ 规划期 `simulateExecutable` 提前发现，返回 `"空间不足或中转受阻，已中止整理"`。
- **内嵌袋**：`entries.filter(movable)` 天然排除，绝不生成以它为源的 Extract，也绝不把它选为目标。

### 4.3 前置校验

```pseudo
function simulateExecutable(moves, vm):
    for mv in moves:
        switch mv.action:
            Insert(srcBag, dstBag, key, n):
                b = vm.bagBySlot(dstBag)
                if b.locked || b.freeWeight() < weightOf(key, n): return false
                if b.freeSlots() <= 0 && !b.hasSameKey(key): return false
                applyInsert(vm, ...)
            Extract(bag, key, n, dest):
                b = vm.bagBySlot(bag)
                if !b.hasSameKey(key): return false
                if !vm.canAccept(dest, key, n): return false
                applyExtract(vm, ...)
    return true
```

---

## 5. 执行层：节流异步状态机（Throttled FSM）

> 无遮罩、无 UI 锁（D4/D5）。按钮只反映进度。

### 5.1 状态机

```pseudo
enum SortState { IDLE, PLANNING, READY, WAIT_CONFIRM, ROLLBACK, DONE, ABORTED }

final class SortStateMachine {
    SortPlan plan;
    Deque<PlannedMove> queue;
    HistoryStack history;
    ServerConfirmTracker confirm;
    SortState state = IDLE;
    int waitTicks, delayTicks;
    static final int MIN_DELAY = 2, MAX_DELAY = 5;
    static final int CONFIRM_TIMEOUT = 20;   // 1s @20tps

    void start(SortPlan p) {
        plan = p; queue = new ArrayDeque<>(p.orderedMoves);
        history.clear(); state = READY; delayTicks = 0;
        SortButton.setProgress(0, p.totalMoves);   // 按钮进入进度态
    }

    void onClientTick() {
        switch (state) {
            case READY:
                if (queue.isEmpty()) {
                    state = DONE;
                    SortButton.finish("整理完成");
                    return;
                }
                if (delayTicks-- > 0) return;
                PlannedMove next = queue.peek();
                if (!preconditionsHold(next, liveSnapshot())) {
                    circuitBreaker("执行前状态与规划不符"); return;
                }
                BundlePacketSender.send(next.action);   // 1 个逻辑动作 = 1~N 个底层包
                waitTicks = 0; state = WAIT_CONFIRM; return;

            case WAIT_CONFIRM:
                waitTicks++;
                if (confirm.confirmed(next)) {
                    history.push(next, inverseOf(next));
                    queue.poll();
                    SortButton.setProgress(plan.totalMoves - queue.size(), plan.totalMoves);
                    delayTicks = MIN_DELAY + RNG.nextInt(MAX_DELAY - MIN_DELAY + 1);
                    state = READY;
                } else if (waitTicks > CONFIRM_TIMEOUT) {
                    circuitBreaker("服务端 1s 未确认");
                }
                return;

            case ROLLBACK:
                rollback.tick();
                return;
        }
    }

    // D4：任意玩家输入触发，立即停止，不回滚
    void abortByUser() {
        if (state == IDLE || state == DONE || state == ABORTED) return;
        queue.clear();
        state = ABORTED;
        SortButton.finish("已取消");
    }
}
```

### 5.2 打断接入（D4）

在现有 mixin 事件入口（`AbstractContainerScreenMixin` / `AbstractRecipeBookScreenMixin`）的 HEAD 处调用：

```pseudo
if (SortStateMachine.isRunning()) SortStateMachine.abortByUser();
// 注意：不要 cir.setReturnValue(true) —— 玩家操作要照常放行
```

覆盖事件：`mouseClicked`、`mouseReleased`、`mouseDragged`、`mouseScrolled`、`keyPressed`，以及 `onClose`（关窗也视为打断，立即停止但**不回滚**）。

### 5.3 节流与顺序

- 每个逻辑动作只发一次，之后必须等确认，再随机等 `2~5 tick` 发下一个（`40~100ms` 节流，抗反作弊）。
- 同一逻辑动作内的多个底层包可同 tick 连续发送（如「选中 → 取出 → 放入」），共同只算一次动作、等一次确认。
- 禁止同 tick 处理多个逻辑动作。

### 5.4 防幽灵物品 + 动态索引

```pseudo
@Mixin(ClientPacketListener.class)
class ClientPacketListenerMixin {
    @Inject(method="handleContainerSetSlot", at=@At("TAIL"))
    void onSetSlot(ClientboundContainerSetSlotPacket p, CallbackInfo ci) {
        ServerConfirmTracker.INSTANCE.onSlotChanged(p.getContainerId(), p.getSlot(), p.getItem());
    }
    @Inject(method="handleContainerSetContent", at=@At("TAIL"))
    void onSetContent(ClientboundContainerSetContentPacket p, CallbackInfo ci) {
        ServerConfirmTracker.INSTANCE.onFullSync(p.getContainerId(), p.getItems());
    }
}

// ServerboundSelectBundleItemPacket 的索引随取出会漂移 → 每次执行前动态解析
function currentIndexInBundle(bagSlot, key):
    live = player.containerMenu.getSlot(bagSlot).getItem()
    items = bundleContents(live).itemCopyStream().toList()
    for i in 0..items.size()-1:
        if ItemKey.of(items[i]).equals(key): return i
    return -1       // 已被移动 → 前置校验失败 → 熔断
```

### 5.5 熔断（D8）

```pseudo
function circuitBreaker(reason):
    state = ROLLBACK;
    rollback.start(history, reason);
    SortButton.finish("整理熔断，回滚中");
```

---

## 6. 容错层：反向操作回滚（仅熔断时，D8）

```pseudo
final class HistoryStack {
    Deque<ExecutedMove> stack;
    void push(PlannedMove mv, MoveAction inverse) { stack.push(new ExecutedMove(mv, inverse)); }
    ExecutedMove pop() { return stack.pop(); }
}

MoveAction inverseOf(Insert srcBag->dstBag, key, n):
    return new Extract(dstBag, key, n, dest = srcBag)
MoveAction inverseOf(Extract bag->dest, key, n):
    return new Insert(dest, bag, key, n)

final class RollbackController {
    void tick() {
        if (queue.isEmpty()) {
            if (history.size() == 0) { state = ABORTED; SortButton.finish("已回滚"); return; }
            queue.push(history.pop().inverse());     // LIFO：最后成功的先回滚
        }
        if (delayTicks-- > 0) return;
        MoveAction inv = queue.peek();
        if (!rollbackPreconditionsHold(inv)) { queue.poll(); return; }
        BundlePacketSender.send(inv);
        if (confirm.confirmedRollback(inv)) {
            queue.poll(); delayTicks = MIN_DELAY + RNG.nextInt(4);
        } else if (waitTicks > CONFIRM_TIMEOUT) {
            queue.poll();                            // 回滚单步失败则跳过，避免死循环
        }
    }
}
```

**红线**：回滚只使用「选中/取出/放入」原语，**绝不使用 NBT/组件覆盖**（防复制 Bug）。玩家主动打断**不**触发回滚（D4）。

---

## 7. UI 交互（无遮罩）

### 7.1 「一键整理」按钮 + 进度（D7）

- 位置：面板底部，与现有容量统计同排（`BundlePanelRenderer.getBundleStats`，`BundlePanelRenderer.java:373`）。
- 三态：
  - `IDLE`：绘制「整理」按钮。
  - `RUNNING`：按钮文本显示 `3/17`，可叠加一圈环形/条状进度；点击按钮 = 取消（同样走 `abortByUser`）。
  - `DONE / ABORTED`：短暂显示「完成 / 已取消」，约 1s 后恢复 `IDLE`。
- 失败：`SortError` 文案显示在按钮 tooltip（或按钮短暂变红），不弹窗、不遮罩。
- 点击接入点在 `AbstractContainerScreenMixin.onMouseClicked` 的面板分支，命中后 `cir.setReturnValue(true)`。

### 7.2 袋子容量 / 槽位 / 锁死警告

扩展 `BundleSlotEntry`（`BundlePanelRenderer.java:49`）旁挂 `BagModel`：

```
容量：usedWeight + "/64"              例如 40/64
条目：slotCount  （无分母；实测无条目上限）
锁死：locked == true → 袋子图标右上角警告三角
       tooltip = lockReason（"容量已满" / "含不可移动内嵌袋"）
```

```pseudo
for each visible bundle row:
    drawCapacityText(entry, entry.usedWeight + "/64")
    drawSlotText(entry, "条目 " + entry.slotCount)
    if entry.locked: drawWarningIcon(x + w - 10, y)
```

---

## 8. 关键辅助方法

```pseudo
function BundleSnapshotBuilder.build(player):
    m = new InventoryModel()
    for idx in (9..35) ++ (0..8):                 // 与现有 findEmptyPlayerSlot 顺序一致
        s = player.getInventory().getItem(idx)
        if s.isEmpty(): continue                   // loose 不参与（D2）
        if isBundle(s):
            slot = containerSlotOf(player, idx)
            b = BagModel.of(slot, s)
            // 内容条目 -> BagEntry；内嵌收纳袋标记为 obstacle
            for (i, contentStack) in enumerate(bundleContents(s).itemCopyStream()):
                entry = BagEntry.of(contentStack)
                entry.nestedBundle = isBundle(contentStack)
                entry.sourceIndex  = i
                b.entries.add(entry)
            m.bags.add(b)                          // 空袋与不空袋都加入（空袋可作目标）
    return m

function anyBagCanAccept(m):
    return m.bags.any(b -> !b.locked && (b.freeSlots() > 0 || b.freeWeight() > 0))
```

### 8.1 底层包发送（唯一出口，经光标中转）

```pseudo
function BundlePacketSender.send(a):
    switch a:
        Extract(bagSlot, key, n, destBagSlot):      // 袋 -> 光标 -> 另一袋
            idx = currentIndexInBundle(bagSlot, key)         // §5.4 动态解析
            if idx < 0: throw PreconditionChanged
            connection.send(new ServerboundSelectBundleItemPacket(bagSlot, idx))
            click(containerId, bagSlot,   PICKUP, button=1)  // 取出到光标
            click(containerId, destBagSlot, PICKUP, button=0)// 光标放入目标袋
        Insert(srcBagSlot, dstBagSlot, key, n):      // 等价于一次 Extract
            send(Extract(srcBagSlot, key, n, dstBagSlot))

function click(id, slot, input, button):
    connection.send(new ServerboundContainerClickPacket(
        id, /*stateId*/-1, (short)slot, (byte)button,
        input, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY))
```

> 与原 `BundlePanelInteraction.makeClickPacket`（`BundlePanelInteraction.java:153`）及 `ServerboundSelectBundleItemPacket` 用法保持一致；Shift 批量取出可参考 `handlePanelClick`（`BundlePanelInteraction.java:95`）。

---

## 9. 常量与配置

```java
// sort/SortConfig.java
public static final int W_FULL          = 64;   // 唯一容量约束
public static final int MIN_DELAY_TICKS = 2;
public static final int MAX_DELAY_TICKS = 5;
public static final int CONFIRM_TIMEOUT = 20;   // 1s
public static final int MAX_EVAC_DEPTH  = 3;
public static final int MAX_TOTAL_MOVES = 256;
```

---

## 10. 安全红线核对表

- [ ] 内嵌收纳袋条目绝不作为 Source / Target / 合并对象；只占位并计入锁定判断（D1）。
- [ ] 背包空袋可作整理目标（D1）。
- [ ] 只移动袋子内容里的可移动条目，不把背包散落物品收进袋子（D2）。
- [ ] 不可堆叠物品按真实 64/64 参与规划（D3）。
- [ ] 无 Swap：所有腾挪经光标中转，且顺序等待确认（D9）。
- [ ] 每个逻辑动作间隔随机 `2~5 tick`，无同 tick 多动作。
- [ ] 每个逻辑动作发出后，必须收到服务端 `SetSlot/SetContent` 且与预期一致才继续。
- [ ] 超 20 tick（1s）无确认 → 熔断 → 回滚；回滚只用取/放原语，无 NBT 覆盖（D8）。
- [ ] 玩家任意输入/关窗 → 立即中止，**不回滚**，且输入照常放行（D4/D5）。
- [ ] 无遮罩、无 UI 锁；进度只显示在按钮上（D5/D7）。
- [ ] 规划失败（有环/无空间/中转受阻）时中止并给出中文提示，不强行操作。
- [ ] 面板显示每袋 `容量/64` 与 `条目数`（无分母），锁死袋显示警告图标与原因。
- [ ] 容量约束只有重量；不存在条目数上限（实测 §0.1）。

---

## 11. 建议新增/修改文件清单

**新增**
- `src/client/java/betterbundle/sort/model/{ItemClass,ItemKey,BagEntry,BagModel,InventoryModel,BundleSnapshotBuilder}.java`
- `src/client/java/betterbundle/sort/plan/{MoveAction,PlannedMove,SortError,SortPlan,ConstrainedTbfdPlanner}.java`
- `src/client/java/betterbundle/sort/dag/DependencyGraph.java`
- `src/client/java/betterbundle/sort/exec/{SortStateMachine,CircuitBreaker,HistoryStack,RollbackController}.java`
- `src/client/java/betterbundle/sort/net/{BundlePacketSender,ServerConfirmTracker}.java`
- `src/client/java/betterbundle/sort/SortConfig.java`
- `src/client/java/betterbundle/gui/SortButton.java`
- `src/client/java/betterbundle/mixin/ClientPacketListenerMixin.java`

**修改**
- `BetterBundleMod.java`：注册客户端 tick 回调 `SortStateMachine::onClientTick`。
- `BundlePanelRenderer.java`：渲染容量/槽位/锁死图标与整理按钮进度。
- `AbstractContainerScreenMixin.java` / `AbstractRecipeBookScreenMixin.java`：整理按钮点击 + 打断钩子（放行输入）。
- `better-bundle.client.mixins.json`：注册新 mixin。
- `better-bundle.accesswidener`：若需访问 `ClientPacketListener`/`Minecraft.setScreen` 再补充。

---

## 12. 与早前需求书的差异汇总

1. **范围收窄**：不做「把背包散落物品塞进袋」，只重排袋内条目（D2）。
2. **空袋**：背包空袋可用作目标；仅**内嵌**在袋内容里的收纳袋条目不可动（D1）。
3. **不可堆叠**：按真实 64/64 参与，不特殊「当 1 算」（D3）。
4. **无遮罩/无锁**：去掉原设计的遮罩层与 UI 锁定；打断即中止不回滚（D4/D5/D8）。
5. **MAX_SLOTS**：实测确认无条目上限，唯一约束是重量 ≤64/64（D6/§0.1）；内嵌空袋重量为 4/64（1/16）而非 1/64。
6. **进度**：只显示在按钮上（D7）。
7. **中转**：以光标为唯一中转位，不再预留背包空槽。
