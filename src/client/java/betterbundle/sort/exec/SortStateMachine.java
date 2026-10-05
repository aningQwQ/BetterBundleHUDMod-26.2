package betterbundle.sort.exec;

import betterbundle.sort.SortConfig;
import betterbundle.sort.model.BagEntry;
import betterbundle.sort.model.BagModel;
import betterbundle.sort.model.BundleSnapshotBuilder;
import betterbundle.sort.model.InventoryModel;
import betterbundle.sort.net.BundlePacketSender;
import betterbundle.sort.net.BundleSignature;
import betterbundle.sort.plan.MoveAction;
import betterbundle.sort.plan.PlannedMove;
import betterbundle.sort.plan.SortPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 执行层：节流异步状态机（整叠移动）。
 *
 * <p>每次逻辑动作移动一个袋内条目（整叠）：取出→放入，然后等待服务端把源袋/目标袋更新到
 * 预期内容，再随机等 2~5 tick 发下一条。超时 1s 熔断回滚；玩家任意输入立即中止不回滚。
 */
public final class SortStateMachine {

    public enum State { IDLE, READY, WAIT_CONFIRM, ROLLBACK_PREPARE, ROLLBACK_WAIT, DONE, ABORTED }

    private static final SortStateMachine INSTANCE = new SortStateMachine();

    /** 运行时读取用户配置（高级选项：动作间隔 / 确认超时 / 单次动作上限）。 */
    private static betterbundle.config.BetterBundleConfig cfg() {
        return betterbundle.config.ModConfig.get();
    }

    private final Random random = new Random();
    private final List<PlannedMove> ordered = new ArrayList<>();
    private final Deque<MoveAction> history = new ArrayDeque<>();

    private State state = State.IDLE;
    private int actionIndex;
    private int waitTicks;
    private int delayTicks;
    private int activeContainerId = -1;
    private MoveAction rollbackPending;
    private String message = "";
    private int messageTicks;

    private Map<String, Integer> expectedSrc;
    private Map<Integer, Map<String, Integer>> expectedDsts;
    private final java.util.LinkedHashMap<Integer, Integer> expectedTakes = new java.util.LinkedHashMap<>();
    private int expectedSrcSlot = -1;

    private SortStateMachine() {}

    public static SortStateMachine get() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return state == State.READY || state == State.WAIT_CONFIRM
                || state == State.ROLLBACK_PREPARE || state == State.ROLLBACK_WAIT;
    }

    public State state() {
        return state;
    }

    public int progressDone() {
        return actionIndex;
    }

    public int progressTotal() {
        return ordered.size();
    }

    public String message() {
        return messageTicks > 0 ? message : "";
    }

    /** 返回空串表示可开始；否则为拒绝原因。 */
    public String start(SortPlan plan) {
        if (isRunning()) return "整理进行中";
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return "未进入世界";
        if (betterbundle.util.CreativeGuard.isCreative(player, client.gui.screen())) return "创造模式暂不支持整理";
        if (!player.containerMenu.getCarried().isEmpty()) return "请先放下光标上的物品";

        // 用「动作次数」衡量工作量：每个动作成本固定（发包+确认+节流），
        // 与它搬运多少物品无关。避免用“整叠数量之和”这种被放大的假指标误拒。
        if (plan.moves().size() > cfg().maxTotalMoves) return "操作次数过多，请分批整理";

        ordered.clear();
        ordered.addAll(plan.moves());
        history.clear();
        actionIndex = 0;
        waitTicks = 0;
        delayTicks = 0;
        rollbackPending = null;
        clearExpectation();
        activeContainerId = player.containerMenu.containerId;
        message = "";
        messageTicks = 0;
        state = ordered.isEmpty() ? State.DONE : State.READY;
        if (ordered.isEmpty()) setMessage("无需整理");
        return "";
    }

    public void abortByUser() {
        if (!isRunning()) return;
        clearExpectation();
        state = State.ABORTED;
        setMessage("已取消");
    }

    public void onClientTick() {
        if (messageTicks > 0) messageTicks--;
        try {
            switch (state) {
                case READY -> tickReady();
                case WAIT_CONFIRM -> tickWaitConfirm();
                case ROLLBACK_PREPARE -> tickRollbackPrepare();
                case ROLLBACK_WAIT -> tickRollbackWait();
                default -> { }
            }
        } catch (Throwable t) {
            // 任何意外（槽位/容器变化、越界等）都优雅中止，绝不让异常冒泡导致客户端崩溃。
            clearExpectation();
            rollbackPending = null;
            state = State.ABORTED;
            setMessage("整理异常，已中止");
        }
    }

    private void tickReady() {
        if (actionIndex >= ordered.size()) {
            finish("整理完成");
            return;
        }
        if (delayTicks-- > 0) return;

        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null || player.containerMenu.containerId != activeContainerId) {
            abortByUser();
            return;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            abortByUser();
            return;
        }

        MoveAction action = ordered.get(actionIndex).action();
        if (!preconditionsHold(player, action)) {
            circuitBreak("执行前状态不符");
            return;
        }
        captureExpectation(player, action);
        if (!BundlePacketSender.sendMove(action)) {
            clearExpectation();
            circuitBreak("发包前置条件不满足");
            return;
        }
        waitTicks = 0;
        state = State.WAIT_CONFIRM;
    }

    private void tickWaitConfirm() {
        waitTicks++;
        if (isConfirmed()) {
            MoveAction action = ordered.get(actionIndex).action();
            // 回滚：每个收到物品的目标袋各生成一条反向移动（搬回源袋），best-effort。
            for (Map.Entry<Integer, Integer> e : new java.util.LinkedHashMap<>(expectedTakes).entrySet()) {
                history.push(new MoveAction(e.getKey(), action.key(), e.getValue(),
                        List.of(action.srcBagSlot())));
            }
            clearExpectation();
            actionIndex++;
            delayTicks = cfg().minDelayTicks
                    + random.nextInt(Math.max(1, cfg().maxDelayTicks - cfg().minDelayTicks + 1));
            state = State.READY;
        } else if (waitTicks > cfg().confirmTimeoutTicks) {
            clearExpectation();
            circuitBreak("服务端 1s 未确认");
        }
    }

    private void tickRollbackPrepare() {
        if (rollbackPending == null) {
            if (history.isEmpty()) {
                state = State.ABORTED;
                setMessage("已回滚");
                return;
            }
            rollbackPending = history.pop();
            waitTicks = 0;
            delayTicks = 0;
        }
        if (delayTicks-- > 0) return;

        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) {
            rollbackPending = null;
            state = State.ABORTED;
            return;
        }
        captureExpectation(player, rollbackPending);
        if (!BundlePacketSender.sendMove(rollbackPending)) {
            clearExpectation();
            rollbackPending = null;
            delayTicks = 0;
            return;
        }
        waitTicks = 0;
        state = State.ROLLBACK_WAIT;
    }

    private void tickRollbackWait() {
        waitTicks++;
        if (isConfirmed()) {
            clearExpectation();
            rollbackPending = null;
            delayTicks = cfg().minDelayTicks
                    + random.nextInt(Math.max(1, Math.min(3, cfg().maxDelayTicks - cfg().minDelayTicks + 1)));
            state = State.ROLLBACK_PREPARE;
        } else if (waitTicks > cfg().confirmTimeoutTicks) {
            clearExpectation();
            rollbackPending = null;
            delayTicks = 0;
            state = State.ROLLBACK_PREPARE;
        }
    }

    private void circuitBreak(String reason) {
        setMessage(reason);
        rollbackPending = null;
        delayTicks = 0;
        state = history.isEmpty() ? State.ABORTED : State.ROLLBACK_PREPARE;
    }

    private boolean preconditionsHold(Player player, MoveAction action) {
        InventoryModel model = BundleSnapshotBuilder.build(player);
        BagModel src = model.bySlot(action.srcBagSlot());
        if (src == null) return false;
        BagEntry entry = src.findEntry(action.key());
        if (entry == null || entry.count < action.count()) return false;
        int per = Math.max(1, entry.weight / entry.count);
        for (int dstSlot : action.dstBagSlots()) {
            BagModel dst = model.bySlot(dstSlot);
            if (dst != null && !dst.locked && dst.freeWeight() / per >= 1) return true;
        }
        return false;
    }

    /** 预期：源袋该物品 -count，目标袋该物品 +count。 */
    private void captureExpectation(Player player, MoveAction action) {
        expectedSrcSlot = action.srcBagSlot();
        String key = BundleSignature.keyOf(action.key().representative());

        expectedDsts = new java.util.LinkedHashMap<>();
        expectedTakes.clear();
        int remaining = action.count();
        for (int dstSlot : action.dstBagSlots()) {
            if (remaining <= 0) break;
            if (dstSlot == expectedSrcSlot) continue;
            ItemStack dstStack = stackAt(player, dstSlot);
            int cap = betterbundle.util.BundleContentsHelper.maxAcceptable(
                    dstStack, action.key().representative());
            int take = Math.min(remaining, cap);
            if (take <= 0) continue;
            Map<String, Integer> dstMap = BundleSignature.of(dstStack);
            dstMap.merge(key, take, Integer::sum);
            expectedDsts.put(dstSlot, dstMap);
            expectedTakes.put(dstSlot, take);
            remaining -= take;
        }

        // 只有真正放出去的部分离开了源袋，余量会返回源袋。
        int moved = action.count() - remaining;
        Map<String, Integer> srcMap = BundleSignature.of(stackAt(player, expectedSrcSlot));
        int srcCount = srcMap.getOrDefault(key, 0) - moved;
        if (srcCount > 0) srcMap.put(key, srcCount);
        else srcMap.remove(key);
        expectedSrc = srcMap;
    }

    private boolean isConfirmed() {
        if (expectedSrc == null || expectedDsts == null) return false;
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null || player.containerMenu.containerId != activeContainerId) return false;

        // 余量会返回源袋，因此确认时先要求光标已清空（所有放入都已完成）。
        if (!player.containerMenu.getCarried().isEmpty()) return false;

        if (!BundleSignature.of(stackAt(player, expectedSrcSlot)).equals(expectedSrc)) return false;
        for (Map.Entry<Integer, Map<String, Integer>> e : expectedDsts.entrySet()) {
            if (!BundleSignature.of(stackAt(player, e.getKey())).equals(e.getValue())) return false;
        }
        return true;
    }

    /** 安全读取槽位物品；越界/异常一律返回空，绝不抛出。 */
    private static ItemStack stackAt(Player player, int slotIndex) {
        if (player == null) return ItemStack.EMPTY;
        if (slotIndex < 0 || slotIndex >= player.containerMenu.slots.size()) return ItemStack.EMPTY;
        try {
            Slot slot = player.containerMenu.getSlot(slotIndex);
            return slot == null ? ItemStack.EMPTY : slot.getItem();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    private void clearExpectation() {
        expectedSrc = null;
        expectedDsts = null;
        expectedSrcSlot = -1;
    }

    private void finish(String text) {
        state = State.DONE;
        setMessage(text);
    }

    private void setMessage(String text) {
        message = text == null ? "" : text;
        messageTicks = 40;
    }
}
