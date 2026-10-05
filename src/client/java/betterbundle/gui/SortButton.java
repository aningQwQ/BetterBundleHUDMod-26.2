package betterbundle.gui;

import betterbundle.sort.exec.SortStateMachine;
import betterbundle.sort.model.BundleSnapshotBuilder;
import betterbundle.sort.model.InventoryModel;
import betterbundle.sort.plan.BundlePacker;
import betterbundle.sort.plan.PlanResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/** 「一键整理」按钮 + 进度展示（无遮罩、不锁 UI）。位置由 {@link PanelLayout} 统一给出。 */
public final class SortButton {

    private SortButton() {}

    private static PanelLayout layout(int leftPos, int topPos, int imageHeight) {
        return BundlePanelRenderer.currentLayout(leftPos, topPos, imageHeight);
    }

    public static boolean isInside(double mouseX, double mouseY, int leftPos, int topPos, int imageHeight) {
        PanelLayout lay = layout(leftPos, topPos, imageHeight);
        return mouseX >= lay.sortX && mouseX < lay.sortX + lay.sortW
                && mouseY >= lay.sortY && mouseY < lay.sortY + lay.sortH;
    }

    /** 处理按钮点击。返回 true 表示事件已被消化。 */
    public static boolean handleClick(int leftPos, int topPos, int imageHeight,
                                      double mouseX, double mouseY) {
        if (!isInside(mouseX, mouseY, leftPos, topPos, imageHeight)) return false;

        SortStateMachine fsm = SortStateMachine.get();
        if (fsm.isRunning()) {
            fsm.abortByUser();
            return true;
        }
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return true;

        if (betterbundle.util.CreativeGuard.isCreative(player, client.gui.screen())) {
            setError("创造模式暂不支持整理");
            return true;
        }

        InventoryModel model = BundleSnapshotBuilder.build(player);
        PlanResult result = new BundlePacker().plan(model);
        if (!result.isOk()) {
            setError(result.error());
            return true;
        }
        // start 可能拒绝（如光标持物/状态不符）；把原因显示出来，避免“点了没反应”。
        String reason = fsm.start(result.plan());
        if (reason != null && !reason.isEmpty()) {
            setError(reason);
        }
        return true;
    }

    private static String errorMessage = "";
    private static int errorTicks;

    public static void setError(String message) {
        errorMessage = message == null ? "" : message;
        errorTicks = 60;
    }

    public static void tick() {
        if (errorTicks > 0) errorTicks--;
    }

    public static void render(GuiGraphicsExtractor graphics, Font font,
                              int leftPos, int topPos, int imageHeight,
                              int mouseX, int mouseY) {
        PanelLayout lay = layout(leftPos, topPos, imageHeight);
        int x = lay.sortX;
        int y = lay.sortY;
        int w = lay.sortW;
        int h = lay.sortH;
        SortStateMachine fsm = SortStateMachine.get();

        String label;
        if (fsm.isRunning()) {
            int done = fsm.progressDone();
            int total = Math.max(1, fsm.progressTotal());
            int filled = (w - 2) * Math.min(done, total) / total;
            graphics.fill(x, y, x + w, y + h, 0x80000000);
            graphics.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, 0xFF3C6E3C);
            label = done + "/" + total;
        } else {
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
            graphics.fill(x, y, x + w, y + h, hovered ? 0x60FFFFFF : 0x40FFFFFF);
            label = "一键整理";
        }

        String status = fsm.message();
        if (!status.isEmpty()) label = status;
        else if (errorTicks > 0 && !errorMessage.isEmpty()) label = errorMessage;

        int textW = font.width(label);
        int textColor = errorTicks > 0 ? 0xFFFF8080 : 0xFFFFFFFF;
        graphics.text(font, label, x + Math.max(2, (w - textW) / 2),
                y + (h - font.lineHeight) / 2 + 1, textColor, false);
    }
}
