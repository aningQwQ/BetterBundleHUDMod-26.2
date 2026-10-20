package betterbundle.mixin;

import betterbundle.gui.BundlePanelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 箱子等直接继承 AbstractContainerScreen 的界面没有覆写 charTyped，
 * 字符事件走 GuiEventListener 的默认实现，因此在这里接管搜索框输入。
 * 只在容器界面且搜索框聚焦时处理，避免吞掉聊天等界面的输入。
 */
@Mixin(GuiEventListener.class)
public interface GuiEventListenerMixin {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void betterbundle$onCharTyped(CharacterEvent event, CallbackInfoReturnable<Boolean> cir) {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof AbstractContainerScreen<?> && BundlePanelRenderer.isSearchFocused()) {
            BundlePanelRenderer.handleSearchChar(event);
            cir.setReturnValue(true);
        }
    }
}
