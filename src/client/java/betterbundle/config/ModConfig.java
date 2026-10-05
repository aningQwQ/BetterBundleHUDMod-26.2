package betterbundle.config;

import me.shedaniel.autoconfig.AutoConfig;

/** 持有已注册的配置实例。 */
public final class ModConfig {

    private static BetterBundleConfig instance;
    private static final BetterBundleConfig FALLBACK = new BetterBundleConfig();

    private ModConfig() {}

    public static void init() {
        instance = AutoConfig.getConfigHolder(BetterBundleConfig.class).getConfig();
    }

    public static BetterBundleConfig get() {
        if (instance == null) {
            try {
                init();
            } catch (Throwable t) {
                return FALLBACK; // 尚未注册时退回默认值，渲染路径不应崩
            }
        }
        return instance != null ? instance : FALLBACK;
    }

    /** 记录并保存“上次面板显隐”（仅 REMEMBER_LAST 策略使用）。 */
    public static void rememberVisible(boolean value) {
        BetterBundleConfig cfg = get();
        cfg.lastVisible = value;
        try {
            AutoConfig.getConfigHolder(BetterBundleConfig.class).save();
        } catch (Throwable ignored) {
            // 保存失败不影响本次显示
        }
    }
}
