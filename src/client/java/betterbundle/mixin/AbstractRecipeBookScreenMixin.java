package betterbundle.mixin;

import betterbundle.gui.BundleCategory;
import betterbundle.gui.BundlePanelInteraction;
import betterbundle.gui.BundlePanelRenderer;
import betterbundle.gui.SortButton;
import betterbundle.sort.exec.SortStateMachine;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractRecipeBookScreen.class)
public abstract class AbstractRecipeBookScreenMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (betterbundle.util.CreativeGuard.isCreative(
                net.minecraft.client.Minecraft.getInstance().player,
                net.minecraft.client.Minecraft.getInstance().gui.screen())) return;
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        double mouseX = event.x();
        double mouseY = event.y();

        // 一键整理按钮（面板可见时优先；运行中点击即取消）
        if (BundlePanelRenderer.isEffectivelyVisible()
                && SortButton.handleClick(self.leftPos, self.topPos, self.imageHeight, mouseX, mouseY)) {
            cir.setReturnValue(true);
            return;
        }
        SortStateMachine.get().abortByUser();

        // Toggle button
        int bx = self.leftPos + self.imageWidth;
        int by = self.topPos + 5;
        if (mouseX >= bx && mouseX < bx + 20 && mouseY >= by && mouseY < by + 20) {
            BundlePanelRenderer.toggleVisible();
            cir.setReturnValue(true);
            return;
        }

        // Category button
        if (BundlePanelRenderer.isEffectivelyVisible()) {
            BundleCategory cat = BundlePanelRenderer.getCategoryAt(mouseX, mouseY, self.leftPos, self.topPos, self.imageHeight);
            if (cat != null) {
                BundlePanelRenderer.currentCategory = cat;
                BundlePanelRenderer.searchQuery = "";
                BundlePanelRenderer.scrollToTop();
                cir.setReturnValue(true);
                return;
            }
        }

        // Search bar click
        if (BundlePanelRenderer.isEffectivelyVisible()
                && BundlePanelRenderer.isInsideSearchBar(mouseX, mouseY, self.leftPos, self.topPos, self.imageHeight)) {
            BundlePanelRenderer.searchFocused = true;
            cir.setReturnValue(true);
            return;
        }

        BundlePanelRenderer.searchFocused = false;
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void onCharTyped(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (betterbundle.util.CreativeGuard.isCreative(
                net.minecraft.client.Minecraft.getInstance().player,
                net.minecraft.client.Minecraft.getInstance().gui.screen())) return;
        if (BundlePanelRenderer.searchFocused) {
            BundlePanelRenderer.onCharTyped((char) event.codepoint());
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (betterbundle.util.CreativeGuard.isCreative(
                net.minecraft.client.Minecraft.getInstance().player,
                net.minecraft.client.Minecraft.getInstance().gui.screen())) return;
        SortStateMachine.get().abortByUser();
        if (BundlePanelRenderer.searchFocused) {
            BundlePanelRenderer.onSearchKeyPress(event.key());
            cir.setReturnValue(true);
        }
    }
}
