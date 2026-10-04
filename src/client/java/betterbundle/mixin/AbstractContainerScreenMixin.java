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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (betterbundle.util.CreativeGuard.isCreative(mc.player, mc.gui.screen())) return;
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

        // Bulk-insert: space+left anywhere starts the timer (0.05s to activate)
        if (event.button() == 0 && isSpaceDown()) {
            BundlePanelInteraction.startBulkInsert();
        }

        // Space+Click works on ALL container screens
        Slot hovered = self.hoveredSlot;
        if (hovered != null && hovered.hasItem()) {
            boolean handled = BundlePanelInteraction.handleSpaceClick(hovered);
            if (handled) { cir.setReturnValue(true); return; }
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
        Minecraft mc = Minecraft.getInstance();
        if (betterbundle.util.CreativeGuard.isCreative(mc.player, mc.gui.screen())) return;
        BundlePanelInteraction.stopBulkInsert();
        lastBulkSlot = -1;
        if (!BundlePanelRenderer.isEffectivelyVisible()) return;
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (BundlePanelInteraction.isInsidePanel(event.x(), event.y(),
                self.leftPos, self.topPos, self.imageHeight)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (betterbundle.util.CreativeGuard.isCreative(mc.player, mc.gui.screen())) return;
        SortStateMachine.get().abortByUser();
        if (BundlePanelRenderer.searchFocused) {
            BundlePanelRenderer.onSearchKeyPress(event.key());
            cir.setReturnValue(true);
        }
    }

    private int lastBulkSlot = -1;

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void onMouseDragged(MouseButtonEvent event, double dx, double dy,
                                 CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (betterbundle.util.CreativeGuard.isCreative(mc.player, mc.gui.screen())) return;
        SortStateMachine.get().abortByUser();
        if (!BundlePanelInteraction.isBulkInsertActive()) return;
        if (!isSpaceDown()) { BundlePanelInteraction.stopBulkInsert(); return; }

        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        Slot hovered = self.hoveredSlot;
        if (hovered != null && hovered.hasItem() && hovered.index != lastBulkSlot) {
            lastBulkSlot = hovered.index;
            BundlePanelInteraction.handleSpaceClick(hovered);
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void onMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
                                  CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (betterbundle.util.CreativeGuard.isCreative(mc.player, mc.gui.screen())) return;
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
