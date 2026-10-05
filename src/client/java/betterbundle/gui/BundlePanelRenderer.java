package betterbundle.gui;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import betterbundle.util.BundleContentsHelper;

public final class BundlePanelRenderer {

    private static int scrollOffset = 0;
    public static boolean visible = true;

    public static String searchQuery = "";
    public static boolean searchFocused = false;
    private static int searchCursorTick = 0;
    private static int hoveredBundleSlot = -1;

    public static BundleCategory currentCategory = BundleCategory.ALL;

    private BundlePanelRenderer() {}

    /** bundleSlot = container slot index (for clickSlot packets) */
    public record BundleSlotEntry(int bundleSlot, ItemStack bundleStack, BundleContents contents) {}

    /** 面板的统一布局（渲染、交互命中、排序按钮共用）。 */
    public static PanelLayout currentLayout(int leftPos, int topPos) {
        return currentLayout(leftPos, topPos, 0);
    }

    public static PanelLayout currentLayout(int leftPos, int topPos, int imageHeight) {
        Minecraft mc = Minecraft.getInstance();
        List<FlatItem> items = filterItems(buildFlatItemList(getBundles()), searchQuery);
        return PanelLayout.compute(leftPos, topPos, imageHeight, items.size(),
                currentCategory == BundleCategory.ALL, scrollOffset, mc.font.lineHeight);
    }

    public static int getScrollOffset() { return scrollOffset; }
    public static void scrollToTop() { scrollOffset = 0; }

    public static void scrollBy(int delta, int leftPos, int topPos, int imageHeight) {
        PanelLayout lay = currentLayout(leftPos, topPos, imageHeight);
        scrollOffset = Math.clamp(scrollOffset + delta, 0, lay.maxScroll);
    }

    public record FlatItem(int bundleSlot, int itemIndex, ItemStack stack) {}

    public static List<FlatItem> buildFlatItemList(List<BundleSlotEntry> bundles) {
        List<FlatItem> result = new ArrayList<>();
        for (BundleSlotEntry entry : bundles) {
            if (entry.contents() == null) continue;
            List<ItemStack> items = entry.contents().itemCopyStream().toList();
            for (int i = 0; i < items.size(); i++) {
                result.add(new FlatItem(entry.bundleSlot(), i, items.get(i)));
            }
        }
        // UI 层按物品 id 稳定排序：只改显示顺序，不改物理存储与交互映射
        // （FlatItem 仍带 bundleSlot/itemIndex，取出与悬停不受影响）。
        // 同 id 的跨袋条目会相邻，便于取用；再按组件、袋序、袋内序号保证确定性。
        result.sort(Comparator
                .comparing((FlatItem fi) -> BuiltInRegistries.ITEM.getKey(fi.stack().getItem()).toString())
                .thenComparingInt(fi -> ItemStack.hashItemAndComponents(fi.stack()))
                .thenComparingInt(FlatItem::bundleSlot)
                .thenComparingInt(FlatItem::itemIndex));
        return result;
    }

    public static List<FlatItem> filterItems(List<FlatItem> items, String query) {
        List<FlatItem> filtered = new ArrayList<>();
        for (FlatItem fi : items) {
            String key = BuiltInRegistries.ITEM.getKey(fi.stack().getItem()).toString();
            if (currentCategory.matches(key)) filtered.add(fi);
        }
        if (query.isEmpty()) return filtered;
        String q = query.toLowerCase(Locale.ROOT);
        List<FlatItem> sorted = new ArrayList<>(filtered);
        sorted.sort(Comparator.comparing((FlatItem fi) -> matchesSearch(fi, q) ? 0 : 1));
        return sorted;
    }

    private static boolean matchesSearch(FlatItem fi, String q) {
        String name = fi.stack().getDisplayName().getString().toLowerCase(Locale.ROOT);
        if (name.contains(q)) return true;
        if (toPinyin(name).contains(q)) return true;
        var key = BuiltInRegistries.ITEM.getKey(fi.stack().getItem());
        String fullId = key.toString().toLowerCase(Locale.ROOT);
        String path = key.getPath().toLowerCase(Locale.ROOT);
        return fullId.contains(q) || path.contains(q);
    }

    private static String toPinyin(String text) {
        try {
            HanyuPinyinOutputFormat fmt = new HanyuPinyinOutputFormat();
            fmt.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
            StringBuilder sb = new StringBuilder();
            for (char c : text.toCharArray()) {
                String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, fmt);
                if (arr != null && arr.length > 0) sb.append(arr[0]);
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    public static List<BundleSlotEntry> getBundles() { return findBundles(false); }
    public static List<BundleSlotEntry> getAllBundles() { return findBundles(true); }

    private static List<BundleSlotEntry> findBundles(boolean includeEmpty) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return List.of();
        List<BundleSlotEntry> result = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            boolean matches = includeEmpty
                    ? BundleContentsHelper.isBundle(stack)
                    : BundleContentsHelper.isNonEmptyBundle(stack);
            if (matches) {
                // Convert inventory index to container slot index
                int containerSlot = findContainerSlot(player, inv, i);
                result.add(new BundleSlotEntry(containerSlot, stack, BundleContentsHelper.getContents(stack)));
            }
        }
        return result;
    }

    /** Convert player inventory index (0-35) to container menu slot index. */
    private static int findContainerSlot(Player player, Inventory inv, int inventoryIndex) {
        for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
            if (slot.container == inv && slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
            }
        }
        return inventoryIndex; // fallback
    }

    public static boolean isRecipeBookOpen() {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof AbstractRecipeBookScreen<?> screen) return screen.recipeBookComponent.isVisible();
        return false;
    }

    public static int getHoveredBundleSlot() { return visible ? hoveredBundleSlot : -1; }
    public static boolean isEffectivelyVisible() {
        // 创造背包界面：面板整体视为不可见（各交互分支都会因此自然跳过）。
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (betterbundle.util.CreativeGuard.isCreative(mc.player, mc.gui.screen())) return false;
        return visible && !isRecipeBookOpen();
    }
    public static void toggleVisible() { visible = !visible; }

    // --- hit-test（全部基于唯一布局） ---

    public static BundleCategory getCategoryAt(double mouseX, double mouseY,
                                               int leftPos, int topPos, int imageHeight) {
        PanelLayout lay = currentLayout(leftPos, topPos, imageHeight);
        BundleCategory[] cats = BundleCategory.values();
        for (int i = 0; i < cats.length; i++) {
            if (lay.catContains(i, mouseX, mouseY)) return cats[i];
        }
        return null;
    }

    public static boolean isInsideSearchBar(double mouseX, double mouseY,
                                            int leftPos, int topPos, int imageHeight) {
        if (currentCategory != BundleCategory.ALL) return false; // Only ALL mode has interactive search
        return currentLayout(leftPos, topPos, imageHeight).insideSearch(mouseX, mouseY);
    }

    public static void onCharTyped(char c) {
        if (!searchFocused || currentCategory != BundleCategory.ALL) return;
        if (c >= 32 && c != 127) { searchQuery += c; scrollOffset = 0; }
    }

    public static void onSearchKeyPress(int key) {
        if (!searchFocused || currentCategory != BundleCategory.ALL) return;
        if (key == 259) {
            if (!searchQuery.isEmpty()) { searchQuery = searchQuery.substring(0, searchQuery.length() - 1); scrollOffset = 0; }
        } else if (key == 256) {
            searchQuery = ""; searchFocused = false; scrollOffset = 0;
        } else if (key == 257 || key == 335) {
            searchFocused = false;
        }
    }

    // --- render ---

    private static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    public static void render(GuiGraphicsExtractor graphics, int leftPos, int topPos, int imageHeight, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        // 创造模式：整个面板不绘制（仅开关打开时显示“不支持”）。
        if (betterbundle.util.CreativeGuard.isCreative(mc.player, mc.gui.screen())) {
            if (visible) renderUnsupported(graphics, leftPos, topPos, imageHeight);
            return;
        }
        if (!isEffectivelyVisible()) return;

        Font font = mc.font;
        List<BundleSlotEntry> bundles = getBundles();
        List<FlatItem> items = filterItems(buildFlatItemList(bundles), searchQuery);
        PanelLayout lay = PanelLayout.compute(leftPos, topPos, imageHeight, items.size(),
                currentCategory == BundleCategory.ALL, scrollOffset, font.lineHeight);
        scrollOffset = lay.startRow;

        boolean isAllMode = currentCategory == BundleCategory.ALL;

        // 面板底 + 边框（尺寸=实际内容，不再有魔数溢出）
        graphics.fill(lay.panelX, lay.panelY, lay.panelX + lay.panelW, lay.panelY + lay.panelH, 0x40101010);
        border(graphics, lay.panelX, lay.panelY, lay.panelW, lay.panelH, 0x60FFFFFF);

        // 分类按钮（在面板内部）
        BundleCategory[] cats = BundleCategory.values();
        for (int i = 0; i < cats.length; i++) {
            if (!lay.catButtonFits(i)) break;
            int by = lay.catButtonY(i);
            boolean selected = cats[i] == currentCategory;
            boolean hovered = lay.catContains(i, mouseX, mouseY);
            int bg = selected ? 0x60000000 : (hovered ? 0x40FFFFFF : 0x30FFFFFF);
            graphics.fill(lay.catX, by, lay.catX + lay.catW, by + lay.catW, bg);
            if (selected) border(graphics, lay.catX, by, lay.catW, lay.catW, 0x90FFFFFF);
            int iconOff = (lay.catW - 16) / 2;
            graphics.item(cats[i].getIcon(), lay.catX + iconOff, by + iconOff);
        }

        // 滚动条
        graphics.fill(lay.scrollX, lay.scrollY, lay.scrollX + lay.scrollW, lay.scrollY + lay.scrollH, 0x30FFFFFF);
        if (lay.maxScroll > 0) {
            int thumbH = Math.max(12, lay.scrollH * lay.visibleRows / lay.totalRows);
            int thumbY = lay.scrollY + (lay.scrollH - thumbH) * lay.startRow / lay.maxScroll;
            graphics.fill(lay.scrollX, thumbY, lay.scrollX + lay.scrollW, thumbY + thumbH, 0x60FFFFFF);
        }

        // 物品网格（动态列/行）
        int hoveredFlatIndex = -1;
        for (int row = 0; row < lay.visibleRows; row++) {
            for (int col = 0; col < lay.columns; col++) {
                int flatIndex = lay.flatIndex(row, col, items.size());
                if (flatIndex < 0) continue;
                int sx = lay.slotX(col);
                int sy = lay.slotY(row);

                graphics.fill(sx, sy, sx + PanelLayout.SLOT, sy + PanelLayout.SLOT, 0x40FFFFFF);
                graphics.fill(sx + 1, sy + 1, sx + PanelLayout.SLOT - 1, sy + PanelLayout.SLOT - 1, 0x50FFFFFF);

                FlatItem fi = items.get(flatIndex);
                graphics.item(fi.stack(), sx + 1, sy + 1);
                graphics.itemDecorations(font, fi.stack(), sx + 1, sy + 1);

                if (mouseX >= sx && mouseX < sx + PanelLayout.SLOT
                        && mouseY >= sy && mouseY < sy + PanelLayout.SLOT) {
                    hoveredFlatIndex = flatIndex;
                }
            }
        }

        if (hoveredFlatIndex >= 0) {
            int hRow = hoveredFlatIndex / lay.columns - lay.startRow;
            int hCol = hoveredFlatIndex % lay.columns;
            int hx = lay.slotX(hCol);
            int hy = lay.slotY(hRow);
            graphics.fill(hx, hy, hx + PanelLayout.SLOT, hy + PanelLayout.SLOT, 0x60FFFFFF);
            graphics.setTooltipForNextFrame(font, items.get(hoveredFlatIndex).stack(), mouseX, mouseY);
            hoveredBundleSlot = items.get(hoveredFlatIndex).bundleSlot();
        } else {
            hoveredBundleSlot = -1;
        }

        // 搜索栏（始终绘制，仅 ALL 可交互）
        {
            boolean active = isAllMode && searchFocused;
            int bg = isAllMode ? (active ? 0x60000000 : 0x40FFFFFF) : 0x30FFFFFF;
            graphics.fill(lay.searchX, lay.searchY, lay.searchX + lay.searchW, lay.searchY + lay.searchH, bg);
            if (active) graphics.fill(lay.searchX + 1, lay.searchY + 1, lay.searchX + lay.searchW - 1, lay.searchY + lay.searchH - 1, 0x50FFFFFF);
            int textY = lay.searchY + (lay.searchH - font.lineHeight) / 2;
            if (isAllMode && searchQuery.isEmpty() && !searchFocused) {
                graphics.text(font, "Search...", lay.searchX + 3, textY, 0xFF666666, false);
            } else if (isAllMode && !searchQuery.isEmpty()) {
                graphics.text(font, searchQuery, lay.searchX + 3, textY, 0xFFFFFFFF, false);
                searchCursorTick = (searchCursorTick + 1) % 40;
                if (searchFocused && searchCursorTick < 20) {
                    int cursorX = lay.searchX + 3 + font.width(searchQuery);
                    graphics.fill(cursorX, textY, cursorX + 1, textY + font.lineHeight, 0xFFFFFFFF);
                }
            }
        }

        // 非 ALL：在搜索栏位置显示分类标题
        if (currentCategory != BundleCategory.ALL) {
            String label = currentCategory.getDisplayName();
            int catTextY = lay.searchY + (lay.searchH - font.lineHeight) / 2;
            graphics.text(font, label, lay.searchX + 3, catTextY, 0xFFCCCCCC, false);
        }

        // 容量显示（网格下方右对齐）
        int[] stats = getBundleStats();
        String countText = stats[0] + "/" + stats[1];
        int textW = font.width(countText);
        int countX = lay.gridX + lay.gridW - textW;
        graphics.fill(countX - 2, lay.countY, countX + textW + 2, lay.countY + font.lineHeight, 0x30FFFFFF);
        graphics.text(font, countText, countX, lay.countY, 0xFFAAAAAA, false);
    }

    private static void renderUnsupported(GuiGraphicsExtractor graphics, int leftPos, int topPos, int imageHeight) {
        Font font = Minecraft.getInstance().font;
        PanelLayout lay = PanelLayout.compute(leftPos, topPos, imageHeight, 0, true, 0, font.lineHeight);
        graphics.fill(lay.panelX, lay.panelY, lay.panelX + lay.panelW, lay.panelY + lay.panelH, 0x40101010);
        border(graphics, lay.panelX, lay.panelY, lay.panelW, lay.panelH, 0x60FFFFFF);
        String msg = "创造模式不支持此功能";
        int tx = lay.panelX + (lay.panelW - font.width(msg)) / 2;
        int ty = lay.panelY + lay.panelH / 2;
        graphics.text(font, msg, tx, ty, 0xFFFF8080, false);
    }

    private static int[] getBundleStats() {
        List<BundleSlotEntry> all = getAllBundles();
        // 已用 / 总量：以 1/64 为单位表示重量。
        // 总量 = 袋子数 × 64，只随“袋子数量”变化，不随放入物品的类型变化。
        int capacity = all.size() * 64;
        int used = 0;
        for (BundleSlotEntry entry : all) {
            BundleContents c = entry.contents();
            if (c != null && !c.isEmpty()) {
                Fraction w = c.weight().result().orElse(Fraction.ZERO);
                used += w.multiplyBy(Fraction.getFraction(64, 1)).intValue();
            }
        }
        return new int[] { used, capacity };
    }
}
