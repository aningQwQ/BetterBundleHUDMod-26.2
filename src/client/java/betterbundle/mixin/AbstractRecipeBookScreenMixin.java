package betterbundle.mixin;

import betterbundle.gui.BundleCategory;
import betterbundle.gui.BundlePanelInteraction;
import betterbundle.gui.BundlePanelRenderer;
import betterbundle.gui.SortButton;
import betterbundle.sort.exec.SortStateMachine;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractRecipeBookScreen.class)
public abstract class AbstractRecipeBookScreenMixin {

    /** 同 AbstractContainerScreenMixin：让 JEI 的「容器内聚焦文本框」判定命中我们的搜索框。 */
    @Unique
    private EditBox betterbundle$searchField;

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(double mouseX, double mouseY, int button, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        

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
                BundlePanelRenderer.resetSearch();
                cir.setReturnValue(true);
                return;
            }
        }

        // Search bar click
        if (BundlePanelRenderer.isInsideSearchBar(mouseX, mouseY, self.leftPos, self.topPos, self.imageHeight)) {
            BundlePanelRenderer.handleSearchClick(mouseX, mouseY, button, self.leftPos, self.topPos, self.imageHeight);
            this.betterbundle$searchField = BundlePanelRenderer.getSearchBox();
            cir.setReturnValue(true);
            return;
        }

        BundlePanelRenderer.unfocusSearch();
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void onCharTyped(char codePoint, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (BundlePanelRenderer.isSearchFocused()) {
            BundlePanelRenderer.handleSearchChar(codePoint, modifiers);
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(int keyCode, int scanCode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        SortStateMachine.get().abortByUser();
        if (BundlePanelRenderer.isSearchFocused()) {
            BundlePanelRenderer.handleSearchKey(keyCode, scanCode, modifiers);
            cir.setReturnValue(true);
        }
    }
}
