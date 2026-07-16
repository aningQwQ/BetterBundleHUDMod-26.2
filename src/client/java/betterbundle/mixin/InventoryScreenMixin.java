package betterbundle.mixin;

import betterbundle.gui.BundlePanelRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class InventoryScreenMixin {

    @Inject(method = "extractContents", at = @At("TAIL"))
    private void onExtractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        BundlePanelRenderer.render(graphics,
                self.leftPos,
                self.topPos,
                self.imageHeight,
                mouseX, mouseY);

        // Enlarge the bundle icon for the hovered panel item
        int bundleSlot = BundlePanelRenderer.getHoveredBundleSlot();
        if (bundleSlot >= 0) {
            Slot slot = self.getMenu().getSlot(bundleSlot);
            if (slot != null && slot.hasItem()) {
                int sx = self.leftPos + slot.x;
                int sy = self.topPos + slot.y;
                var pose = graphics.pose();
                pose.pushMatrix();
                pose.translate(sx + 8, sy + 8);
                float scale = 19f / 16f;
                pose.scale(scale, scale);
                pose.translate(-8, -8);
                graphics.item(slot.getItem(), 0, 0);
                pose.popMatrix();
            }
        }

        renderToggleButton(graphics, self.leftPos + self.imageWidth, self.topPos + 5, mouseX, mouseY);
    }

    private static void renderToggleButton(GuiGraphicsExtractor graphics, int x, int y, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + 20 && mouseY >= y && mouseY < y + 20;
        if (hovered) graphics.fill(x, y, x + 20, y + 20, 0x40FFFFFF);
        if (BundlePanelRenderer.visible) graphics.fill(x + 2, y + 17, x + 18, y + 18, 0xFF80B0FF);
        ItemStack icon = new ItemStack(Items.BUNDLE);
        graphics.item(icon, x + 1, y + 2);
    }
}
