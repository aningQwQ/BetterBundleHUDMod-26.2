package betterbundle;

import betterbundle.gui.BundleCategory;
import betterbundle.gui.SortButton;
import betterbundle.sort.exec.SortStateMachine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class BetterBundleMod implements ClientModInitializer {
    public static final String MOD_ID = "better-bundle";

    @Override
    public void onInitializeClient() {
        BundleCategory.registerCategoryItems();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SortStateMachine.get().onClientTick();
            SortButton.tick();
        });
    }
}
