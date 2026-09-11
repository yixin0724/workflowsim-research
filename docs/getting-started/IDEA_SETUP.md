# IDEA 配置指南：experiments 模块设置

## 📢 重要更新

**从 2024-09 开始，experiments 模块已改为默认构建，不再需要手动激活 profile。**

如果你看到 experiments 下的文件显示为**橙色咖啡杯图标** ☕，说明 IDEA 还没有重新加载项目。

---

## 快速解决（1 步操作）

**只需重新加载 Maven 项目**：

1. 打开 IDEA 右侧的 **Maven** 工具窗口
2. 点击顶部的 **🔄 Reload All Maven Projects** 图标
3. 等待重新导入完成（右下角状态栏显示进度）

**验证成功**：
- experiments 下的 `.java` 文件变成**蓝色 C 图标** 🔵
- 可以右键 `main` 方法 → **Run**
- 代码自动补全正常

---

## 如果重新加载后还是橙色？

### 方案 1：清理缓存并重启（推荐）

```
File → Invalidate Caches / Restart → Invalidate and Restart
```

重启后 IDEA 会重新索引项目，应该就能正确识别。

### 方案 2：检查 Maven 导入设置

1. **File** → **Settings** (Windows/Linux) 或 **Preferences** (Mac)
2. 搜索 **Maven**
3. 确保勾选：
   - ☑ **Import Maven projects automatically**
   - ☑ **Automatically download: Sources** (可选，推荐)

### 方案 3：手动标记源代码目录（临时）

如果以上都不行，可以手动标记：

1. **File** → **Project Structure** (`Cmd + ;` 或 `Ctrl + Alt + Shift + S`)
2. 左侧选择 **Modules**
3. 应该能看到 `workflowsim-experiments` 模块
4. 选中后，在右侧目录树中找到 `experiments/src/main/java`
5. 右键 → **Mark Directory as** → **Sources Root**
6. 同样标记 `experiments/src/test/java` 为 **Test Sources Root**
7. **Apply** → **OK**

⚠️ 这种方式是临时的，重新导入 Maven 项目后会失效。

---

## 验证 IDEA 配置是否成功

### 检查图标

| 文件 | 正确图标 | 错误图标 |
| --- | --- | --- |
| `experiments/src/main/java/**/*.java` | 🔵 蓝色 C | ☕ 橙色咖啡杯 |
| `simulator/src/main/java/**/*.java` | 🔵 蓝色 C | - |

### 测试右键运行

1. 打开 `experiments/src/main/java/org/workflowsim/examples/WorkflowSimBasicExample1.java`
2. 找到 `public static void main(String[] args)` 方法
3. 在方法名上右键
4. 应该能看到 **▶ Run 'WorkflowSimBasicExample1.main()'**

### 测试代码补全

在 experiments 下的任意 `.java` 文件中：
- 输入 `SimulationConfig.` 应该有自动补全
- `Ctrl/Cmd + 点击` 类名应该能跳转到定义

---

## 常见问题

### Q1: 为什么之前需要勾选 profile，现在不需要了？

**之前的设计**：experiments 模块在 Maven profile 中，默认不构建（需要 `-Pexperiments`），IDEA 也不会导入。

**当前设计**：experiments 模块已移到默认构建列表，IDEA 会自动识别。

这个改变是为了简化使用，让新用户不需要了解 Maven profile 概念就能运行示例。

### Q2: 我的 IDEA 版本很旧，Maven 窗口长得不一样？

**IDEA 2020 之前的版本**：
1. 打开 **Maven Projects** 工具窗口（不是 Maven）
2. 点击工具栏的 **Reimport All Maven Projects** 按钮（两个圆箭头图标）

### Q3: 重新加载项目需要多久？

通常 10-30 秒，取决于机器性能。状态栏会显示 "Indexing..." 进度。

### Q4: 为什么文档中还提到 experiments profile？

`IDEA_SETUP.md` 是配置参考文档，保留了历史问题的排查方法。实际使用时，你只需重新加载项目即可。

---

## 项目结构说明

```
WorkflowSim-1.0/
├── simulator/               # 核心模拟器（永远构建）
│   └── src/
│       ├── main/java/       # 🔵 应显示为蓝色 C
│       └── test/java/       # 🔵 应显示为蓝色 C
│
└── experiments/             # 示例与研究代码（现已默认构建）
    └── src/
        ├── main/java/       # 🔵 应显示为蓝色 C（之前是 ☕）
        └── test/java/       # 🔵 应显示为蓝色 C（之前是 ☕）
```

---

## 配置后的完整运行流程

### 在 IDEA 中运行实验

1. **确保已重新加载 Maven 项目**（Maven 窗口 → 🔄 Reload）

2. **创建你的实验类**（例如在 `experiments/src/main/java/org/workflowsim/mystudy/MyExperiment.java`）

3. **编写代码**（参考 [`RUN_EXPERIMENTS.md`](RUN_EXPERIMENTS.md) 的完整模板）

4. **右键运行**
   - 在 `main` 方法内右键
   - 选择 **Run 'MyExperiment.main()'**

5. **查看输出**
   - 仿真结果会在 **Run** 工具窗口显示

---

## 下一步

- **实验代码模板**：[`RUN_EXPERIMENTS.md`](RUN_EXPERIMENTS.md)
- **算法选择指南**：[`ALGORITHMS.md`](ALGORITHMS.md)
- **数据集选择**：[`DATASETS.md`](DATASETS.md)
- **构建命令参考**：[`BUILD.md`](BUILD.md)
