package betterbundle.gui;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.WaterlilyBlock;
import net.minecraft.world.level.block.SeagrassBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 物品 → 分类的推导器（B 方案）。
 *
 * <p>不再维护 1000+ 条硬编码物品清单，而是写“规则”：运行时按
 * 物品类型 / 数据组件 / 原版 tag / 方块形状 推导所属分类，结果按物品缓存一次。
 * 这样 Minecraft 每次升级新增的物品会被规则自动覆盖，兼容分支几乎零维护。
 *
 * <p>判定顺序：食物 / 植物 / 矿物 / 工具装备 各自独立叠加；方块类（整方块、不完整方块）
 * 对非植物、非食物的方块叠加；最终什么都没有的落入「杂物」兜底。
 * 少量原版 tag/组件覆盖不到的物品由显式小表补齐。
 */
public final class CategoryClassifier {

    private static final Map<Item, Set<BundleCategory>> CACHE = new HashMap<>();

    private static final Set<BundleCategory> FALLBACK = Set.of(BundleCategory.MISC);

    /** 矿物没有完整 tag：锭 / 宝石 / 原矿 / 矿物块的显式补充。 */
    private static final Set<String> MINERAL_EXTRA = Set.of(
            "ancient_debris", "coal", "copper_ingot", "diamond", "diamond_block",
            "emerald", "emerald_block", "gold_block", "gold_ingot", "iron_ingot",
            "lapis_block", "lapis_lazuli", "netherite_block", "netherite_ingot",
            "netherite_scrap", "raw_copper", "raw_gold", "raw_iron", "redstone_block",
            "sulfur", "cinnabar"
    );

    /** 用具类：原版没有统一 tag 且无耐久/组件的物品的显式补充。 */
    private static final Set<String> TOOL_EXTRA = Set.of(
            "bow", "crossbow", "trident", "shield", "fishing_rod", "flint_and_steel",
            "shears", "brush", "spyglass", "clock", "compass", "recovery_compass",
            "map", "name_tag", "lead", "saddle", "totem_of_undying", "writable_book",
            "carrot_on_a_stick", "warped_fungus_on_a_stick", "turtle_helmet",
            "wolf_armor", "mace", "milk_bucket", "powder_snow_bucket"
    );

    /** 植物：原版 tag / 方块类覆盖不到、但语义上属于植物的物品。 */
    private static final Set<String> PLANT_EXTRA = Set.of(
            "bamboo", "big_dripleaf", "small_dripleaf", "chorus_plant", "chorus_flower",
            "cocoa_beans", "glow_berries", "glow_lichen", "hanging_roots", "mangrove_roots",
            "moss_block", "moss_carpet", "muddy_mangrove_roots", "pale_hanging_moss",
            "pale_moss_block", "pale_moss_carpet", "sweet_berries", "twisting_vines",
            "weeping_vines", "wheat", "kelp", "seagrass", "lily_pad",
            "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "torchflower_seeds", "pitcher_pod"
    );

    /** 形状不满但按惯例算作“方块”的物品。 */
    private static final Set<String> BLOCKS_EXTRA = Set.of(
            "dirt_path", "honey_block", "mud", "soul_sand"
    );

    /** 食物组件覆盖不到、但视觉上属于食物的物品。 */
    private static final Set<String> FOOD_EXTRA = Set.of("cake");

    private CategoryClassifier() {}

    public static void init() {
        CACHE.clear();
    }

    public static boolean matches(BundleCategory category, Item item) {
        if (category == BundleCategory.ALL) return true;
        return categories(item).contains(category);
    }

    public static Set<BundleCategory> categories(Item item) {
        if (item == null) return FALLBACK;
        Set<BundleCategory> cached = CACHE.get(item);
        if (cached != null) return cached;
        Set<BundleCategory> computed = compute(item);
        if (computed == null) return FALLBACK; // tags/components 尚未绑定，不缓存
        CACHE.put(item, computed);
        return computed;
    }

    /** 返回 null 表示判定依赖的 tags/components 尚未绑定（调用过早），本次不缓存。 */
    private static Set<BundleCategory> compute(Item item) {
        try {
            return computeInner(item);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Set<BundleCategory> computeInner(Item item) {
        EnumSet<BundleCategory> out = EnumSet.noneOf(BundleCategory.class);
        if (item == Items.AIR) {
            out.add(BundleCategory.MISC);
            return out;
        }

        String path = BuiltInRegistries.ITEM.getKey(item).getPath();

        boolean isBlock = item instanceof BlockItem;
        BlockState state = isBlock ? ((BlockItem) item).getBlock().defaultBlockState() : null;

        boolean food = item.components().has(DataComponents.FOOD) || FOOD_EXTRA.contains(path);
        boolean plant = !food && isPlant(item, state, path);

        if (plant) out.add(BundleCategory.PLANTS);
        if (isMineral(item, path)) out.add(BundleCategory.MINERALS);
        if (food) out.add(BundleCategory.FOOD);
        if (isToolOrEquipment(item, path)) out.add(BundleCategory.TOOLS_AND_EQUIPMENT);

        if (isBlock && !plant && !food) {
            if (BLOCKS_EXTRA.contains(path) || isFullBlock(state)) out.add(BundleCategory.BLOCKS);
            else out.add(BundleCategory.NON_FULL_BLOCKS);
        }

        if (out.isEmpty()) out.add(BundleCategory.MISC);
        return out;
    }

    private static boolean isPlant(Item item, BlockState state, String path) {
        var holder = item.builtInRegistryHolder();
        if (holder.is(ItemTags.SAPLINGS) || holder.is(ItemTags.LEAVES)
                || holder.is(ItemTags.VILLAGER_PLANTABLE_SEEDS) || PLANT_EXTRA.contains(path)) {
            return true;
        }
        if (state != null) {
            Block block = state.getBlock();
            if (block instanceof BushBlock
                    || block instanceof CropBlock || block instanceof SugarCaneBlock
                    || block instanceof CactusBlock
                    || block instanceof VineBlock || block instanceof WaterlilyBlock
                    || block instanceof KelpBlock || block instanceof KelpPlantBlock
                    || block instanceof SeagrassBlock) {
                return true;
            }
            return state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS)
                    || state.is(BlockTags.SMALL_FLOWERS) || state.is(BlockTags.CROPS)
                    || state.is(BlockTags.CAVE_VINES);
        }
        return false;
    }

    private static boolean isMineral(Item item, String path) {
        var holder = item.builtInRegistryHolder();
        return holder.is(ItemTags.COALS)
                || holder.is(ItemTags.COAL_ORES) || holder.is(ItemTags.IRON_ORES)
                || holder.is(ItemTags.COPPER_ORES) || holder.is(ItemTags.GOLD_ORES)
                || holder.is(ItemTags.REDSTONE_ORES) || holder.is(ItemTags.LAPIS_ORES)
                || holder.is(ItemTags.DIAMOND_ORES) || holder.is(ItemTags.EMERALD_ORES)
                || MINERAL_EXTRA.contains(path);
    }

    private static boolean isToolOrEquipment(Item item, String path) {
        var holder = item.builtInRegistryHolder();
        if (holder.is(ItemTags.SWORDS) || holder.is(ItemTags.AXES)
                || holder.is(ItemTags.PICKAXES) || holder.is(ItemTags.HOES) || holder.is(ItemTags.SHOVELS)
                || holder.is(ItemTags.ARROWS) || holder.is(ItemTags.HEAD_ARMOR)
                || holder.is(ItemTags.CHEST_ARMOR) || holder.is(ItemTags.LEG_ARMOR)
                || holder.is(ItemTags.FOOT_ARMOR) || holder.is(ItemTags.BUNDLES)) {
            return true;
        }
        // 组件判定只对非方块物品生效：地毯等方块也可能带 EQUIPPABLE（可装备）组件。
        if (!(item instanceof BlockItem)) {
            var components = item.components();
            if (components.has(DataComponents.TOOL) || components.has(DataComponents.EQUIPPABLE)) {
                return true;
            }
        }
        if (item instanceof BucketItem || item instanceof BoatItem || item instanceof MinecartItem) {
            return true;
        }
        return TOOL_EXTRA.contains(path);
    }

    private static boolean isFullBlock(BlockState state) {
        if (state == null) return false;
        // 功能方块（熔炉/箱子/告示牌等，带 BlockEntity）按惯例算“不完整方块”。
        if (state.hasBlockEntity()) return false;
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        return !shape.isEmpty() && Block.isShapeFullBlock(shape);
    }
}
