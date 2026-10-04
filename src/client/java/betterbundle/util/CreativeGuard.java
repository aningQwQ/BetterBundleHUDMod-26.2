package betterbundle.util;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.entity.player.Player;

/** 创造模式防护：创造背包使用 ItemPickerMenu（动态槽位/破坏槽/无限 picker），我们的自动整理与分发塞入不安全。 */
public final class CreativeGuard {

    private CreativeGuard() {}

    public static boolean isCreative(Player player, Screen screen) {
        // 风险来自创造背包界面的 ItemPickerMenu（动态槽位/破坏槽/无限 picker）。
        // 创造玩家打开箱子等普通容器用的是普通菜单，不影响。
        return screen instanceof CreativeModeInventoryScreen;
    }
}
