# IronHabit Bug 修复方案（待确认稿）

> 依据：`BUG_INSPECTION_REPORT.md`(V1) / `BUG_INSPECTION_REPORT_V2.md`(V2) / `FIX_VERIFICATION_REPORT_V3.md`(V3)。
> 约束：**AI 教练相关代码尽量不动**（`domain/ai/**`、`ui/screens/ai/**`、`AiCredentialsStore`、`DeepSeekClient` 及其测试）——你计划删除外部 Key 接入、另做 AI 帮训功能，这些区域的遗留问题大多会随删除自然消失。
> 原则：不触碰 Room schema（无新增迁移）、strings.xml 只增不改、每批次结束跑全量单测、不新增任何网络库。

---

## 步骤 0：建立回滚基线（动手前必做）

1. `git init` + 首次提交（含三份报告与本方案），`.gitignore` 已覆盖 keystore 等敏感文件；
2. 跑一次 `gradle testDebugUnitTest`（用本机 `C:\Users\science\android-tools\gradle-8.9\bin\gradle.bat`，**不要用 gradlew.bat**，它下载 8.11.1 会网络超时）记录基线：当前 864 用例全绿；
3. 之后每完成一个批次提交一次，出问题可单批回滚。

---

## 批次 A：数据完整性 + 崩溃防线（必修，约 10 个文件）

| # | 修复项 | 位置 | 改法 | 验证 |
|---|---|---|---|---|
| A1 | **新-P2 换餐次槽位不迁移 meal_items** | `MealDao.kt:203-219` | 换槽分支补一条单语句：`UPDATE meal_items SET meal_id=:to, sort_order = sort_order + (SELECT COALESCE(MAX(sort_order),-1)+1 FROM meal_items WHERE meal_id=:to) WHERE meal_id=:from`（保持在同一 @Transaction 内，明细追加到目标餐末尾） | 新增 androidTest 用例（沿用 `CheckInDaoTest` 模式；本机无设备，进 CI/真机跑）；同时把 `meal_slot_deleted_hint` 文案补上"已记的明细会一并移过去" |
| A2 | **CE 吞噬收尾（约 13 处）** | `TodayViewModel:761/772`、`TrainViewModel:258/285/322/238`、`DisciplineViewModel:159/175/192`、`AddEditHabitViewModel:282`、`AddEditPlanViewModel:161/284`、`SettingsViewModel:226/254/393`、`PlanPreviewViewModel:257/323/370`、`FoodLibraryViewModel:259` | 所有 `catch(Throwable)`/`catch(Exception)`/`runCatching` 包住挂起调用的地方，先 `if (e is CancellationException) throw e` 再走失败分支（照抄本文件内已有的正确写法） | 新增一个**源码扫描契约测试**（仿照工程已有的 `ApplicationCoroutineExceptionHandlerTest` 源码断言模式），永久防止回归 |
| A3 | **设置页三处读-改-写丢更新（V2-P3-4）** | `SettingsViewModel.kt:307-338` | 三个 toggle 共用一把 `Mutex`，读-改-写全程持锁 | 并发单测：两个并发 toggle 断言两次翻转都落库 |
| A4 | **import() 吞 CancellationException（P3-30）** | `BackupRepositoryImpl.kt:130` | `runCatching` 改为 try/catch 并重抛 CE | 现有 `BackupRestoreRulesTest` 回归 |
| A5 | **闹钟自续期不查软删（P3-5）** | `ReminderReceiver.kt:68-84` | 判据 `habit == null` 改 `habit?.isActive != true`（一行，封死孤儿闹钟竞态） | 代码评审 + 现有 `ReminderTriggerTimeTest` 回归 |
| A6 | **"已删除"段恢复按钮未做未来日门禁（新-P3-1）** | `TodayScreen.kt:756,420-427` | `MealDeletedRow` 补 `enabled = !isFutureDay`（与同分支 MealBlock 一致） | UI 评审；`TodayViewModelMealEditTest` 回归 |
| A7 | **编辑餐弹层跨午夜搬到新的一天（新-P3-5）** | `TodayViewModel.kt:490-506` | 保存时的 `epochDay` 改用打开弹层时快照的 `editingMeal.dateEpochDay`，不用实时游标 | 单测：模拟跨午夜后保存，断言仍写原日 |
| A8 | **FoodLibrary 启停两处裸 launch（V2-P3-3 残余）** | `FoodLibraryViewModel.kt:276-284` | `onDeactivate/onActivate` 补 catch（同 A2 口径） | 纳入 A2 的源码扫描测试覆盖 |

## 批次 B：非 AI 区域的 P3（约 12 个文件）

| # | 修复项 | 位置 | 改法 |
|---|---|---|---|
| B1 | Converters 枚举未知值 NPE（P3-1） | `data/local/entity/Converters.kt` | 四个枚举转换器照抄 `FoodEntity` 已有的"安全兜底"模式：未知值回落枚举默认成员，不再返回 null |
| B2 | 复制到下周复活软删槽位（P3-4）⚠️ 需你拍板语义 | `PlanRepositoryImpl.kt:220-226,99-113` | 方案：复制时目标槽位若被**软删行**占用，先硬删该行再插新行（schema 唯一索引决定必须先清位；"复制=以源周为准覆盖目标周"语义也更一致） |
| B3 | 导出设置非原子快照（P3-6） | `BackupRepositoryImpl.kt:107-111` | 三次 `first()` 改为 `combine(三流){…}.first()` 一次取齐 |
| B4 | 按克数改份量丢失"1.5碗"标签（P3-12） | `ChangeMealItemPortionUseCase.kt:52-57` | serving 为 null 时回落 `existing.servingUnit/servingCount`（照抄 `AddMealItemUseCase:64-65` 的份优先口径） |
| B5 | 空条目餐落库（P3-13） | `UpsertMealUseCase.kt:28-36` | `cleaned` 为空时拒收并返回失败（让调用方提示），与注释声称的行为对齐 |
| B6 | 计划预览部分采纳后陈旧快照（P3-10） | `PlanPreviewViewModel.kt:322` / `PlanPreviewHolder.kt` | `load()` 渲染前把快照里**已采纳的天**标记为已采纳（或 commit 后同步从快照移除对应天） |
| B7 | 预览页空快照卡死（P3-29） | `PlanPreviewViewModel.kt:187` / `PlanPreviewScreen.kt:226` | peek==null 时给明确的"草案已失效，请返回重新生成"错误态而非静默空页 |
| B8 | 测试时钟快照 flake（B-3） | 7 个测试类 | `Clock.System` 快照改为注入固定时钟（工程已有 `TestTodayClock` 可复用） |
| B9 | RestoreMealUseCase KDoc 失实（新-P3-9） | `RestoreMealUseCase.kt:12-13` | 注释改为描述真实机制（upsertGenerated 命中软删行是原地复活） |

## 批次 C：需要你拍板的可选项

| # | 事项 | 我的建议 |
|---|---|---|
| C1 | V2-P3-2：导入 v1/v2 老备份会重置主题/单位/提醒（`BackupRepositoryImpl.kt:206-212`） | **补版本闸**（三行：schemaVersion<3 时不覆盖这三项）。如果你认为"整体替换"是有意的兼容口径，也可以选择不改、只在 KDoc 声明结案 |
| C2 | P3-25：每次切「动作库」分段重发一次 AI 建议（远端=一次 HTTP 花费） | 改为**每次会话只拉一次 + 下拉手动刷新**。若你打算重做 AI 帮训，这条也可以随功能一起重做 |
| C3 | P3-24：同文案 Snackbar 连发第二条被吞 | 给 snackbar 事件加单调序号做 key（三屏各一处小改）。影响很小，可选 |
| C4 | P3-20：DietPlanGenerator 空模板除零防御 | 属 `domain/diet` 不是 AI UI，两行守卫，建议顺手修；若 DietPlanGenerator 也在你重做范围内则跳过 |

---

## 明确不动 / 延后的区域（AI 教练相关）

以下条目**本轮一律不碰**，待你删除外部 Key 功能时大多自然消失；删除完成后我可以再做一轮增量验收：

- P2-13 深嵌套 JSON 崩溃（`ExternalPlanDocumentParser` —— 外部计划导入功能的一部分）
- P3-11 / P3-14 / P3-15 / P3-17(远端) / P3-18 / P3-19 / P3-21（`domain/ai/**` 内）
- P3-8（`AiCredentialsStore.resetStorage` 锁竞态）、P3-16（`DeepSeekClient` 超时参数）
- `AiCoachViewModel` / `ExternalImportViewModel` / `ImportPlanSheet` 内的 CE 残余与 copied 残留（P3-27/28/26）
- 新-P3-7（`AskCoachUseCaseTest` 钉死旧行为——改 P3-14 时才会碰）

> 提醒：**P2-13 是剩余唯一的 P2（崩溃级）**，但它在外部计划导入路径上——如果你确定该功能会被删除替换，延后是合理的；如果新方案仍会解析外部粘贴文本，届时务必带上深度预检。

---

## 验证与交付

1. 每批次完成 → 全量 `testDebugUnitTest` 必须全绿 → git 提交（commit message 注明对应报告条目编号）；
2. A、B 批次完成后出 **V4 验收报告**（逐条复核修复，沿用 V3 格式，含新增单测清单）；
3. 预计新增/修改测试：A2 源码扫描契约测试、A3 并发单测、A7 跨午夜单测、B1 兜底单测、B4/B5 用例单测、B8 时钟固化、A1 androidTest 用例（本机无设备，进 CI/真机）。

## 需要你确认的 4 个点

1. **AI 延后清单**（上文"明确不动"一节）是否认可？
2. **B2 语义**：复制到下周时，目标周被你手动删掉的槽位 = 允许被源周内容覆盖（先硬删软删行再插入），可以吗？
3. **C1**：v1/v2 备份导入重置设置——补版本闸，还是按兼容口径结案？
4. **C2/C3/C4** 三个可选项做不做？

确认后我按 步骤 0 → A → B →（C 中你勾选的）→ V4 报告 的顺序动手。
