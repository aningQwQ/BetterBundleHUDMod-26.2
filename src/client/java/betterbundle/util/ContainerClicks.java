package betterbundle.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;

/** 统一走原版容器点击，由客户端预测维护 stateId / changedSlots。 */
public final class ContainerClicks {
    private ContainerClicks() {}

    public static void pickup(int containerId, int slot, int button) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null || client.gameMode == null) return;
        client.gameMode.handleContainerInput(containerId, slot, button, ContainerInput.PICKUP, player);
    }
}
