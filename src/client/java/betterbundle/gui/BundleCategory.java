package betterbundle.gui;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 面板分类。物品归属由 {@link CategoryClassifier} 在运行时按规则推导，
 * 不再维护硬编码物品清单（B 方案）。
 */
public enum BundleCategory {
    ALL("全部", Items.COMPASS),
    BLOCKS("方块", Items.GRASS_BLOCK),
    NON_FULL_BLOCKS("不完整方块", Items.SPRUCE_DOOR),
    PLANTS("植物", Items.CHERRY_SAPLING),
    FOOD("食物", Items.BREAD),
    TOOLS_AND_EQUIPMENT("工具和装备", Items.DIAMOND_CHESTPLATE),
    MISC("杂物", Items.LEATHER),
    MINERALS("矿物", Items.DIAMOND);

    private final String displayName;
    private final Item iconItem;

    BundleCategory(String displayName, Item iconItem) {
        this.displayName = displayName;
        this.iconItem = iconItem;
    }

    public String getDisplayName() { return displayName; }

    public ItemStack getIcon() { return new ItemStack(iconItem); }

    public boolean matches(Item item) { return CategoryClassifier.matches(this, item); }

    /** 兼容旧入口：初始化分类器（清空缓存）。 */
    public static void registerCategoryItems() { CategoryClassifier.init(); }
}
