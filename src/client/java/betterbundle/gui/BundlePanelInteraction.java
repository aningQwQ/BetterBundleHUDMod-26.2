package betterbundle.gui;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import betterbundle.util.BundleContentsHelper;

public final class BundlePanelInteraction {

    private static final int GLFW_MOD_SHIFT = 0x1;

    private BundlePanelInteraction() {}

    private static int gridX(int leftPos) {
        int pw = BundlePanelRenderer.panelWidth();
        int panelX = leftPos - pw - 4;
        return panelX + BundlePanelRenderer.PADDING
                + BundlePanelRenderer.CAT_BAR_WIDTH + 2
                + BundlePanelRenderer.SCROLL_BAR_WIDTH + 2;
    }

    private static int gridY(int topPos) {
        return topPos + BundlePanelRenderer.SEARCH_BAR_HEIGHT + 3 + BundlePanelRenderer.PADDING;
    }

    private static BundlePanelRenderer.FlatItem getClickedItem(double mouseX, double mouseY,
                                                                int leftPos, int topPos) {
        List<BundlePanelRenderer.BundleSlotEntry> bundles = BundlePanelRenderer.getBundles();
        if (bundles.isEmpty()) return null;

        List<BundlePanelRenderer.FlatItem> allItems = BundlePanelRenderer.buildFlatItemList(bundles);
        if (allItems.isEmpty()) return null;

        // Use filtered items to match rendered panel
        List<BundlePanelRenderer.FlatItem> items = BundlePanelRenderer.filterItems(allItems, BundlePanelRenderer.searchQuery);
        if (items.isEmpty()) return null;

        int gx = gridX(leftPos);
        int gy = gridY(topPos);

        int relX = (int) mouseX - gx;
        int relY = (int) mouseY - gy;

        int col = relX / (BundlePanelRenderer.SLOT_SIZE + BundlePanelRenderer.SLOT_SPACING);
        int row = relY / (BundlePanelRenderer.SLOT_SIZE + BundlePanelRenderer.SLOT_SPACING);

        if (col < 0 || col >= BundlePanelRenderer.COLUMNS) return null;
        if (row < 0 || row >= BundlePanelRenderer.VISIBLE_ROWS) return null;

        int flatIndex = (BundlePanelRenderer.getScrollOffset() + row) * BundlePanelRenderer.COLUMNS + col;
        if (flatIndex >= items.size()) return null;
        return items.get(flatIndex);
    }

    public static boolean handlePanelClick(double mouseX, double mouseY, int button, int modifiers,
                                            int leftPos, int topPos,
                                            net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        BundlePanelRenderer.FlatItem clicked = getClickedItem(mouseX, mouseY, leftPos, topPos);
        if (clicked == null) return false;

        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return false;

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        int bundleSlot = clicked.bundleSlot();
        int containerId = player.containerMenu.containerId;
        boolean shiftDown = (modifiers & GLFW_MOD_SHIFT) != 0;

        if (shiftDown) {
            boolean inContainer = !(screen instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen);
            int destSlot;
            if (button == 0 && inContainer) {
                destSlot = findEmptyContainerSlot(player);
                if (destSlot < 0) destSlot = findEmptyPlayerSlot(player);
            } else {
                destSlot = findEmptyPlayerSlot(player);
            }
            if (destSlot < 0) return true;

            // removeOne 取出的是「整个选中条目」：只需一次选中 + 右键，再放入目标槽。
            // 绝不能循环右键（光标非空后右键会走原版逻辑，把袋子本身拿起来）。
            connection.send(new ServerboundSelectBundleItemPacket(bundleSlot, -1));
            connection.send(new ServerboundSelectBundleItemPacket(bundleSlot, clicked.itemIndex()));
            connection.send(makeClickPacket(containerId, bundleSlot, (byte) 1));
            connection.send(makeClickPacket(containerId, destSlot, (byte) 0));
        } else {
            // 选中是 toggle 语义：先 -1 再设索引，保证确定选中。
            connection.send(new ServerboundSelectBundleItemPacket(bundleSlot, -1));
            connection.send(new ServerboundSelectBundleItemPacket(bundleSlot, clicked.itemIndex()));
            connection.send(makeClickPacket(containerId, bundleSlot, (byte) 1));
        }

        return true;
    }

    /** Find an empty slot in the open container (not the player inventory). */
    private static int findEmptyContainerSlot(Player player) {
        for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
            if (!slot.hasItem() && slot.container != player.getInventory()) {
                return slot.index;
            }
        }
        return -1;
    }

    /**
     * 空格批量塞入的「入队」入口。真正发包交给 {@link #onClientTick()} 里的状态机，
     * 一步步等待服务端确认后再继续，避免在客户端旧数据上盲发连招。
     *
     * <p>原实现的致命缺陷：一次连招「拿起源槽 → 点袋子 → 余量点回源槽」全部基于客户端
     * 槽位内容预判光标状态。快速拖拽/重复经过同一槽位时，客户端仍显示物品，但服务端其实
     * 已经被前一次连招清空，于是「拿起」落空、光标为空，后续「点袋子」变成把袋子本身拿到
     * 光标，最后「余量点回源槽」就把袋子塞进了箱子（或其它容器）。
     */
    public static boolean handleSpaceClick(Slot hoveredSlot) {
        if (hoveredSlot == null || !hoveredSlot.hasItem()) return false;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getWindow() == null) return false;

        long window = client.getWindow().handle();
        if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) != GLFW.GLFW_PRESS) return false;

        Player player = client.player;
        if (betterbundle.util.CreativeGuard.isCreative(player, client.gui.screen())) return false;
        // 入队与光标状态解耦：快速滑动时本机正在用光标搬运上一格，光标常常非空，
        // 若在这里拒绝入队，滑过去的槽位会被整体丢弃，表现为「稍快就不放入」。
        // 真正执行由状态机串行进行，且只在光标为空时才开始，安全性不受影响。

        ItemStack stack = hoveredSlot.getItem();
        if (stack.isEmpty()) return false;
        // 空格手势下，袋子本身不可再塞入袋子：静默吞掉，绝不退化成原版「拿起袋子」。
        if (BundleContentsHelper.isNonEmptyBundle(stack)) return true;

        int slotIndex = hoveredSlot.index;
        if (queuedSlots.contains(slotIndex)) return true;
        // 没有任何袋子/没有空间能容纳：静默吞掉空格点击，不走原版「拿起物品」。
        if (buildInsertTargets(BundlePanelRenderer.getAllBundles(), stack, slotIndex).isEmpty()) return true;

        if (pendingSlots.size() >= MAX_PENDING_SLOTS) return true;
        queuedSlots.add(slotIndex);
        pendingSlots.addLast(slotIndex);
        return true;
    }

    /**
     * 计算把 stack 放入哪些袋子（类似 XFS 聚堆预分配）：
     * <ol>
     *   <li>优先已有同种物品的袋子（同种越多越靠前）；</li>
     *   <li>没有同种时，选当前能容纳最多的袋子（“找最大的一个”），尽量把整叠聚到一处；</li>
     *   <li>依次累计容量，凑够整叠就停，装不下的部分才继续找下一个袋子。</li>
     * </ol>
     * 返回目标袋子槽位（按放入顺序）。
     */
    private static List<Integer> buildInsertTargets(
            List<BundlePanelRenderer.BundleSlotEntry> bundles, ItemStack stack, int excludeSlot) {
        List<BundlePanelRenderer.BundleSlotEntry> cands = new ArrayList<>();
        for (BundlePanelRenderer.BundleSlotEntry entry : bundles) {
            if (entry.bundleSlot() == excludeSlot) continue;
            if (BundleContentsHelper.maxAcceptable(entry.bundleStack(), stack) > 0) {
                cands.add(entry);
            }
        }
        // 与整理的目标一致：优先“已有该物品”的袋子（按当前列表顺序），否则放最空的袋子。
        cands.sort(Comparator
                .comparingInt((BundlePanelRenderer.BundleSlotEntry e) ->
                        BundleContentsHelper.sameItemCount(e.bundleStack(), stack) > 0 ? 0 : 1)
                .thenComparingInt(e -> -BundleContentsHelper.maxAcceptable(e.bundleStack(), stack)));

        List<Integer> targets = new ArrayList<>();
        int remaining = stack.getCount();
        for (BundlePanelRenderer.BundleSlotEntry entry : cands) {
            int cap = BundleContentsHelper.maxAcceptable(entry.bundleStack(), stack);
            if (cap <= 0) continue;
            targets.add(entry.bundleSlot());
            remaining -= Math.min(cap, remaining);
            if (remaining <= 0) break;
        }
        return targets;
    }

    private static ServerboundContainerClickPacket makeClickPacket(int containerId, int slot, byte button) {
        return new ServerboundContainerClickPacket(
                containerId, -1, (short) slot, button,
                ContainerInput.PICKUP, new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
    }

    private static int findEmptyPlayerSlot(Player player) {
        // search main inventory (getSlotIndex 9-35) then hotbar (getSlotIndex 0-8)
        for (int pass = 0; pass < 2; pass++) {
            int min = (pass == 0) ? 9 : 0;
            int max = (pass == 0) ? 36 : 9;
            for (Slot slot : player.containerMenu.slots) {
                if (slot.container == player.getInventory() && !slot.hasItem()) {
                    int idx = slot.getContainerSlot();
                    if (idx >= min && idx < max) return slot.index;
                }
            }
        }
        return -1;
    }

    private static long bulkInsertStart = 0;
    private static final long BULK_INSERT_DELAY = 50; // 0.05s

    /** Start the bulk-insert timer (called on space+left-click inside panel with empty cursor). */
    public static void startBulkInsert() {
        bulkInsertStart = System.currentTimeMillis();
    }

    /** Whether the bulk-insert state is active (left button held > 0.05s). */
    public static boolean isBulkInsertActive() {
        return bulkInsertStart > 0 && (System.currentTimeMillis() - bulkInsertStart) >= BULK_INSERT_DELAY;
    }

    /** Exit bulk-insert state. 不影响已入队槽位的收尾处理。 */
    public static void stopBulkInsert() {
        bulkInsertStart = 0;
    }

    // ==== 空格批量塞入：确认式状态机 ====

    private static final int MAX_PENDING_SLOTS = 256;
    private static final int BULK_CONFIRM_TIMEOUT_TICKS = 40; // 2s

    private enum BulkPhase { IDLE, WAIT_PICKUP, WAIT_INSERT }

    private static final Deque<Integer> pendingSlots = new ArrayDeque<>();
    private static final Set<Integer> queuedSlots = new HashSet<>();

    private static BulkPhase bulkPhase = BulkPhase.IDLE;
    private static int bulkWaitTicks;
    private static int bulkSrcSlot = -1;
    private static String bulkSrcBefore = "";
    private static boolean bulkRecoverySent;
    private static AbstractContainerMenu bulkMenu;

    /**
     * 每客户端 tick 推进批量塞入。全过程串行：
     * <ol>
     *   <li>WAIT_PICKUP：点源槽拿起整叠，等光标变为非空（或源槽已空）；</li>
     *   <li>WAIT_INSERT：从已确认的光标构造目标袋，点入后把余量点回源槽，等光标清空即可开始下一格。</li>
     * </ol>
     * 关键不变量：目标袋点击一定发生在「光标已确认非空」之后，且下一格的拿起只在光标为空时开始，
     * 因此绝不会在空光标上点袋子槽，也就不会把袋子拿起并（在余量回填时）塞进容器。
     */
    public static void onClientTick() {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null || client.getConnection() == null) {
            resetBulk();
            return;
        }
        if (player.containerMenu != bulkMenu) {
            bulkMenu = player.containerMenu;
            resetBulk();
            return;
        }
        if (bulkPhase != BulkPhase.IDLE) {
            advanceBulkPhase(client, player);
            return;
        }
        // 整理进行中：暂停，避免两套自动操作互相踩踏。
        if (betterbundle.sort.exec.SortStateMachine.get().isRunning()) return;
        // 光标被占用时先暂停（不丢弃队列）：可能是外部操作或尚未同步，等光标空了继续。
        if (!player.containerMenu.getCarried().isEmpty()) return;

        while (!pendingSlots.isEmpty()) {
            int slot = pendingSlots.pollFirst();
            if (!tryStartSlot(client, player, slot)) {
                queuedSlots.remove(slot);
                continue;
            }
            break;
        }
    }

    private static boolean tryStartSlot(Minecraft client, Player player, int slotIndex) {
        if (slotIndex < 0 || slotIndex >= player.containerMenu.slots.size()) return false;
        Slot slot = player.containerMenu.getSlot(slotIndex);
        if (slot == null || !slot.hasItem()) return false;
        ItemStack stack = slot.getItem();
        if (stack.isEmpty() || BundleContentsHelper.isNonEmptyBundle(stack)) return false;
        if (buildInsertTargets(BundlePanelRenderer.getAllBundles(), stack, slotIndex).isEmpty()) return false;

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        bulkSrcSlot = slotIndex;
        bulkSrcBefore = stackSignature(stack);
        bulkRecoverySent = false;
        connection.send(makeClickPacket(player.containerMenu.containerId, slotIndex, (byte) 0));
        bulkPhase = BulkPhase.WAIT_PICKUP;
        bulkWaitTicks = 0;
        return true;
    }

    private static void advanceBulkPhase(Minecraft client, Player player) {
        bulkWaitTicks++;

        if (bulkPhase == BulkPhase.WAIT_PICKUP) {
            ItemStack carried = player.containerMenu.getCarried();
            if (!carried.isEmpty()) {
                sendInsertPhase(client, player, carried);
                return;
            }
            ItemStack srcNow = stackAt(player, bulkSrcSlot);
            // 拿起点击已被服务端处理（源槽已空/内容变化）但光标仍空：说明原本就没东西，跳过。
            if (srcNow.isEmpty() || !stackSignature(srcNow).equals(bulkSrcBefore)) {
                finishBulkSlot();
                return;
            }
            // 超时：只跳过当前格、保留整条队列（不要 resetBulk，否则会连带丢掉后面所有待处理格）。
            if (bulkWaitTicks > BULK_CONFIRM_TIMEOUT_TICKS) finishBulkSlot();
            return;
        }

        // WAIT_INSERT：只需等光标清空——这是继续下一格的关键不变量。
        // 袋子内容与光标更新由服务端同批下发，无需逐袋签名校验，避免拖慢整条流水线。
        if (player.containerMenu.getCarried().isEmpty()) {
            finishBulkSlot();
            return;
        }
        if (bulkWaitTicks > BULK_CONFIRM_TIMEOUT_TICKS) {
            // 光标长时间未清空：兜底把光标物品点回源槽（避免滞留），只跳过当前格，保留队列。
            ClientPacketListener connection = client.getConnection();
            if (!bulkRecoverySent && connection != null) {
                connection.send(makeClickPacket(player.containerMenu.containerId, bulkSrcSlot, (byte) 0));
                bulkRecoverySent = true;
                bulkWaitTicks = 0;
                return;
            }
            finishBulkSlot();
        }
    }

    private static void sendInsertPhase(Minecraft client, Player player, ItemStack carried) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            resetBulk();
            return;
        }
        int containerId = player.containerMenu.containerId;
        List<Integer> targets = buildInsertTargets(
                BundlePanelRenderer.getAllBundles(), carried, bulkSrcSlot);

        int remaining = carried.getCount();
        for (int target : targets) {
            if (remaining <= 0) break;
            int cap = BundleContentsHelper.maxAcceptable(stackAt(player, target), carried);
            if (cap <= 0) continue;
            remaining -= Math.min(cap, remaining);
            connection.send(makeClickPacket(containerId, target, (byte) 0));
        }
        // 余量点回源槽：全部放入时光标为空且源槽为空，是无害 no-op；
        // 有空余量时把余量放回。绝不在此前空光标点过袋子槽。
        connection.send(makeClickPacket(containerId, bulkSrcSlot, (byte) 0));

        bulkPhase = BulkPhase.WAIT_INSERT;
        bulkWaitTicks = 0;
    }

    private static void finishBulkSlot() {
        if (bulkSrcSlot >= 0) queuedSlots.remove(bulkSrcSlot);
        bulkSrcSlot = -1;
        bulkSrcBefore = "";
        bulkRecoverySent = false;
        bulkPhase = BulkPhase.IDLE;
        bulkWaitTicks = 0;
    }

    private static void resetBulk() {
        pendingSlots.clear();
        queuedSlots.clear();
        bulkSrcSlot = -1;
        bulkSrcBefore = "";
        bulkRecoverySent = false;
        bulkPhase = BulkPhase.IDLE;
        bulkWaitTicks = 0;
    }

    /** 非袋子物品的规范化签名，用于判断源槽是否已被服务端更新。 */
    private static String stackSignature(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                + "#" + ItemStack.hashItemAndComponents(stack) + "#" + stack.getCount();
    }

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

    /** Put cursor item into available bundles, distributing across several if needed.
     *  单袋放不下时自动拆到多个袋子（优先聚堆）。 */
    public static boolean handlePanelInsert(int button) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return false;
        // 创造模式：光标可能来自无限 picker，禁用面板分发塞入，避免复制。
        if (betterbundle.util.CreativeGuard.isCreative(player, client.gui.screen())) return false;

        ItemStack cursor = player.containerMenu.getCarried();
        if (cursor.isEmpty()) return false;

        List<BundlePanelRenderer.BundleSlotEntry> bundles = BundlePanelRenderer.getAllBundles();
        List<Integer> targets = buildInsertTargets(bundles, cursor, -1);
        if (targets.isEmpty()) return false;

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;
        int containerId = player.containerMenu.containerId;

        // 光标已有整叠，直接依次放入多个袋子；每个尽量填充，余量留在光标上。
        // 按预测光标数量推进：一旦预测放空就停手，绝不在空光标上点袋子槽（否则会把袋子拿起）。
        int predicted = cursor.getCount();
        for (int target : targets) {
            if (predicted <= 0) break;
            int cap = BundleContentsHelper.maxAcceptable(stackAt(player, target), cursor);
            if (cap <= 0) continue;
            predicted -= Math.min(cap, predicted);
            connection.send(makeClickPacket(containerId, target, (byte) 0));
        }
        return true;
    }

    public static boolean handleScroll(double mouseX, double mouseY, double scrollDelta,
                                        int leftPos, int topPos, int imageHeight) {
        if (!BundlePanelRenderer.isEffectivelyVisible()) return false;
        if (!isInsidePanel(mouseX, mouseY, leftPos, topPos, imageHeight)) return false;
        BundlePanelRenderer.scrollBy(scrollDelta > 0 ? -1 : 1);
        return true;
    }

    public static boolean isInsidePanel(double mouseX, double mouseY,
                                         int leftPos, int topPos, int imageHeight) {
        int pw = BundlePanelRenderer.panelWidth();
        int panelX = leftPos - pw - 4;
        int gx = gridX(leftPos);
        if (mouseX < gx || mouseX > panelX + pw - BundlePanelRenderer.PADDING) return false;
        int pTop = gridY(topPos);
        int pH = BundlePanelRenderer.VISIBLE_ROWS * BundlePanelRenderer.SLOT_SIZE
                + (BundlePanelRenderer.VISIBLE_ROWS - 1) * BundlePanelRenderer.SLOT_SPACING;
        if (mouseY < pTop || mouseY > pTop + pH) return false;
        return true;
    }
}
