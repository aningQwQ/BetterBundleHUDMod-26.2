package betterbundle.gui;

import net.minecraft.client.Minecraft;

/**
 * 面板的唯一布局真源。
 *
 * <p>渲染、交互命中、排序按钮、mixin 的 bounds 判定都只读这一份，保证“画出来的”和“点得到的”
 * 永远一致，并从根本上避免越界：
 * <ul>
 *   <li>列数由左侧可用宽度决定（贴左缘时自动压缩列数）；</li>
 *   <li>高度自适应：行数随内容增长，超过屏幕可用高度才滚动；</li>
 *   <li>panelX/panelY 始终 clamp 在屏幕内（贴边）。</li>
 * </ul>
 */
public final class PanelLayout {

    public static final int SLOT = 18;
    public static final int SLOT_SPACING = 1;
    public static final int SLOT_PITCH = SLOT + SLOT_SPACING;
    public static final int PADDING = 3;
    public static final int SCROLL_W = 4;
    public static final int CAT_W = 23;
    public static final int CAT_GAP = 2;
    public static final int SEARCH_H = 14;
    public static final int GAP = 4;    // 面板与容器 GUI 的间距
    public static final int MARGIN = 2; // 面板距屏幕边缘
    public static final int MIN_COLS = 2;
    public static final int MAX_COLS = 10;
    public static final int SORT_H = 12;

    public final int panelX, panelY, panelW, panelH;
    public final boolean showCategoryBar;
    public final int columns, visibleRows, totalRows, startRow, maxScroll;
    public final int catX, catY, catW;
    public final int searchX, searchY, searchW, searchH;
    public final int scrollX, scrollY, scrollW, scrollH;
    public final int gridX, gridY, gridW, gridH;
    public final int countY;       // 计数文本顶
    public final int sortX, sortY, sortW, sortH;
    public final int lineHeight;

    private PanelLayout(int panelX, int panelY, int panelW, int panelH, boolean showCategoryBar,
                        int columns, int visibleRows, int totalRows, int startRow, int maxScroll,
                        int catX, int catY, int catW,
                        int searchX, int searchY, int searchW, int searchH,
                        int scrollX, int scrollY, int scrollW, int scrollH,
                        int gridX, int gridY, int gridW, int gridH,
                        int countY, int sortX, int sortY, int sortW, int sortH, int lineHeight) {
        this.panelX = panelX; this.panelY = panelY; this.panelW = panelW; this.panelH = panelH;
        this.showCategoryBar = showCategoryBar;
        this.columns = columns; this.visibleRows = visibleRows; this.totalRows = totalRows;
        this.startRow = startRow; this.maxScroll = maxScroll;
        this.catX = catX; this.catY = catY; this.catW = catW;
        this.searchX = searchX; this.searchY = searchY; this.searchW = searchW; this.searchH = searchH;
        this.scrollX = scrollX; this.scrollY = scrollY; this.scrollW = scrollW; this.scrollH = scrollH;
        this.gridX = gridX; this.gridY = gridY; this.gridW = gridW; this.gridH = gridH;
        this.countY = countY;
        this.sortX = sortX; this.sortY = sortY; this.sortW = sortW; this.sortH = sortH;
        this.lineHeight = lineHeight;
    }

    public static PanelLayout compute(int leftPos, int topPos, int imageHeight,
                                      int itemCount, boolean allMode, int scrollOffset, int lineHeight,
                                      boolean showCategoryBar) {
        Minecraft mc = Minecraft.getInstance();
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        int headerH = PADDING + SEARCH_H + PADDING;
        int footerH = 3 + lineHeight + 2 + SORT_H + PADDING; // 计数行 + 排序按钮 + 底padding
        // 最小高度始终按“整条分类栏”的高度：即使隐藏分类栏，面板也不缩矮（保持观感一致）。
        int catBarH = PADDING * 2 + BundleCategory.values().length * CAT_W;

        // ---- 横向：列数由左侧可用宽度决定，贴左缘时压缩 ----
        int catChrome = showCategoryBar ? (CAT_W + CAT_GAP) : 0;
        int availLeft = Math.max(0, leftPos - GAP - MARGIN);
        int chromeW = PADDING + catChrome + SCROLL_W + 2 + PADDING;
        int gridAvailW = availLeft - chromeW;
        int maxColsByWidth = (int) Math.floor((gridAvailW + SLOT_SPACING) / (double) SLOT_PITCH);
        int columns = Math.max(MIN_COLS, Math.min(MAX_COLS, maxColsByWidth));
        int gridW = columns * SLOT_PITCH - SLOT_SPACING;
        int panelW = chromeW + gridW;

        int panelX = leftPos - GAP - panelW;
        if (panelX < MARGIN) panelX = MARGIN; // 贴左缘（宁可略微盖住 GUI，也不越出屏幕）

        // ---- 纵向：自适应高度（下限含分类栏），超过屏幕可用高度才滚动 ----
        int minPanelH = Math.max(headerH + footerH + SLOT_PITCH, catBarH);
        int maxPanelH = Math.max(minPanelH, screenH - topPos - MARGIN);
        int maxGridRows = Math.max(1, (maxPanelH - headerH - footerH + SLOT_SPACING) / SLOT_PITCH);

        int totalRows = Math.max(1, (itemCount + columns - 1) / columns);
        int visibleRows = Math.max(1, Math.min(totalRows, maxGridRows));
        int maxScroll = Math.max(0, totalRows - visibleRows);
        int startRow = Math.clamp(scrollOffset, 0, maxScroll);

        int gridH = visibleRows * SLOT_PITCH - SLOT_SPACING;
        int panelH = Math.max(minPanelH, headerH + gridH + footerH);

        int panelY = topPos;
        if (panelY + panelH > screenH - MARGIN) panelY = screenH - MARGIN - panelH;
        if (panelY < MARGIN) panelY = MARGIN;

        // ---- 组件矩形（底栏锚定面板底部） ----
        int catX = panelX + PADDING;
        int catY = panelY + PADDING;
        int searchX = panelX + PADDING + catChrome;
        int searchY = panelY + PADDING;
        int searchW = panelX + panelW - PADDING - searchX;
        int gridY = panelY + headerH + PADDING;
        int gridX = searchX + SCROLL_W + 2;
        int scrollX = searchX;
        int scrollY = gridY;
        int scrollH = gridH;
        int sortY = panelY + panelH - PADDING - SORT_H;
        int sortX = gridX;
        int sortW = gridW;
        int sortH = SORT_H;
        int countY = sortY - 2 - lineHeight;

        return new PanelLayout(panelX, panelY, panelW, panelH, showCategoryBar,
                columns, visibleRows, totalRows, startRow, maxScroll,
                catX, catY, CAT_W,
                searchX, searchY, searchW, SEARCH_H,
                scrollX, scrollY, SCROLL_W, scrollH,
                gridX, gridY, gridW, gridH,
                countY, sortX, sortY, sortW, sortH, lineHeight);
    }

    // ---- 命中辅助 ----

    public boolean insidePanel(double mx, double my) {
        return mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + panelH;
    }

    /** 第 i 个分类按钮的 Y。 */
    public int catButtonY(int i) {
        return catY + i * CAT_W;
    }

    public boolean catButtonFits(int i) {
        return showCategoryBar && catButtonY(i) + CAT_W <= panelY + panelH;
    }

    public boolean catContains(int i, double mx, double my) {
        if (!catButtonFits(i)) return false;
        int by = catButtonY(i);
        return mx >= catX && mx < catX + catW && my >= by && my < by + CAT_W;
    }

    public boolean insideSearch(double mx, double my) {
        return mx >= searchX && mx <= searchX + searchW && my >= searchY && my <= searchY + searchH;
    }

    /** 是否在物品网格区域内（滚动/点击判定用）。 */
    public boolean insideGrid(double mx, double my) {
        return mx >= gridX && mx < gridX + gridW && my >= gridY && my < gridY + gridH;
    }

    /** 滚动条滑块高度（与渲染一致）。 */
    public int thumbHeight() {
        if (maxScroll <= 0) return scrollH;
        return Math.max(12, scrollH * visibleRows / totalRows);
    }

    /** 网格中 (row,col) 的格子左上角 X。 */
    public int slotX(int col) {
        return gridX + col * SLOT_PITCH;
    }

    public int slotY(int row) {
        return gridY + row * SLOT_PITCH;
    }

    /** (row,col) 对应的拍平索引；越界返回 -1。 */
    public int flatIndex(int row, int col, int itemCount) {
        if (col < 0 || col >= columns || row < 0 || row >= visibleRows) return -1;
        int idx = (startRow + row) * columns + col;
        return idx < itemCount ? idx : -1;
    }

    public int totalRowsFor(int itemCount, int columns) {
        return Math.max(1, (itemCount + columns - 1) / columns);
    }
}
