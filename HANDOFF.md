# IronHabit 交接报告（给下一位开发 agent）

> 写于 2026-09-16 · 交接人：上一任开发 agent（DSH）
> 这份文档是**自包含**的：你不需要看我们之前的对话，照着做就能开工。

---

## 0. 三分钟开工

1. **你的源码在这里**（免 git，直接可编译）：
   `D:\Workbuddy data\2026-09-14-09-31-06\fitness-app-v204`
   （已同步到 `versionCode = 15` / `versionName = "2.0.4"`；如果你的沙箱只能访问别的目录，把整个文件夹拷过去即可，它是纯源码、不含 `.git`）
2. **跑一次全量测试**确认环境没问题（命令见 §2），**期望：43 个测试类 / 395 用例 / 0 失败**。
3. **读 §3（红线）和 §4（数据模型）**，再动代码。这两节是"不知道就会踩雷"的部分。

产出的东西（APK / 报告）放哪、怎么交付，见 §8。

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
| 模拟器 | MuMu 实例「软件测试」，`adb 127.0.0.1:16448`，Android 12，1080×1920 |

### 三条线做到哪了

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P1** | 档案真正参与排课（目标/体脂/体重/年龄进规则、伤病改"替代"、训练日 3–6 天可选） | ✅ 完成 |
| **P2** | 周复盘卡 + 「AI 会看到什么」数据包导出（`ironhabit-week-package/v1`） | ✅ 完成 |
| **P3** | 计划改成**按周存放** + 「没有计划就显示创建入口」+ 「每周相同」开关 | 🟡 **数据层与今日页完成**；"生成→预览→逐天采纳→撤销"还是**直接写库**（见 §6 A） |
| **P4** | 每日自适应建议（维持/加量/减量/换部位 + 一键采纳） | ⬜ 未开始（见 §6 E） |

---

## 2. 环境与命令（照抄即可）

### 2.1 编译 / 测试

⚠️ **不要用 `gradlew`**：wrapper 会去下载 Gradle 8.11.1，而本机证书链不通 → 直接失败。
用机器上已装的 Gradle 8.9 + JDK17 + `--offline`（依赖缓存已存在）：

```bash
cd "/d/Workbuddy data/2026-09-14-09-31-06/fitness-app-v204"

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

（POSIX 启动器要在 git-bash 里跑。偶发 `javaCompile.lock` / `journal-1.lock` 拒绝访问 = 多个 Gradle daemon 抢锁，重试即可。）

### 2.2 装到模拟器 + 验证

```bash
ADB="C:\Users\science\AppData\Local\Android\Sdk\platform-tools\adb.exe"
"$ADB" -s 127.0.0.1:16448 install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s 127.0.0.1:16448 shell am force-stop com.ironhabit.app
"$ADB" -s 127.0.0.1:16448 shell am start -n com.ironhabit.app/.MainActivity
# 看界面：dump 出来搜文本（uiautomator 的中文正常，别用 bash 传中文参数给 input text）
"$ADB" -s 127.0.0.1:16448 shell uiautomator dump /sdcard/ui.xml
"$ADB" -s 127.0.0.1:16448 pull /sdcard/ui.xml .
```

**验收必须包含真机**（模拟器即可）：界面上的东西只能靠点一遍确认，单测证明不了。

### 2.3 想核对数据库（很有用）

```powershell
# 二进制安全：用 cmd 重定向，别用 PowerShell 的 >（会破坏二进制）
cmd /c "`"C:\Users\science\AppData\Local\Android\Sdk\platform-tools\adb.exe`" -s 127.0.0.1:16448 exec-out run-as com.ironhabit.app cat databases/ironhabit.db > after.db"
# 再把 ironhabit.db-wal / -shm 一起拉下来（同名放同目录），用 Python 的 sqlite3 打开即可
```
（`D:\dsh data\tools\check_migration_on_device.py` 是现成的核对脚本，可参考。）

---

## 3. 🔒 红线（不知道就会写出 bug）

1. **`minSdk = 24`** → 数据库迁移**只能** `CREATE TABLE` / `ADD COLUMN`，**禁止** `DROP COLUMN`（要 SQLite 3.35/API 31）与 `RENAME COLUMN`（要 3.25/API 28）。
   改索引可以（`DROP INDEX` / `CREATE INDEX` 无版本要求）。
2. **禁止 `fallbackToDestructiveMigration`**：遇到没注册的 schema 变化要**抛异常暴露**，绝不静默清库。
3. **禁止 `DELETE` / `REPLACE` 计划行**：删除 = 软删除（`is_active = 0` + `is_user_edited = 1`），写入 = 显式 upsert。
   软删除行**仍占唯一槽位**——这是"用户删掉的那条不会被 AI 复活"的实现基础。
4. **用户手改行（`is_user_edited = 1`，含软删除行）永不被覆盖、永不被回收、永不复活**：
   规则层先排除槽位，UseCase 再过滤一次（双保险）。
5. **中文文案只能写在 `res/values/strings.xml`**：`domain/**` 里除了肌群标签这类**数据词汇**，不许出现中文。
6. **占位符类型契约**：`PlanBasisItem.args` 全是 `Int` → 对应资源只能用 `%d`；
   `TodayUiState.snackbarArgs` / `SettingsUiState.snackbarArgs` 是 `List<String>` → 只能用 `%s`。
   写错会在真机上崩（`StringResourcePlaceholderContractTest` 守着这条）。
7. **"不猜"（产品红线）**：拿不到的数据一律 `null`，**绝不用 `0` 冒充**。
   例：一周只称一次体重 → `BodyReview.deltaKg = null`（不是 `0`）；没有 RPE → `avgRpe = null`。
8. **"诚实标注"（产品红线）**：只有**真的调用了 DeepSeek** 才能显示"AI 生成/分析"；
   本地规则的结果必须标"本地规则"；回落原因要写出来（`RemoteFallbackReason`）。
9. **`PlanReason.INJURY_SAFE` 的判据是定义级的**：把同一天按"不做伤病过滤"再选一遍，**两次之差**才算替代。
   **不许**用"焦点里任意标签被禁忌命中"这种粗判据（那会给正常动作编理由）。
10. **有氧是"不连排同肌群"的唯一例外**：有氧配额（每周至少 N 个）优先，它不是需要 48 小时恢复的力量训练。
11. **副标签算同肌群**：动作的 `muscleGroups` 全量参与"相邻两天撞不撞"的判断（偏保守，是有意的）。
12. **可用动作不够时必须说出来**：伤病+器械过滤后主肌群种类 < 每天动作数时，允许重复肌群填满，
    但必须给 `basis_library_too_narrow`（不许静默）。

---

## 4. 数据模型现状（P3 之后，这块最容易搞错）

### 4.1 `week_plans` 的"哪一周"

```
week_start_epoch_day = 0        → 「每周相同」的那一份（旧的"模板"语义，现在是 **opt-in**）
week_start_epoch_day = 某周周一  → 只属于那一周的计划（**默认形态**）
```
- **为什么用哨兵 `0` 而不是 `NULL`**：SQLite 的唯一索引把 `NULL` 视为互不相等，
  用 `NULL` 表示"模板"会允许同一「天 × 动作」出现**多条模板行**，打破 v1 起的"同槽位只有一行"不变量。
  （`epochDay 0` = 1970-01-01，真实数据里不可能出现。）
- 唯一索引已换成 **`UNIQUE(day_of_week, exercise_id, week_start_epoch_day)`**：
  不换的话"模板里排深蹲"和"下周专属里也排深蹲"会撞同一个槽位，而这是合法状态。
- ⚠️ **所有按槽位查/写的地方都必须带 `week_start_epoch_day`**（DAO 的 `getBySlot`），
  否则"给这一周排动作"会改掉另一周那一行。

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
周一的算法全工程只有一处：`DateUtils.weekStartMon1(epochDay)`（迁移 `MIGRATION_4_5` 里有一份 SQL 侧的副本）。

### 4.3 迁移链

```
v1 ─1→2─► v2 ─2→3─► v3 ─3→4─► v4 ─4→5─► v5
1→2：逐组打卡 bitmask / RPE / 动作三态 source / 计划 is_user_edited
2→3：新建 meals 表（纯建表）
3→4：week_plans 加 week_start_epoch_day + 重建唯一索引（三列）
4→5：把"模板行"落到**当前周** → 于是升级后本周照旧、**从下周开始为空**
```
`AppDatabase.VERSION = 5`；四条迁移全部注册在 `di/DatabaseModule.kt`（少注册一条，对应版本的老设备会崩）。

### 4.4 备份

`BackupPayload.WeekPlanBackup` 已带 `weekStartEpochDay`（默认 `0` = 老备份 → 按"每周相同"处理，与升级前一致）。

---

## 5. 已经做完的（可以当参考实现，别推翻）

### P1 —— 档案真正参与排课
- `domain/ai/ProfileLoadPolicy.kt`（纯函数）：目标 → 组次区间/每周有氧数/加重步长；
  体脂 → 有氧比例；体重 vs 目标体重 → 有氧或容量；年龄 → 单日动作数 + 恢复建议。
  **诚实边界**：性别未知不判体脂；没记过体重不判体重；**身高不进训练规则**。
- `LocalRuleAdvisor`：训练日集合 `3→[1,3,5] / 4→[1,2,4,5] / 5→[1,2,3,5,6] / 6→[1..6]`（周日恒休息）；
  **按日历顺序**生成以支持"不连排同肌群"；`pickForDay` = 选动作 + 有氧配额（唯一实现）；
  伤病替代走定义级判据；生成依据 17 个 key。
- `UserProfile.trainingDaysPerWeek`（3–6，默认 3）+ DataStore + 设置页 chips。

### P2 —— 周复盘 + 数据包
- `domain/model/WeeklyReview.kt`、`domain/usecase/BuildWeeklyReviewUseCase.kt`（只读、确定性、7 天齐全）、
  `domain/usecase/ExportWeekPackageUseCase.kt`（`@Serializable` DTO，`explicitNulls`，无密钥）。
- 界面：`ui/screens/ai/WeeklyReviewBlock.kt`（复盘卡 + 数据包弹层，复制反馈画在弹层内）。
- JSON 合同 `ironhabit-week-package/v1`：`schema / generatedAtEpochMillis / profile / week{days} / summary / library`；
  `includeDetails = false` → `days: []`。**没有** `streakDays`（有意）。

### P3 —— 计划按周存放（数据层 + 今日页）
- `PlanRepository` 的按周 API（见 §4.2）+ `WeekPlanWeekResolver`。
- 今日页训练区块三态：**这一周没计划** → 「创建训练计划」卡（让 AI 生成 / 自己创建）；
  有计划但今天没排 → "今天是休息日"；正常 → 动作卡列表 + 底部「每周相同」开关。
- `GenerateTrainingPlanUseCase(weekStartEpochDay: Long? = null)`：**生成必须落到目标周**
  （不带这一维会写进「每周相同」那份 —— 这是踩过的真 bug）；`existing` = 该周的行。

### 复核修复（另一位 agent 的对抗性复核，见 `REVIEW-p1p2.md`）
F-1 伤病替代编理由、F-2 退化库静默重复肌群、F-3 导出 avgRpe 未圆、F-4 轮换池前两个重点没查相交、
F-6 过期 KDoc、F-7 桶下标负数截断 —— **都已修**，并把它的 41 条对抗性用例收进主仓
（`app/src/test/java/com/ironhabit/app/verify/`）。

---

## 6. 还没做的（你的任务候选，按优先级）

### A【最高】P3 收尾：生成 → 预览 → 逐天采纳 → 撤销 → 减载周
**现状**：点「让 AI 生成」= **直接写库**（沿用既有 `GenerateTrainingPlanUseCase` 契约：不碰手改行、回收陈旧 AI 行）。
**要做**：
1. 生成结果先落"预览态"（**不写库**），逐天可以**只采纳某一天**；
2. 「撤销本次导入」：只撤**这次 AI 写的、用户又没改过**的行；**只在本次会话有效**（重启后入口消失）；
3. **减载周**：每 4–6 周（或连续 3 周 RPE 偏高）自动排一个减载周（重量 −10%、组数 −1），
   提前说明理由，可以「跳过这次」；
4. **AI 可提库里没有的新动作**：远端返回 `newExercise{name, category, muscleGroups}` 时按**名字幂等入库**
   （`source = AI_SUGGESTED`，同名不重复建），再写进计划。
   ⚠️ 目前**只支持**"补充动作建议"那条路（`SuggestExercisesUseCase` + `adopt`）；`RemoteLlmAdvisor.parseProposalJson`
   **还没有** `newExercise` 分支 —— 这是要新写的。
**验收**：单测（预览不写库 / 只采纳某天 / 撤销只撤 AI 行 / 同名不重复建 / 减载周判定）+ 真机点一遍。
**风险**：远端解析错误 → 必须本地校验（组次上限、分类枚举、名字去空白限长）。

### B【中】"你正在改哪一周"的提示
**现状**：训练页「周计划」编辑的是**本周**，但页面上没有任何"这是哪一周"的提示；
从"下周"那页点「自己创建」会跳到训练页 → 用户以为在改下周，实际落在本周。
**要做**：训练页加「本周计划 · 9/14–9/20」标签；新增/编辑弹层顶部写清"你正在改：这一周 / 每周相同那份"。
**验收**：真机翻到下周点「自己创建」，界面上必须有明确提示。

### C【低】数据包落成文件
**现状**：只能"复制到剪贴板"。
**要做**：SAF 存文件 / 分享（`ui/screens/settings/BackupScreen.kt` 里有现成的 SAF 写法可抄）。

### D【低】周日 20:00 的"每周复盘"提醒
**现状**：只有"每日训练提醒"（默认 20:00）。需要新增一种提醒类型（`ReminderType` + `ReminderScheduler`）。

### E【中】P4 每日自适应建议
**要做**：今日页「今日建议」卡（维持 / 加量 / 减量 / 换部位）+ 一键采纳；**只给建议，绝不自动改计划**（用户已拍板 5A）。
判定输入：最近 RPE、连续训练天数、周容量趋势。建议文案与理由都要能解释。

### F【可选】其他
- 「恢复为推荐」已有 `is_active = 1` 守卫（做过）；可顺手检查其它写入口的同类守卫。
- 复核报告 §4 里点名的未覆盖区：UI 状态机、Room 真实 SQL/并发、远端回落路径、备份恢复与排课的交互、性能。

---

## 7. 已知坑（别再踩一遍）

1. **生成计划不带"哪一周"→ 写进「每周相同」那份**：用户下周看着正常，但他"每周相同"的计划被偷偷换掉了。
   凡是构造 `WeekPlan` 的写入路径，都要问一句"这条属于哪一周"。
2. **UI 状态字段漏搬**：`overviewState` 里算好的字段，如果没在 `_uiState.update { }` 里一起 copy 过来，
   界面就永远显示旧值（现象：开关点了弹回去，像"点了没反应"）。
3. **Snackbar 会被 `ModalBottomSheet` 盖住**：弹层里的操作反馈必须画在弹层内部。
4. **小屏裁切**：长内容 + 底部按钮要用 `verticalScroll` / 高度上限，否则 1080×1920 上按钮点不到（踩过两次）。
5. **一日多条打卡**：`completedSetsMask` 是唯一真源，`completedSets` 是派生值，别独立写。
6. **趋势桶下标**：Kotlin 的 Long 除法**向零取整**，负数会截断到 0 → 用 `Math.floorDiv`。
7. **单次称重不给 0**：`BodyReview` 带 `sampleCount`，< 2 条时 `deltaKg = null`。
8. **迁移里读设备当前时间**只有一处（`MIGRATION_4_5`，一次性数据搬家），别在别处学它。
9. **`strings.xml` 的 `%d` / `%s`**：写反了本地编译能过、真机崩。

---

## 8. 怎么交付（协作规矩）

这个仓库最近是**两个 agent 交替开发**的，所以规矩比较细：

1. **一个文件只能有一个作者**：开工前先声明你要动哪些文件，别两边同时改同一个文件。
2. **新增功能优先"新增文件"**：改动越小、越容易合并。
3. **复核/验证类工作只新增** `app/src/test/java/com/ironhabit/app/verify/**` + 一份报告，**不改实现**。
4. **提交规范**：中文 conventional commits（`feat(p3): …` / `fix: …` / `chore: bump …` / `docs: …`）；
   一个阶段一个提交；**不要** `--force`、不要打 tag（tag 会触发 CI 的发布流程，而它需要 4 个签名 secret，必然失败）。
5. **版本号**：功能落地后 `versionCode +1` / `versionName` +0.0.1；**只有一个人**负责 bump 与推 GitHub。
6. **报告格式**（每次都一样，最后一节必须写）：
   - 做了什么（基线自证 + 命令 + 结果原文，不要截图）
   - 发现 / 改动（按严重度，每条可复现）
   - **我改动的文件清单**（"修改：0 个"也要写）
   - **我**没**做什么 / 不确定的地方**（写"无"也要写）
7. **不要放水**：禁 `assertTrue(true)`、禁注释掉断言、禁 `@Ignore`；测试要能证明行为，不是证明"能跑"。

---

## 9. 测试与证据现状

- 全量：**43 类 / 395 用例 / 0 失败**。
- `app/src/test/java/com/ironhabit/app/verify/`：**41 条对抗性用例**（另一位 agent 写的，已收进主仓）。
  它们专门打边界：极端档案（3840 组组合）、脏数据、幂等、手改行保护、JSON 字段集合冻结、无密钥泄漏等。
  **改动 P1/P2 相关代码后必须跑它们。**
- 真机证据（截图 + UI dump）：`D:\dsh data\shots\`（`p1-basis-local-rules.png`、`p2-week-package.png`、
  `p3-empty-week-card.png`、`p3-repeat-weekly-switch.png`、`p3-v203-today.png`）。
- 设计文档：仓库 `docs/ai-coach-local.md`（§12 是 P1/P2 的落地记录）、`docs/ARCHITECTURE.md`、`docs/schema-v3-meals.md`。

---

## 10. 需要人拍板的事（别自己决定）

1. **这一轮做哪块**：§6 的 A（P3 收尾）是最有价值的，但它依赖远端 AI 路径（需要 Key）；B/C/D/E 都可以纯本地做。
2. **谁能 push / 谁能 bump 版本**：目前约定"只有主理人（上一任 agent）"。你要是也需要推，先说清楚，避免两边同时推。
3. **是否要改产品行为**：涉及"AI 能不能自动改计划""伤病替代的粒度""计划是否默认每周相同"这类，
   都已经由用户拍过板（见 §3 / §4），**要改先问用户**。
