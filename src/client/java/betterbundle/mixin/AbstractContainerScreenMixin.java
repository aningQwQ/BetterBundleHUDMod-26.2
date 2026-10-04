package betterbundle.mixin;

import betterbundle.gui.BundleCategory;
import betterbundle.gui.BundlePanelInteraction;
import betterbundle.gui.BundlePanelRenderer;
import betterbundle.gui.SortButton;
import betterbundle.sort.exec.SortStateMachine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;

        // 一键整理按钮（面板可见时优先；运行中点击即取消）
        if (BundlePanelRenderer.isEffectivelyVisible()
                && SortButton.handleClick(self.leftPos, self.topPos, self.imageHeight,
                        event.x(), event.y())) {
            cir.setReturnValue(true);
            return;
        }

        // 玩家任意输入 → 立即中止整理（不回滚，D4）
        SortStateMachine.get().abortByUser();

        // Bulk-insert: space+left anywhere starts the sweep.
        if (event.button() == 0 && isSpaceDown() && BundlePanelRenderer.isEffectivelyVisible()) {
            BundlePanelInteraction.startBulkInsert();
            sweeping = true;
            sweepLastX = event.x();
            sweepLastY = event.y();
            sweptSlots.clear();
        }

        // Space+Click works on ALL container screens.
        // 用事件坐标定位槽位（与原生 getHoveredSlot 一致），不要用 hoveredSlot 字段：
        // 该字段只在 mouseMoved 里更新，单击可能先于本帧的移动事件到达而读到旧槽位。
        Slot hovered = findSlotAt(self, event.x(), event.y());
        if (hovered != null && hovered.hasItem()) {
            boolean handled = BundlePanelInteraction.handleSpaceClick(hovered);
            if (handled) {
                sweptSlots.add(hovered.index);
                cir.setReturnValue(true);
                return;
            }
        }

        double mx = event.x();
        double my = event.y();

        // For non-recipe-book screens: handle toggle, category, search bar
        if (!(((Object) this) instanceof AbstractRecipeBookScreen)) {
            int bx = self.leftPos + self.imageWidth;
            int by = self.topPos + 5;
            if (mx >= bx && mx < bx + 20 && my >= by && my < by + 20) {
                BundlePanelRenderer.toggleVisible();
                cir.setReturnValue(true);
                return;
            }

            if (BundlePanelRenderer.isEffectivelyVisible()) {
                BundleCategory cat = BundlePanelRenderer.getCategoryAt(mx, my, self.leftPos, self.topPos, self.imageHeight);
                if (cat != null) {
                    BundlePanelRenderer.currentCategory = cat;
                    BundlePanelRenderer.searchQuery = "";
                    BundlePanelRenderer.scrollToTop();
                    cir.setReturnValue(true);
                    return;
                }
            }

            if (BundlePanelRenderer.isEffectivelyVisible()
                    && BundlePanelRenderer.isInsideSearchBar(mx, my, self.leftPos, self.topPos, self.imageHeight)) {
                BundlePanelRenderer.searchFocused = true;
                cir.setReturnValue(true);
                return;
            }

            BundlePanelRenderer.searchFocused = false;
        }

        if (!BundlePanelRenderer.isEffectivelyVisible()) return;

        // Cursor has items + click anywhere in panel (except category buttons) → insert
        ItemStack cursor = self.getMenu().getCarried();
        if (!cursor.isEmpty() && isInsidePanelBounds(mx, my, self.leftPos, self.topPos, self.imageHeight)) {
            BundleCategory cat = BundlePanelRenderer.getCategoryAt(mx, my, self.leftPos, self.topPos, self.imageHeight);
            if (cat == null) {
                boolean handled = BundlePanelInteraction.handlePanelInsert(event.button());
                if (handled) cir.setReturnValue(true);
            }
        }

        if (BundlePanelInteraction.isInsidePanel(mx, my, self.leftPos, self.topPos, self.imageHeight)) {
            if (cursor.isEmpty()) {
                boolean handled = BundlePanelInteraction.handlePanelClick(
                        mx, my, event.button(), event.modifiers(), self.leftPos, self.topPos, self);
                if (handled) cir.setReturnValue(true);
            }
        }
    }

    private static boolean isInsidePanelBounds(double mx, double my, int leftPos, int topPos, int imageHeight) {
        int pw = BundlePanelRenderer.panelWidth();
        int panelX = leftPos - pw - 4;
        int panelY = topPos;
        int searchH = BundlePanelRenderer.SEARCH_BAR_HEIGHT + 3;
        int gridH = BundlePanelRenderer.PADDING * 2
                + BundlePanelRenderer.VISIBLE_ROWS * BundlePanelRenderer.SLOT_SIZE
                + (BundlePanelRenderer.VISIBLE_ROWS - 1) * BundlePanelRenderer.SLOT_SPACING;
        int panelH = Math.min(imageHeight, searchH + gridH) + 24;
        return mx >= panelX && mx <= panelX + pw && my >= panelY && my <= panelY + panelH;
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void onMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        BundlePanelInteraction.stopBulkInsert();
        sweeping = false;
        sweptSlots.clear();
        if (!BundlePanelRenderer.isEffectivelyVisible()) return;
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (BundlePanelInteraction.isInsidePanel(event.x(), event.y(),
                self.leftPos, self.topPos, self.imageHeight)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        SortStateMachine.get().abortByUser();
        if (BundlePanelRenderer.searchFocused) {
            BundlePanelRenderer.onSearchKeyPress(event.key());
            cir.setReturnValue(true);
        }
    }

    /** 快速滑动时鼠标事件会在两格之间跳跃，只取当前悬停格会漏掉中间槽位。 */
    private boolean sweeping;
    private double sweepLastX;
    private double sweepLastY;
    private final Set<Integer> sweptSlots = new HashSet<>();

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void onMouseDragged(MouseButtonEvent event, double dx, double dy,
                                 CallbackInfoReturnable<Boolean> cir) {
        SortStateMachine.get().abortByUser();
        if (!sweeping) return;
        if (!isSpaceDown()) {
            BundlePanelInteraction.stopBulkInsert();
            sweeping = false;
            sweptSlots.clear();
            return;
        }

        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        double mx = event.x();
        double my = event.y();
        sweepEnqueue(self, sweepLastX, sweepLastY, mx, my);
        sweepLastX = mx;
        sweepLastY = my;
        cir.setReturnValue(true);
    }

    /** 一次线段扫过的槽位及其进入参数，用于按滑动路径排序。 */
    private record SweepHit(double t, Slot slot) {}

    /**
     * 把「上一次位置 → 当前位置」这条线段与每个槽位的矩形做相交判断，穿过就补入，
     * 并按进入线段的时间参数 t 排序，保证按滑动经过的顺序入队（而不是按槽位索引乱序）。
     */
    private void sweepEnqueue(AbstractContainerScreen<?> self, double x0, double y0, double x1, double y1) {
        List<SweepHit> hits = new ArrayList<>();
        for (Slot slot : self.getMenu().slots) {
            if (!slot.isActive() || !slot.hasItem()) continue;
            if (sweptSlots.contains(slot.index)) continue;
            double t = segmentEntryParam(self, slot, x0, y0, x1, y1);
            if (t >= 0.0) hits.add(new SweepHit(t, slot));
        }
        hits.sort(Comparator.comparingDouble(SweepHit::t));
        for (SweepHit hit : hits) {
            if (!sweptSlots.add(hit.slot().index)) continue;
            BundlePanelInteraction.handleSpaceClick(hit.slot());
        }
    }

    /** Liang-Barsky：返回线段进入槽位矩形（含 ±1px 容差）的时间参数 t∈[0,1]，不相交返回 -1。 */
    private static double segmentEntryParam(AbstractContainerScreen<?> self, Slot slot,
                                            double x0, double y0, double x1, double y1) {
        double minX = self.leftPos + slot.x - 1;
        double maxX = self.leftPos + slot.x + 16 + 1;
        double minY = self.topPos + slot.y - 1;
        double maxY = self.topPos + slot.y + 16 + 1;
        double dx = x1 - x0;
        double dy = y1 - y0;
        double t0 = 0.0;
        double t1 = 1.0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0 - minX, maxX - x0, y0 - minY, maxY - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0.0) {
                if (q[i] < 0.0) return -1.0;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0.0) {
                    if (r > t1) return -1.0;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return -1.0;
                    if (r < t1) t1 = r;
                }
            }
        }
        return t0 <= t1 ? t0 : -1.0;
    }

    /** 与原生 AbstractContainerScreen.getHoveredSlot 等价：按事件坐标找活动槽位（含 ±1px 容差）。 */
    private static Slot findSlotAt(AbstractContainerScreen<?> self, double mouseX, double mouseY) {
        double relX = mouseX - self.leftPos;
        double relY = mouseY - self.topPos;
        for (Slot slot : self.getMenu().slots) {
            if (!slot.isActive()) continue;
            if (relX >= slot.x - 1 && relX < slot.x + 16 + 1
                    && relY >= slot.y - 1 && relY < slot.y + 16 + 1) {
                return slot;
            }
        }
        return null;
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void onMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
                                  CallbackInfoReturnable<Boolean> cir) {
        SortStateMachine.get().abortByUser();
        if (!BundlePanelRenderer.isEffectivelyVisible()) return;
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (BundlePanelInteraction.isInsidePanel(mouseX, mouseY,
                self.leftPos, self.topPos, self.imageHeight)) {
            boolean handled = BundlePanelInteraction.handleScroll(mouseX, mouseY, scrollY,
                    self.leftPos, self.topPos, self.imageHeight);
            if (handled) cir.setReturnValue(true);
        }
    }

    private static boolean isSpaceDown() {
        long window = Minecraft.getInstance().getWindow().handle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS;
    }
}
