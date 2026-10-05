package betterbundle.config;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.clothconfig2.gui.entries.SelectionListEntry;

/**
 * Better Bundle 的配置模型（Cloth Config / AutoConfig）。
 *
 * <p>不要在 {@code @Config} 类里加 static 字段：Cloth 会遍历全部声明的字段当选项，静态字段会导致保存失败。
 */
@Config(name = "better-bundle")
public class BetterBundleConfig implements ConfigData {

    /** 面板默认显隐策略。 */
    public enum PanelVisibility implements SelectionListEntry.Translatable {
        ALWAYS_ON,
        ALWAYS_OFF,
        REMEMBER_LAST;

        @Override
        public String getKey() {
            return "text.autoconfig.better-bundle.panelVisibility." + name();
        }
    }

    // ---------------- 常规 ----------------

    /** 是否显示面板左侧的分类栏（关闭后用搜索查找物品）。 */
    @ConfigEntry.Gui.Tooltip
    public boolean showCategoryBar = true;

    /** 面板默认显隐：一直开 / 一直关 / 跟随上次。 */
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    public PanelVisibility panelVisibility = PanelVisibility.REMEMBER_LAST;

    /** 上次的面板显隐状态（仅 REMEMBER_LAST 使用，不显示在配置界面）。 */
    @ConfigEntry.Gui.Excluded
    public boolean lastVisible = true;

    // ---------------- 高级 ----------------

    /** 一键整理：每个逻辑动作的最小间隔（tick）。 */
    @ConfigEntry.Category("advanced")
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 1, max = 20)
    public int minDelayTicks = 2;

    /** 一键整理：每个逻辑动作的最大间隔（tick）。 */
    @ConfigEntry.Category("advanced")
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 1, max = 20)
    public int maxDelayTicks = 5;

    /** 一键整理：等待服务端确认的超时（tick）。 */
    @ConfigEntry.Category("advanced")
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 5, max = 200)
    public int confirmTimeoutTicks = 20;

    /** 一键整理：单次允许的最大动作数（超过则提示分批）。 */
    @ConfigEntry.Category("advanced")
    @ConfigEntry.Gui.Tooltip
    @ConfigEntry.BoundedDiscrete(min = 16, max = 2048)
    public int maxTotalMoves = 256;

    @Override
    public void validatePostLoad() {
        if (minDelayTicks < 1) minDelayTicks = 2;
        if (maxDelayTicks < minDelayTicks) maxDelayTicks = minDelayTicks;
        if (confirmTimeoutTicks < 5) confirmTimeoutTicks = 20;
        if (maxTotalMoves < 16) maxTotalMoves = 256;
        if (panelVisibility == null) panelVisibility = PanelVisibility.REMEMBER_LAST;
    }
}
