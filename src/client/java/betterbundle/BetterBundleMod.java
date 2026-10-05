package betterbundle;

import betterbundle.config.BetterBundleConfig;
import betterbundle.config.ModConfig;
import betterbundle.gui.BundleCategory;
import betterbundle.gui.BundlePanelInteraction;
import betterbundle.gui.BundlePanelRenderer;
import betterbundle.gui.SortButton;
import betterbundle.sort.exec.SortStateMachine;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class BetterBundleMod implements ClientModInitializer {
    public static final String MOD_ID = "better-bundle";

    @Override
    public void onInitializeClient() {
        AutoConfig.register(BetterBundleConfig.class, GsonConfigSerializer::new);
        ModConfig.init();
        applyPanelVisibility(ModConfig.get());
        BundleCategory.registerCategoryItems();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            SortStateMachine.get().onClientTick();
            BundlePanelInteraction.onClientTick();
            SortButton.tick();
        });
    }

    private static void applyPanelVisibility(BetterBundleConfig cfg) {
        switch (cfg.panelVisibility) {
            case ALWAYS_ON -> BundlePanelRenderer.visible = true;
            case ALWAYS_OFF -> BundlePanelRenderer.visible = false;
            case REMEMBER_LAST -> BundlePanelRenderer.visible = cfg.lastVisible;
        }
    }
}
