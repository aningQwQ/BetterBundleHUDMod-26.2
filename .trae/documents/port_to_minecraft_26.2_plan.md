# 移植到 Minecraft 26.2 计划

## 项目概述

**项目名称**: Better Bundle (better-bundle)
**当前版本**: Minecraft 26.1, Fabric Loader 0.19.2, Fabric API 0.145.1+26.1
**目标版本**: Minecraft 26.2
**项目类型**: Fabric 客户端 Mod，为背包添加侧面板以快速访问捆绑包（Bundle）内容

## 代码库研究结论

### 项目结构
- 构建系统: Gradle + Fabric Loom
- Java 版本: Java 25
- Mod 依赖: Fabric API, pinyin4j (嵌入)

### 核心模块
1. **`BetterBundleMod`** - 客户端入口点，注册分类物品
2. **`BundleCategory`** - 物品分类枚举（全部、方块、不完整方块、植物、食物、工具装备、杂物、矿物），包含硬编码的物品 ID 列表
3. **`BundlePanelRenderer`** - 侧面板渲染逻辑，包括分类按钮、搜索栏、物品网格、滚动条
4. **`BundlePanelInteraction`** - 面板交互处理，包括点击、滚轮、空格快速存入等
5. **`BundleContentsHelper`** - Bundle 内容工具类，使用 Data Components API

### Mixin 列表
1. **`AbstractContainerScreenMixin`** - 注入容器屏幕的鼠标/键盘事件处理
   - `mouseClicked`, `mouseReleased`, `keyPressed`, `mouseDragged`, `mouseScrolled`
2. **`AbstractRecipeBookScreenMixin`** - 注入配方书屏幕的事件处理
   - `mouseClicked`, `charTyped`, `keyPressed`
3. **`InventoryScreenMixin`** - 注入渲染方法绘制面板
   - `extractContents` (使用 `GuiGraphicsExtractor`)

### Access Widener 使用
- `AbstractContainerScreen`: `leftPos`, `topPos`, `imageHeight`, `imageWidth`, `hoveredSlot`
- `AbstractRecipeBookScreen`: `recipeBookComponent`

### 关键 API 依赖
- 输入事件: `MouseButtonEvent`, `KeyEvent`, `CharacterEvent`
- 网络包: `ServerboundSelectBundleItemPacket`, `ServerboundContainerClickPacket`
- 数据组件: `DataComponents.BUNDLE_CONTENTS`, `BundleContents`
- 容器: `ContainerInput`, `HashedStack`
- 渲染: `GuiGraphicsExtractor`
- 注册表: `BuiltInRegistries.ITEM`

## 移植步骤

### 第一步：更新构建配置

1. **更新 `gradle.properties`**:
   - `minecraft_version`: `26.1` → `26.2`
   - `fabric_api_version`: 更新为对应 26.2 的最新版本
   - 检查 `loader_version` 是否需要更新
   - 检查 `loom_version` 是否需要更新

2. **更新 `fabric.mod.json`**:
   - `depends.minecraft`: `>=26.1` → `>=26.2`
   - 检查 `fabricloader` 最低版本要求

### 第二步：验证编译并修复 API 变更

1. **运行构建**检查编译错误：
   ```
   ./gradlew build
   ```

2. **根据编译错误逐一修复**，重点关注：
   
   **输入事件类可能的变更**:
   - `net.minecraft.client.input.MouseButtonEvent` - 方法签名变化
   - `net.minecraft.client.input.KeyEvent` - 方法签名变化
   - `net.minecraft.client.input.CharacterEvent` - 方法签名变化
   
   **屏幕渲染方法可能的变更**:
   - `extractContents` 方法签名或 `GuiGraphicsExtractor` 的变化
   - `AbstractContainerScreen` 字段的访问方式变化
   
   **Bundle 相关 API 可能的变更**:
   - `BundleContents` API 变化
   - `ServerboundSelectBundleItemPacket` 构造函数变化
   - `ServerboundContainerClickPacket` 构造函数变化
   - `ContainerInput` 枚举变化
   - `HashedStack` 变化
   
   **物品/注册表 API 可能的变更**:
   - `BuiltInRegistries` 的变化
   - `DataComponents` 的变化
   
   **其他可能的重命名**:
   - 类名、方法名的映射变化（Yarn 映射更新）

### 第三步：修复 Mixin 问题

1. **检查 mixin 目标方法是否存在**:
   - `AbstractContainerScreen.mouseClicked`
   - `AbstractContainerScreen.mouseReleased`
   - `AbstractContainerScreen.keyPressed`
   - `AbstractContainerScreen.mouseDragged`
   - `AbstractContainerScreen.mouseScrolled`
   - `AbstractRecipeBookScreen.mouseClicked`
   - `AbstractRecipeBookScreen.charTyped`
   - `AbstractRecipeBookScreen.keyPressed`
   - `AbstractContainerScreen.extractContents`

2. **如有方法签名变化，更新 mixin 注入点**

3. **检查 access widener 中的字段和类是否仍然存在**

### 第四步：验证运行时行为

1. **启动游戏测试**：
   ```
   ./gradlew runClient
   ```

2. **验证功能**:
   - 背包侧面板是否正常显示
   - 分类切换是否正常工作
   - 搜索功能是否正常
   - 点击物品取出是否正常
   - Shift+点击快速转移是否正常
   - 空格+点击快速存入是否正常
   - 滚轮滚动是否正常
   - 配方书打开时面板是否正确隐藏
   - 切换按钮是否正常工作

### 第五步：更新物品分类（如有需要）

1. **检查 26.2 是否有新物品**，如果有则需要添加到相应分类中
2. **检查是否有物品被移除或重命名**

## 潜在风险和注意事项

### 高风险区域
1. **Mixin 方法签名变化** - Minecraft 小版本更新经常会改变 GUI 屏幕的输入处理方法签名
2. **GuiGraphicsExtractor API 变化** - 渲染提取器 API 在版本间可能有较大变化
3. **网络包结构变化** - `ServerboundContainerClickPacket` 和 `ServerboundSelectBundleItemPacket` 构造参数可能变化
4. **Bundle 数据组件 API 变化** - `BundleContents` 的 `weight()` 等方法可能有变化

### 中风险区域
1. **Access Widener 字段重命名** - 字段名可能在 Yarn 映射中被重命名
2. **输入事件类重构** - 输入事件系统可能被重构

### 低风险区域
1. **物品注册和分类** - 硬编码的物品 ID 列表，除非有大量物品重命名，否则影响较小
2. **pinyin4j 依赖** - 第三方库，不受 Minecraft 版本影响

## 风险应对策略

1. **逐步推进**: 先更新版本号编译，根据错误信息逐一修复
2. **参考 Fabric API 更新日志**: 查看 Fabric API 对应 26.2 版本的变更说明
3. **参考 Minecraft 更新日志**: 了解 26.2 的主要变更
4. **对照官方示例**: 参考 Fabric 官方的示例 mod 最新版本

## 文件修改清单

### 配置文件（必改）
- [ ] `/workspace/gradle.properties` - 版本号更新
- [ ] `/workspace/src/main/resources/fabric.mod.json` - 依赖版本更新

### 可能需要修改的源代码文件
- [ ] `/workspace/src/client/java/betterbundle/mixin/AbstractContainerScreenMixin.java` - Mixin 方法签名
- [ ] `/workspace/src/client/java/betterbundle/mixin/AbstractRecipeBookScreenMixin.java` - Mixin 方法签名
- [ ] `/workspace/src/client/java/betterbundle/mixin/InventoryScreenMixin.java` - 渲染方法
- [ ] `/workspace/src/client/java/betterbundle/gui/BundlePanelRenderer.java` - 渲染相关 API
- [ ] `/workspace/src/client/java/betterbundle/gui/BundlePanelInteraction.java` - 网络包/容器 API
- [ ] `/workspace/src/client/java/betterbundle/util/BundleContentsHelper.java` - Bundle API
- [ ] `/workspace/src/client/java/betterbundle/gui/BundleCategory.java` - 物品列表更新

### 可能需要修改的资源文件
- [ ] `/workspace/src/main/resources/better-bundle.accesswidener` - 字段/类名更新
- [ ] `/workspace/src/client/resources/better-bundle.client.mixins.json` - Mixin 配置
