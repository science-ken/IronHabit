# IronHabit 交接报告

> 写于 2026-09-16 · 交接人：上一任开发 agent
> 这份文档是**自包含**的：接手的人不需要看之前的对话，照着做就能开工。

---

## ⏳ 当前进度快照（2026-09-19 09:50 · 今日页改版 + 配色改版都已完成 · **上面 00:40 那份的形态与配色结论已作废**）

⚠️ **00:40 那份快照里"清单留同屏（F1）"和"暖金配色"两条都已经不成立了**，别照着它改。

### 现在到底是什么

今日页 = **① 纯磁贴 + 底部弹窗**（用户看过 `prototype.html` 后拍的板，推翻 F1）：

- 整屏只有磁贴 + 日期栏 + 三个常驻入口。
- 训练 / 饮食 / 习惯三份清单在**同一屏的 `ModalBottomSheet`** 里，由磁贴点开。
  逐组勾选仍然可达（这是全 app 唯一出口，不能丢 —— 只是从 1 tap 变 2 tap）。
- 习惯格不再跳「自律」tab，改成打开习惯清单弹窗；`onOpenDiscipline` 参数已删。
- 嵌套弹窗规则：**要弹第二层或跨路由，先收掉清单弹窗**（补录 `CheckInSheet`、
  编辑餐次 `MealEditSheet`、动作详情、编辑计划、编辑习惯都是这样处理的）。

配色 = **中性灰 + 双强调色**（对齐 `prototype.html`）：

- 中性阶全部换成 R=G=B 的纯灰，暖灰 `WarmGray*` 与暖 `Neutral*` 已删除。
- `primary` = 深青 `#0B6E5B`（行动 / 勾选 / 进度）；`tertiary` = 暗金 `#775A00`
  （成就 / 连续 / 纪录）。**刻意不合并成一个**，否则重演"打卡成功与校验失败同色"。
- 热力图不再用 `lerp(surfaceVariant, primary)`，改查 `Color.kt` 的 `HeatmapLevels`
  手调 5 档表 —— 线性插值会让 0 档与 1 档肉眼分不开（实测 1.13:1 与 1.27:1）。

### 本阶段 5 笔提交（各自独立可回退）

| commit | 内容 |
|---|---|
| `10a7b7f` | 2a 磁贴层（只用 `TodayUiState` 现有字段） |
| `a0c4b66` | 2b 本周格接 `WeeklyReview` |
| `95248d4` | 2c 本周热力条并入本周复盘格（D 版） |
| `0e17c7a` | 3 改 ① 纯磁贴 + 底部弹窗 |
| `b52b55c` | 4 配色对齐原型 + 修热力色阶 + 图标/启动图去橙 |

全量单测 47 类 / 430 用例 / 0 失败；每步都装包 + 截图（`shots/2a~4d-*.png`，已 gitignore）。

### 仍然待决的三件事

1. **`ProgressRing` 全项目零引用**（2a 起）。删还是留给二级页，没定。
2. **「这项算不算完成」的语义不一致**：真机是"有任何一条打卡就算完成"（只勾 3 组里的 1 组，
   今日完成就 +1、连续天数就点亮）；`prototype.html` 是"全勾才算"。原型改版后两边对不上，
   要统一到哪边没定。**这是既有逻辑，本轮没动过。**
3. **spec §0 的顶栏待定项**：`AppRoot.kt` 现在已是 `surface`，配色改版后是白底，
   要不要给磁贴页一点顶部区分度，没定。

### 环境提醒（仍然有效）

- 活动树 `D:\fitness-app-v204 1`（纯 ASCII 带空格，AGP 不拦空格）。
- `dev.sh app` **只启动不装包**，验证 UI 必须 `install`，否则拍到旧包。
- 磁贴改版后页面布局整体下移，**旧截图的 tap 坐标全部失效**，点之前必须重新截图定位
  （本会话踩过两次：一次误触一键打卡写进真实数据，一次靠"撤销"复原）。



## ⏳ 旧快照（2026-09-19 00:40 · 已被上面取代：形态与配色结论都已翻案）

当时那一步只有两笔提交，各自独立可回退：

| commit | 内容 | 真机 |
|---|---|---|
| `10a7b7f` | **2a** 磁贴层：连续 / 今日 n+m 分段条 / 饮食 / 习惯 / 下一项，全部取自 `TodayUiState` 现有字段，零 ViewModel 改动 | 装包无崩溃，`shots/2a-*.png` |
| `a0c4b66` | **2b** 本周磁贴接上 `WeeklyReview`：周总容量 / 平均 RPE / 总组数 / 体重 Δ | 显示 1280 kg · RPE 8.5 · 17 组 · `—`，与库里 9/14–9/17 四天打卡一致 |

`assembleDebug` + 全量单测 **47 类 / 430 用例 / 0 失败**（基线 429 + 新增 1 条回归）。

### 形态确认（F1 落地情况）

- 顶部 `ProgressRing` 已换成 `TodayBento`，**动作清单 / 饮食块 / 每周相同开关原样留在同屏**，
  逐组勾选的唯一出口没动。
- 只有习惯格带 `onClick` → `navigateToTab(DISCIPLINE)`（复用既有 tab 切换，**没新增路由**）。
- 动效一个没做（§5 整体推迟），分段条是静态的。
- 无假数据：饮食 / 习惯 / 本周格在没数据时**整块不渲染**；`targetSets = 0` 的项（spec §6-2）
  在「下一项」里退化成目标文案而不是 `0 / 0`。

### ⚠️ 本次踩到的一个真 bug（已修 + 已钉住）

`TodayViewModel.applyData` 是**逐字段手工搬运**的，第一版 2b 漏了 `weeklyReview` →
本周磁贴在**任何一周都不出现**。查库确认那 4 天都有打卡才定位到。已补回归测试
`weeklyReviewReachesUiState`（修复前该断言会红）。
**下一个人加 UiState 字段时必须同步改 `applyData`** —— 旁边 `isRepeatWeeklyOn` 的注释写的就是同一类坑。

### ⚠️ 一次误触（数据已复原，但记下来）

验证时用 `dev.sh tap 193 1443` 想点日期 chip，实际点到了「一键打卡」，
**真的写进了一条打卡**（3 组全勾、连续 0→1 天）。已用页面上的「撤销」退回，
复查截图确认回到 连续 0 天 / 今日完成 0 / 1 / 0 组。**教训：磁贴改版后页面纵向布局整体下移，
旧截图的坐标全部失效 —— 点之前必须重新截图定位，别用上一次的 y。**
（`dev.sh ui` 这次导出为空，坐标只能靠截图量。）

### 2c 为什么没做（需要你拍板）

spec §2 还剩最后一格「本周 12/35 + 热力」。两个前置都不具备：

1. **`HeatmapGrid` 是横向可滚动的多周网格**（`horizontalScroll`）。塞进磁贴会在纵向滚动页里
   套一层横向滚动，手势打架；不滚动又放不下多周。**建议**：改成只画**当前周 7 格**的小色条
   （新写一个 ~20 行的 `WeekHeatStrip`，不复用 `HeatmapGrid`），或者干脆放弃热力、保留已有的
   「本周复盘」格。
2. **「35」= 本周计划总组数，模型里没有。** `TrainingReview` 只有 `totalSets`（实际完成 17），
   计划侧要按周聚合 `WeekPlan.targetSets`，得给 `TodayViewModel` 再加一路 `PlanRepository` 查询。
   要么做这个查询，要么把该格降级成已经显示了的「完成组数」。

### 另一个待决：`ProgressRing` 现在没有消费者

今日页是它唯一的调用方，2a 之后**全项目零引用**（`ui/components/ProgressRing.kt`，114 行）。
我没删——spec §3 还留着「二级页若保留环」的设想，而你随时可能想把环要回来。
**要删还是要留，等你看过 2a/2b 的截图再定。**



## ⏳ 旧快照（2026-09-18 23:10 收尾 · 已被上面取代）

**那次收尾只提交了一笔**：`42fdbfa` —— `scripts/dev.sh` 的 `SHOTS`/`DBDIR` 从树外的
`D:/Workbuddy data/2026-09-14-09-31-06/shots` 改到活动树内的 `D:/fitness-app-v204 1/shots`，
并在 `.gitignore` 加 `shots/`（截图与含真实数据的 WAL 都不该入库）。

### 1) 坑 0 已解除：不再需要 `D:\ih-check` 镜像

活动树现在是 **`D:\fitness-app-v204 1`** —— 纯 ASCII，只剩一个空格。AGP 拦的是**非 ASCII 字符**，
空格不触发。实测：`bash scripts/dev.sh build` → **BUILD SUCCESSFUL in 1m16s**（就在这个带空格的路径上）。
含空格路径逐项复核过：`dev.sh` 里 `$SHOTS` 的每处用法都带引号，Python 侧走 `r''` 字面量，
bash 重定向 + Windows Python 读取实测正常。

`D:\ih-check\`（无 `.git` 的一次性镜像，spec.md 还是 22:18 的旧版）**已无用，可删**，本会话没动它。

### 2) 阶段 2（`7acf501` 的 §4 四项）首次拿到真机肉眼确认

之前那份快照写着"未截图未装包"，现在装了：`build` → `install` → `crash` **崩溃缓冲为空** → 3 张截图。
- **顶栏换色 ✅ 确认生效**：标题条已是浅色 `surface`，不再是整块琥珀色 `primaryContainer`。
- 截图在 `shots/before-today-{1-top,2-mid,3-bottom}.png`（已 gitignore，**未入库**，关机后仍在磁盘上）。
- ⚠️ **陷阱（本次踩到）**：`dev.sh app` 只 `am start` **不安装**。第一次截图因此拍到**旧 APK**，
  顶栏仍是琥珀色，一度看起来像 `7acf501` 白改了。**验证 UI 改动必须 `install`，不能只 `app`。**

### 3) 今日页磁贴化：**一行代码都没改**

只做了读码。`TodayScreen.kt`（423 行）现状 = 环 + 日期栏 + 训练卡列表 + 饮食 + 习惯 + 空态，全在一屏。

**⚠️ spec 内部矛盾，下一个人务必按顶部决策做**：`spec.md` §2 开头写「删掉（移到 ②）：
`ExerciseCheckCard` 列表、`MealBlock`、`HabitRow`、`RepeatWeeklyRow`」—— 那是**已作废的旧 F 形态残留**，
与顶部 2026-09-18 定稿的 **F1** 直接冲突。F1 下**动作清单 / 饮食 / 每周相同开关必须留在今日页同屏**，
只有习惯磁贴跳出到「自律」tab。§2 只当「新增磁贴层」读。

### 4) 下一步（按这个切，两步各自可独立回退）

- **2a（先做，零 ViewModel 改动）**：在 `TodayScreen` 顶部加磁贴层，只用 `TodayUiState` **现有**字段 ——
  连续 N 天（`trainingStreak`）、今日 n/7（`completedCount`/`totalCount`）、习惯 n/m（`habits`）、
  饮食 kcal/蛋白（`mealTotals` + `dietTarget`）、下一项（`plans` 取首个未完成）。
- **2b（要动数据源，单独一笔）**：本周 12/35 + 热力、本周容量/平均 RPE/总组数、体重 Δ
  这三块 **`TodayUiState` 里没有**，需给 `TodayViewModel` 注入 `StatsDao` / `BuildWeeklyReviewUseCase` /
  `BodyMetricDao`。注意「不改 ViewModel 作用域」是本轮硬约束（作用域问题见旧快照 §阶段 0 结论）。
- **环**：F1 下今日页从「环 + 清单」改成「磁贴 + 清单」，`ProgressRing` 今日页调用（`:108`）是它**唯一消费者**，
  摘掉前想清楚是删还是留。
- **禁止假数据**：睡眠、饮水、按"下一项 18:00"这类无数据源的磁贴不许出现（spec §2 已列）。

---

## ⏳ 旧快照（2026-09-18 22:45 · 已被上面取代，仅坑 0 的报错原文还有参考价值）


> **Gradle 跑过了**：`bash scripts/dev.sh test` → BUILD SUCCESSFUL / 1m45s /
> **47 个测试类 · 429 用例 · 0 失败 0 错误 0 跳过**。但**不是在 `D:\fitness-app-v204 副本` 里跑的** ——
> 那个路径含中文「副本」，AGP 直接拒绝构建，详见坑 0。
> **没做的**：`assembleDebug` 没打、没装模拟器、没截图。所以"顶栏换色 / 卡片层级 / 末片内衬"这三项
> 肉眼效果**尚未确认**，只有单测层面的"逻辑对"。

### 坑 0（**最重要，先看这条**）：本目录路径含非 ASCII，Gradle 构建被 AGP 拒绝

`D:\fitness-app-v204 副本\` 里的「副本」是中文。AGP 8.7.3 在 Windows 上检测到项目路径含
non-ASCII 会**直接失败**（连编译都不开始，24 秒内报错）：

```
> Failed to apply plugin 'com.android.internal.application'.
   Your project path contains non-ASCII characters. This will most likely cause the build to fail on Windows.
   Please move your project to a different directory.  (http://b.android.com/95744)
   This warning can be disabled by adding the line 'android.overridePathCheck=true' to gradle.properties
```

本次验证的做法：把源码树（不含 `app/build`、`.git`）复制到纯 ASCII 路径 **`D:\ih-check\`**，在那里跑
`bash scripts/dev.sh test`，通过。**这个镜像目录还在，可以直接复用**（改完源码再复制一次即可）。

三个候选方案（**明天要拍板，否则每改一版都要复制一次**）：
1. 把备份目录改名成纯 ASCII（如 `D:\fitness-app-v204-backup`）—— 最干净，但本会话的工作区根目录会变；
2. `gradle.properties` 加 `android.overridePathCheck=true` —— AGP 文档给的开关，但它只是**关掉检查**，
   后面的 aapt2 / zipalign 仍可能因中文路径出问题，我没试过，不敢担保；
3. 维持现状：写在这个目录，验证时用 `D:\ih-check` 镜像。

注意 `scripts/dev.sh` 里的 `SHOTS` 与 `DBDIR` 曾是**硬编码到原件目录**的
（`D:/Workbuddy data/2026-09-14-09-31-06/shots`），所以 `shot` / `sql` 子命令的产物会落到原件那边，不在本副本里。
**（已解决：`42fdbfa` 起改指活动树内的 `shots/`，见顶部新快照。）**

### 正在做的事

今日页改版，方案定稿在 `.scratch/ironhabit-today-bento/spec.md`（**注意：`.scratch/` 已被
`.gitignore` 忽略，不随 git 走**，原件在 `D:\Workbuddy data\2026-09-14-09-31-06\fitness-app-v204\.scratch\`）。
按 spec §7 的顺序分阶段，每阶段一个 commit + 用户验收后才进下一阶段。

已定决策（**不要再重新讨论**）：① 纯磁贴首页 + 训练/饮食/习惯**三条独立记录视图**（各带 `epochDay`
的真实路由）；`TodayViewModel` **提升到 activity 作用域**以共享日期游标；用户接受打卡 1 tap → 3 tap。

| 阶段 | 内容 | 状态 | commit |
|---|---|---|---|
| 0 | spec §0 + 作用域确认 | ✅ 结论已交付（见下"阶段 0 结论"） | — |
| 1a | spec §6-1 mask 修复 + 单测 | ✅ **单测通过**，未截图 | `db3eee6` |
| 1b | spec §4 结构修复 | ✅ **编译通过**，视觉效果未确认 | `7acf501` |
| 2 | 三条下钻路由 + 搬清单（spec §1） | ⬜ 未开始 | — |
| 3 | 今日页改磁贴首页（spec §2） | ⬜ 未开始 | — |
| 4 | 动效（spec §5） | ⬜ 未开始 | — |

`1a` / `1b` 是**两笔独立 commit**，可以单独回退其中一段。

### 阶段 1 改了哪些文件

| 文件 | 改动 | 完整度 |
|---|---|---|
| `domain/model/CheckIn.kt` | 新增 `mergedMask(count, previousMask)` + 私有 `lowestNBits`；给 `maskFromCount` 补"已有记录别用我"的警告 | ✅ 逻辑完整 |
| `domain/usecase/DetailedCheckInUseCase.kt` | 写库前读旧 mask，改走 `mergedMask` | ✅ 逻辑完整 |
| `domain/usecase/BackfillCheckInUseCase.kt` | 同上 | ✅ 逻辑完整 |
| `ui/screens/checkin/CheckInSheet.kt` | 「已完成组数」初值从 `plan.targetSets` 改为实际已勾组数（无勾选记录时才回落目标值） | ✅ 逻辑完整，**无测试** |
| `ui/navigation/AppRoot.kt` | 顶栏 `primaryContainer` → `surface`，标题 `onPrimaryContainer` → `onSurface` | ✅ 一行 |
| `ui/screens/today/TodayScreen.kt` | 整页 padding 拆开：侧+顶写在 `verticalScroll` **外**，底部写在**内** | ✅ |
| `ui/components/PlanDateStrip.kt` | 滚动行末片加 `padding(end = sm)` | ✅ |
| `ui/components/ExerciseCheckCard.kt` | RPE 滚动行同款末片内衬 | ✅ |
| `ui/theme/Color.kt` | 浅色 `surfaceContainerHighest`：`WarmGray90` → `WarmGray80`（原与 `surfaceVariant` 同值） | 🟡 编译通过，但**对画面零影响**，见坑 1 |
| `app/src/test/.../domain/model/CheckInSetMaskTest.kt` | **新文件**，7 个用例覆盖 `mergedMask` | ✅ **7/7 通过** |

### 阶段 0 结论（已查实，别再查一遍）

`TodayViewModel` 是 **NavBackStackEntry 作用域**（`TodayScreen.kt:77` 裸 `hiltViewModel()`，
`hilt-navigation-compose 1.2.0`，全 app 13 个页面都是这个写法）。构造函数**不吃 `SavedStateHandle`**
（`TodayViewModel.kt:60-78`），所以提到 activity 作用域是安全的。新页面若各自 `hiltViewModel()`，
会拿到独立实例 → `selectedEpochDay` 游标（`:87`）各一份 → 磁贴页与记录页显示的不是同一天。
做法：加 `sharedTodayViewModel()` helper（`LocalContext.current.findActivity()` → `hiltViewModel(owner)`，
**不能用 `LocalActivity`** —— composeBom 2024.12.01 = Compose 1.7.6，还没有这个 key）。

### 本次实测记录（22:40–22:45）

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **BUILD SUCCESSFUL**，1m45s，31 个 task 全执行 |
| 测试类 / 用例 | **47 / 429**，失败 0、错误 0、跳过 0 |
| 本次新增 | `CheckInSetMaskTest` **7 / 7 通过** ⇒ 进入本仓库前的真实基线是 **46 类 / 422 用例** |
| ⚠️ 文档基线过期 | 本文 §1、§9 写的"43 类 / 395 用例"是 `versionCode 15` 时代的数，**已经对不上**（差 27 条），别拿它当回归判据 |
| 编译告警（非本次引入） | `CheckInSheet.kt:162` 两处 "Condition is always 'true'"、`MealEditSheet.kt:173` 一处、测试里 4 处 ExperimentalCoroutinesApi opt-in |
| `assembleDebug` | **未跑** |
| 模拟器 / 截图 | **未做** |

### 下一步（按顺序）

1. 决定坑 0 用哪个方案，然后 `assembleDebug` + `bash scripts/dev.sh install` + `shot`，
   逐屏确认顶栏换色与滚动行末片 —— 这三项是阶段 1 唯一"肉眼可见"的产出，**目前只是逻辑正确**。
2. 补两个行为测试（阶段 1 的测试缺口）：`DetailedCheckInUseCaseTest`（已有 `0b101` + 提交 2 → 存的是 `0b101`
   而非 `0b011`）；`CheckInSheet` 的 `setsText` 初值（目前**零覆盖**）。
3. 用户点头 → 阶段 2：`sharedTodayViewModel()` helper + 三条记录视图路由 + 搬清单。

### 已知坑（本次新踩到的，写下来免得下一个人重新发现）

1. **`surfaceContainerHighest` 在整个 app 里零消费者** —— 只有 `Color.kt` 定义处。spec §4 说
   "拆开卡片才分得开"，但真正让卡片和页面分不开的是另一件事：`ExerciseCheckCard.kt:76` 用
   `colorScheme.surface`（`#FFF8F1`）当容器色，而页面 `Scaffold` 底色是 `background`（`WarmGray99` `#FFFBFF`），
   两者差不到 1% 亮度。改 `surfaceContainerHighest` 一个像素都不会变。**这一步没动它，因为它是品味决策，属于用户。**
   有 22 处消费者的是 `surfaceVariant`，所以它的值原样保留。
2. **`QuickCheckInUseCase.kt:44` 仍用 `maskFromCount(plan.targetSets)` —— 这是对的**，别顺手"统一"掉。
   一键打卡的语义就是"目标全勾满"，不存在身份丢失。
3. **`CheckInSheet` 的 `setsText` 初值逻辑没有任何测试**（`grep CheckInSheet app/src/test` = 0 命中）。
   我原本要补的 `DetailedCheckInUseCaseTest.kt`（断言"已有 0b101 + 提交 2 → 存的是 0b101 而非 0b011"）
   **没写**，被中断了。这是阶段 1 唯一的测试缺口。
4. **spec §6-2（`targetSets = 0` 时零个勾选框）按分工留在阶段 3** 处理，因为它只影响"下一项"磁贴怎么退化。
5. 工作目录是**备份副本** `D:\fitness-app-v204 副本`（可写），原件在
   `D:\Workbuddy data\2026-09-14-09-31-06\fitness-app-v204`。**两份会分叉**，改完记得说清改的是哪一份。
6. **本次 git 历史被重写过一次（本地、未推送，安全）**：原先那笔
   `5207ce5 wip: 今日页改版 阶段1 mask 修复（未完成，断电中断）` 把 11 个文件挤在一起，
   用 `git reset --soft HEAD~1` + 分批 `git add` 拆成三笔，好让每段能单独回退：
   `db3eee6`（mask 修复 + 单测）、`7acf501`（四处结构修复）、第三笔文档
   （`HANDOFF.md` 本节 + 那条既存的 `.gitignore` 改动 —— 两者在我开工前就在工作区里，不属于本阶段代码）。
   本仓库有远端 `origin git@github.com:science-ken/IronHabit.git` 但**当前分支没有上游**，
   所以这几笔都只在本地。

---

## 0. 三分钟开工

1. **源码在哪**（二选一）：
   - 带 git 的工作副本：`D:\dsh data\ironhabit-v3`（HEAD = `6a80932`，与远端 `main` 一致，工作区干净）
   - 免 git 的纯源码快照：`D:\Workbuddy data\2026-09-14-09-31-06\fitness-app-v204`
     （`versionCode = 15` / `versionName = "2.0.4"`；已实测能独立跑通全量测试）
2. **跑一次全量测试**确认环境没问题（命令见 §2），**期望：43 个测试类 / 395 用例 / 0 失败**。
3. **读 §3 和 §4** 再动代码 —— 这两节是"不知道就会写出 bug"的部分。

---

## 1. 这个项目是什么、现在到哪一步

**IronHabit**：一个 Android 健身打卡 App（Kotlin + Compose M3 + Hilt + Room + DataStore，单模块 `:app`）。
单人本地应用，无账号、无后端；"AI 教练"是可选的联网增强，**断网 / 没填 Key 时一律回落本地确定性规则，并如实标注"这不是 AI"**。

| 项 | 值 |
|---|---|
| 远端仓库 | `git@github.com:science-ken/IronHabit.git`，分支 `main` |
| 远端 HEAD | `6a80932`（本地与远端一致，已用 `git ls-remote` 校验） |
| 版本 | `versionCode = 15` / `versionName = "2.0.4"`（**debug 签名**，只用于自装/侧载） |
| 当前 APK | `D:\dsh data\deliverables\IronHabit-v2.0.4-debug.apk` |
| 测试 | 43 个测试类 / 395 用例 / 0 失败 |
| 模拟器（已在用） | MuMu 实例「软件测试」，`adb 127.0.0.1:16448`，Android 12，1080×1920，里面装着 2.0.4 与一份真实数据 |

### 各阶段做到哪了

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P1** | 档案真正参与排课（目标/体脂/体重/年龄进规则、伤病改"替代"、训练日 3–6 天可选） | ✅ 完成 |
| **P2** | 周复盘卡 + 「AI 会看到什么」数据包导出（`ironhabit-week-package/v1`） | ✅ 完成 |
| **P3** | 计划改成**按周存放** + 「没有计划就显示创建入口」+ 「每周相同」开关 | 🟡 **数据层与今日页完成**；"生成→预览→逐天采纳→撤销"目前还是**直接写库**（见 §6 A） |
| **P4** | 每日自适应建议（维持/加量/减量/换部位 + 一键采纳） | ⬜ 未开始（见 §6 E） |

---

## 2. 环境与命令（照抄即可）

### 2.1 编译 / 测试

⚠️ **不要用 `gradlew`**：wrapper 会去下载 Gradle 8.11.1，而本机证书链不通 → 直接失败。
用机器上已装的 Gradle 8.9 + JDK17 + `--offline`（依赖缓存已存在）：

```bash
cd "/d/dsh data/ironhabit-v3"        # 或用快照目录

# 全量测试（约 40–60 秒）
JAVA_HOME='C:\Users\science\android-tools\jdk17' \
  /c/Users/science/android-tools/gradle-8.9/bin/gradle \
  :app:testDebugUnitTest --console=plain --offline

# 只跑某几个类
JAVA_HOME='C:\Users\science\android-tools\jdk17' \
  /c/Users/science/android-tools/gradle-8.9/bin/gradle \
  :app:testDebugUnitTest --tests "com.ironhabit.app.domain.ai.*" --console=plain --offline

# 打 APK
JAVA_HOME='C:\Users\science\android-tools\jdk17' \
  /c/Users/science/android-tools/gradle-8.9/bin/gradle \
  :app:assembleDebug --console=plain --offline
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

（POSIX 启动器要在 git-bash 里跑。现成脚本：`D:\dsh data\tools\ironhabit-gradle.sh`，
固定了 `JAVA_HOME=jdk17` + 本地 `gradle-8.9`，与仓库 `scripts/dev.sh` 同口径。
偶发 `javaCompile.lock` / `journal-1.lock` 拒绝访问 = 多个 Gradle daemon 抢锁，重试即可。）

### 2.2 装到模拟器 + 验证

```bash
ADB="C:\Users\science\AppData\Local\Android\Sdk\platform-tools\adb.exe"
"$ADB" -s 127.0.0.1:16448 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s 127.0.0.1:16448 shell am force-stop com.ironhabit.app
"$ADB" -s 127.0.0.1:16448 shell am start -n com.ironhabit.app/.MainActivity
# 看界面：dump 出来搜文本（uiautomator 的中文正常；别用 bash 给 input text 传中文参数）
"$ADB" -s 127.0.0.1:16448 shell uiautomator dump /sdcard/ui.xml
"$ADB" -s 127.0.0.1:16448 pull /sdcard/ui.xml .
```

**验收要包含真机**（模拟器即可）：界面上的东西只能靠点一遍确认，单测证明不了。

### 2.3 想核对数据库（很有用）

```powershell
# 二进制安全：用 cmd 重定向，别用 PowerShell 的 >（会破坏二进制）
cmd /c "`"C:\Users\science\AppData\Local\Android\Sdk\platform-tools\adb.exe`" -s 127.0.0.1:16448 exec-out run-as com.ironhabit.app cat databases/ironhabit.db > after.db"
# 再把 ironhabit.db-wal / -shm 一起拉下来（同名放同目录），用 Python 的 sqlite3 打开即可
```
现成脚本：`D:\dsh data\tools\check_migration_on_device.py`。

---

## 3. 现有代码的技术约束（改了会出 bug，不是流程规矩）

这些是**代码本身的事实**，写在这里只是为了让你不用踩一遍：

1. **`minSdk = 24`** → 数据库迁移**只能** `CREATE TABLE` / `ADD COLUMN`，**禁止** `DROP COLUMN`（要 SQLite 3.35/API 31）与 `RENAME COLUMN`（要 3.25/API 28）。改索引可以（`DROP INDEX` / `CREATE INDEX` 无版本要求）。
2. **不许用 `fallbackToDestructiveMigration`**：遇到没注册的 schema 变化要**抛异常暴露**，不能静默清库。
3. **计划行不许 `DELETE` / `REPLACE`**：删除 = 软删除（`is_active = 0` + `is_user_edited = 1`），写入 = 显式 upsert。软删除行**仍占唯一槽位** —— 这是"用户删掉的那条不会被 AI 复活"的实现基础。
4. **用户手改行（`is_user_edited = 1`，含软删除行）永不被覆盖、永不被回收、永不复活**：规则层先排除槽位，UseCase 再过滤一次。
5. **中文文案只能写在 `res/values/strings.xml`**：`domain/**` 里除了肌群标签这类**数据词汇**，不许出现中文。
6. **占位符类型要对上**：`PlanBasisItem.args` 全是 `Int` → 对应资源只能用 `%d`；`TodayUiState.snackbarArgs` / `SettingsUiState.snackbarArgs` 是 `List<String>` → 只能用 `%s`。写错本地编译能过、**真机崩**（`StringResourcePlaceholderContractTest` 守着）。
7. **"不猜"**：拿不到的数据一律 `null`，**不用 `0` 冒充**。例：一周只称一次体重 → `BodyReview.deltaKg = null`；没有 RPE → `avgRpe = null`。
8. **"诚实标注"**：只有**真的调用了 DeepSeek** 才能显示"AI 生成/分析"；本地规则的结果必须标"本地规则"；回落原因要写出来（`RemoteFallbackReason`）。
9. **`PlanReason.INJURY_SAFE` 是定义级判据**：把同一天按"不做伤病过滤"再选一遍，**两次之差**才算替代。不许用"焦点里任意标签被禁忌命中"这种粗判据（那会给正常动作编理由）。
10. **有氧是"不连排同肌群"的唯一例外**：有氧配额（每周至少 N 个）优先，它不是需要 48 小时恢复的力量训练。
11. **副标签算同肌群**：动作的 `muscleGroups` 全量参与"相邻两天撞不撞"的判断（偏保守，是有意的）。
12. **可用动作不够时必须说出来**：伤病+器械过滤后主肌群种类 < 每天动作数时，允许重复肌群填满，但必须给 `basis_library_too_narrow`（不许静默）。

---

## 4. 数据模型现状（P3 之后，这块最容易搞错）

### 4.1 `week_plans` 的"哪一周"

```
week_start_epoch_day = 0        → 「每周相同」的那一份（旧的"模板"语义，现在是 opt-in）
week_start_epoch_day = 某周周一  → 只属于那一周的计划（默认形态）
```
- **为什么用哨兵 `0` 而不是 `NULL`**：SQLite 的唯一索引把 `NULL` 视为互不相等，用 `NULL` 表示"模板"会允许同一「天 × 动作」出现**多条模板行**，打破 v1 起的"同槽位只有一行"不变量。（`epochDay 0` = 1970-01-01，真实数据里不可能出现。）
- 唯一索引是 **`UNIQUE(day_of_week, exercise_id, week_start_epoch_day)`**：不这样，模板行与"某周专属行"会撞同一个槽位，而这是合法状态。
- ⚠️ **所有按槽位查/写的地方都必须带 `week_start_epoch_day`**（DAO 的 `getBySlot`），否则"给这一周排动作"会改掉另一周那一行。

### 4.2 取"这一天到底练什么"：`WeekPlanWeekResolver`（纯函数，有单测）

**整周为单位**，不逐条混：
1. 该周有启用的专属行 → **整周**都用专属行（这一天没排就是空的，不把"每周相同"那份混进来）；
2. 该周没有专属行 → 用「每周相同」那份；
3. 两份都没有 → **空**（界面显示「创建训练计划」）；
4. 只返回启用行；排序 `sortOrder → id`。

对外入口（`PlanRepository`）：
```kotlin
observeEffectivePlanForDay(dayOfWeek, weekStartEpochDay)   // 今日页用
observeEffectivePlanForWeek(weekStartEpochDay)             // 整周视图用
observeRepeatPlan()                                        // 「每周相同」那份
setRepeatWeekly(weekStartEpochDay, enabled): Int           // 勾选/取消（复制 / 软停用）
getRowsForWeek(weekStartEpochDay): List<WeekPlan>          // 生成前的"现有行"快照
```
周一取整全工程只有一处：`DateUtils.weekStartMon1(epochDay)`（迁移 `MIGRATION_4_5` 里有一份 SQL 侧副本，注释互相点名）。

### 4.3 迁移链

```
v1 ─1→2─► v2 ─2→3─► v3 ─3→4─► v4 ─4→5─► v5
1→2：逐组打卡 bitmask / RPE / 动作三态 source / 计划 is_user_edited
2→3：新建 meals 表（纯建表）
3→4：week_plans 加 week_start_epoch_day + 重建唯一索引（三列）
4→5：把"模板行"落到当前周 → 升级后本周照旧、从下周开始为空
```
`AppDatabase.VERSION = 5`；四条迁移都注册在 `di/DatabaseModule.kt`（少注册一条，对应版本的老设备会崩）。

### 4.4 备份

`BackupPayload.WeekPlanBackup` 已带 `weekStartEpochDay`（默认 `0` = 老备份按"每周相同"处理，与升级前一致）。

---

## 5. 已经做完的（可以当参考实现）

### P1 —— 档案真正参与排课
- `domain/ai/ProfileLoadPolicy.kt`（纯函数）：目标 → 组次区间/每周有氧数/加重步长；体脂 → 有氧比例；体重 vs 目标体重 → 有氧或容量；年龄 → 单日动作数 + 恢复建议。
  **刻意不做**：性别未知不判体脂；没记过体重不判体重；身高不进训练规则。
- `LocalRuleAdvisor`：训练日集合 `3→[1,3,5] / 4→[1,2,4,5] / 5→[1,2,3,5,6] / 6→[1..6]`（周日恒休息）；**按日历顺序**生成以支持"不连排同肌群"；`pickForDay` = 选动作 + 有氧配额（唯一实现）；伤病替代走定义级判据；生成依据 17 个 key。
- `UserProfile.trainingDaysPerWeek`（3–6，默认 3）+ DataStore + 设置页 chips。

### P2 —— 周复盘 + 数据包
- `domain/model/WeeklyReview.kt`、`domain/usecase/BuildWeeklyReviewUseCase.kt`（只读、确定性、7 天齐全）、`domain/usecase/ExportWeekPackageUseCase.kt`（`@Serializable` DTO、`explicitNulls`、无密钥）。
- 界面：`ui/screens/ai/WeeklyReviewBlock.kt`（复盘卡 + 数据包弹层；复制反馈画在弹层内部）。
- JSON 合同 `ironhabit-week-package/v1`：`schema / generatedAtEpochMillis / profile / week{days} / summary / library`；`includeDetails = false` → `days: []`；**没有** `streakDays`（有意）。

### P3 —— 计划按周存放（数据层 + 今日页）
- `PlanRepository` 的按周 API（见 §4.2）+ `WeekPlanWeekResolver`。
- 今日页训练区块三态：**这一周没计划** → 「创建训练计划」卡（让 AI 生成 / 自己创建）；有计划但今天没排 → "今天是休息日"；正常 → 动作卡列表 + 底部「每周相同」开关。
- `GenerateTrainingPlanUseCase(weekStartEpochDay: Long? = null)`：**生成必须落到目标周**（不带这一维会写进「每周相同」那份 —— 这是踩过的真 bug）；`existing` = 该周的行。

### 第二轮复核修掉的问题
`REVIEW-p1p2.md`（全文在 `D:\Workbuddy data\2026-09-14-09-31-06\fitness-app-v202\`）：
F-1 伤病替代编理由、F-2 退化库静默重复肌群、F-3 导出 avgRpe 未圆、F-4 轮换池前两个重点没查相交、
F-6 过期 KDoc、F-7 桶下标负数截断 —— 都已修；那轮加的 41 条对抗性用例已收进主仓
（`app/src/test/java/com/ironhabit/app/verify/`）。

---

## 6. 还没做的（任务候选，按建议优先级）

### A【最高】P3 收尾：生成 → 预览 → 逐天采纳 → 撤销 → 减载周
**现状**：点「让 AI 生成」= **直接写库**（沿用既有 `GenerateTrainingPlanUseCase` 契约：不碰手改行、回收陈旧 AI 行）。
**要做**：
1. 生成结果先落"预览态"（**不写库**），逐天可以**只采纳某一天**；
2. 「撤销本次导入」：只撤**这次 AI 写的、用户又没改过**的行；**只在本次会话有效**（重启后入口消失）；
3. **减载周**：每 4–6 周（或连续 3 周 RPE 偏高）自动排一个减载周（重量 −10%、组数 −1），提前说明理由，可以「跳过这次」；
4. **AI 可提库里没有的新动作**：远端返回 `newExercise{name, category, muscleGroups}` 时按**名字幂等入库**（`source = AI_SUGGESTED`，同名不重复建），再写进计划。
   ⚠️ 目前**只支持**"补充动作建议"那条路（`SuggestExercisesUseCase` + `adopt`）；`RemoteLlmAdvisor.parseProposalJson` **还没有** `newExercise` 分支 —— 要新写。
**验收**：单测（预览不写库 / 只采纳某天 / 撤销只撤 AI 行 / 同名不重复建 / 减载周判定）+ 真机点一遍。
**风险**：远端解析必须本地校验（组次上限、分类枚举、名字去空白限长）。

### B【中】"你正在改哪一周"的提示
**现状**：训练页「周计划」编辑的是**本周**，页面上没有"这是哪一周"的提示；从"下周"那页点「自己创建」会跳到训练页 → 用户以为在改下周，实际落在本周。
**要做**：训练页加「本周计划 · 9/14–9/20」标签；新增/编辑弹层顶部写清"你正在改：这一周 / 每周相同那份"。
**验收**：真机翻到下周点「自己创建」，界面上必须有明确提示。

### C【低】数据包落成文件
**现状**：只能"复制到剪贴板"。**要做**：SAF 存文件 / 分享（`ui/screens/settings/BackupScreen.kt` 有现成 SAF 写法可抄）。

### D【低】周日 20:00 的"每周复盘"提醒
**现状**：只有"每日训练提醒"（默认 20:00）。需要新增提醒类型（`ReminderType` + `ReminderScheduler`）。

### E【中】P4 每日自适应建议
**要做**：今日页「今日建议」卡（维持 / 加量 / 减量 / 换部位）+ 一键采纳；**只给建议，绝不自动改计划**（用户已拍板）。
判定输入：最近 RPE、连续训练天数、周容量趋势。建议文案与理由都要能解释。

### F【可选】
- 复核报告 §4 点名的未覆盖区：UI 状态机、Room 真实 SQL/并发、远端回落路径、备份恢复与排课的交互、性能、南半球/半小时时区。

---

## 7. 已知坑（别再踩一遍）

1. **生成计划不带"哪一周"→ 写进「每周相同」那份**：用户下周看着正常，但他"每周相同"的计划被偷偷换掉了。凡是构造 `WeekPlan` 的写入路径，都要问一句"这条属于哪一周"。
2. **UI 状态字段漏搬**：`overviewState` 里算好的字段，如果没在 `_uiState.update { }` 里一起 copy 过来，界面永远显示旧值（现象：开关点了弹回去，像"点了没反应"）。
3. **Snackbar 会被 `ModalBottomSheet` 盖住**：弹层里的操作反馈必须画在弹层内部。
4. **小屏裁切**：长内容 + 底部按钮要用 `verticalScroll` / 高度上限，否则 1080×1920 上按钮点不到（踩过两次）。
5. **一日多条打卡**：`completedSetsMask` 是唯一真源，`completedSets` 是派生值，别独立写。
6. **趋势桶下标**：Kotlin 的 Long 除法**向零取整**，负数会截断到 0 → 用 `Math.floorDiv`。
7. **单次称重不给 0**：`BodyReview` 带 `sampleCount`，< 2 条时 `deltaKg = null`。
8. **迁移里读设备当前时间**只有一处（`MIGRATION_4_5`，一次性数据搬家），别在别处学它。
9. **`strings.xml` 的 `%d` / `%s`**：写反了本地编译能过、真机崩。

---

## 8. 提交与交付习惯

- **提交信息**：中文 conventional commits（`feat(p3): …` / `fix: …` / `chore: bump …` / `docs: …`）；一个阶段一个提交。
- **版本号**：功能落地后 `versionCode +1`、`versionName` +0.0.1，改动集中在 `app/build.gradle.kts`。
- **不要打 git tag**：仓库的 CI 在 tag 时会跑发布流程，而它需要 4 个签名 secret（`SIGNING_KEY` / `KEY_STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）—— 本机没有，必然失败。
- **不要 `--force`**；推之前先 `git pull --rebase`（如果远端有新提交）。
- **测试别放水**：禁 `assertTrue(true)`、禁注释掉断言、禁 `@Ignore`。
- **交付时写清楚三件事**（方便别人接着干）：做了什么 / 改动了哪些文件 / **没做什么与不确定的地方**。

---

## 9. 测试与证据现状

- 全量：**43 类 / 395 用例 / 0 失败**。
- `app/src/test/java/com/ironhabit/app/verify/`：**41 条对抗性用例**，专打边界（极端档案 3840 组组合、脏数据、幂等、手改行保护、JSON 字段集合冻结、无密钥泄漏等）。**改动 P1/P2 相关代码后建议跑一遍。**
- 真机证据（截图 + UI dump）：`D:\dsh data\shots\`（`p1-basis-local-rules.png`、`p2-week-package.png`、`p3-empty-week-card.png`、`p3-repeat-weekly-switch.png`、`p3-v203-today.png`）。
- 设计文档：仓库 `docs/ai-coach-local.md`（§12 是 P1/P2 的落地记录）、`docs/ARCHITECTURE.md`、`docs/schema-v3-meals.md`。
- 复核报告全文：`D:\Workbuddy data\2026-09-14-09-31-06\fitness-app-v202\REVIEW-p1p2.md`。

---

## 10. 需要用户拍板的事

1. **先做哪块**：§6 的 A（P3 收尾）价值最高，但它依赖远端 AI 路径（需要 Key）；B/C/D/E 都能纯本地做。
2. **涉及产品行为的改动要先问**：例如"AI 能不能自动改计划""伤病替代的粒度""计划是否默认每周相同"这类，用户都已经拍过板（见 §3 / §4），要改先问。
