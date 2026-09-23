# IronHabit 交接报告

> 写于 2026-09-16 · 交接人：上一任开发 agent
> 这份文档是**自包含**的：接手的人不需要看之前的对话，照着做就能开工。

---

## ✅ 最新快照（2026-09-23 下午 · 「每周相同」拆成两个动作 · 全部本地 commit）

> 现值自己跑：`git rev-list --left-right --count origin/main...HEAD`（右=未推）。**这一轮之后仍未推**，
> 说"推"才推；打 tag 要单独授权（见 §8）。

用户拍板：训练卡弹层底部那个「每周相同」开关**改成只对下周生效**。做完才发现要害不在开关：

- **无限往后是模板兜底造成的**，不是复制造成的 —— `WeekPlanWeekResolver.effectiveForDay:55`
  让任何没有专属行的周回落 `week_start_epoch_day=0`。所以只改开关 = 那 19 条模板行照样无限生效。
  拆成两个动作：「复制到下周」（一次性按钮，写下周专属行）+「以后每周都用这份」出口行
  （**只在模板真有启用行时出现**，点了 `deactivateRepeatPlan()`）。
- **真机读数**：模板 19 行 / 启用 **0** → 出口行整行不出现（这台机此刻本来就没在无限重复）；
  点复制 → 12 条落 `20724`，模板与本周**一格没动**；再点 → 确认框念出「下周现有 12 条」；
  测完按 id 删干净，与开工基线 `--strip-trailing-cr` 逐行一致。
- **我自己先写错、量出来才改对的两处**（值得下一个人当模板看）：
  ① 回执走全局 Snackbar → 被 `ModalBottomSheet` 盖住，写库成功而界面零反馈（§7 坑 3 复发）；
  ② 改成弹层内另起一行 → 那两行是弹层最后一块，回执把按钮顶到 **y=1911**（屏高 1920）。
  最终让它占副标题那一格，零高度变化。**口径：弹层最后一块的反馈不能增加高度。**
- 656 单测全绿（`--rerun-tasks`）；`setRepeatWeekly` 已删，`isRepeatWeeklyOn` → `repeatPlanRowCount`。
- ⚠️ **A13 还挂着**：我把「A13（习惯重复日全不选静默变全周）」和「每周相同」当成同一件事去问，
  用户答的是后者。习惯那条**仍未定**，别当已决。
- ⚠️ **今日页不再有"把整周变成永久模板"的入口** —— 只剩训练页「每周都加（以后每周都用这份）」。
  这是这次拍板的直接后果，不是漏做。

细节与勘误（含 D3 那条"foodwake 没接进来"其实是判定不可用）在 `docs/REVIEW-BACKLOG-2026-09-22.md` 第二节 G 组。

---

## ✅ 上一轮快照（2026-09-23 · 「我的」页重构 C6–C12 做完，按**方案 J** 落地 · **已推**）

这一轮的 commit（前缀 `feat(profile)` / `feat(stats,profile)` / `feat(body)` / `fix(theme,ui)` / `docs`，
从 `2b34307` 起）：数据层 → 档案卡 + 四联 → 新路由「训练统计」 → 记录台账 + 存哪儿
→ 饼图同色系色阶 + `formatMonthDay` 收拢 → 身体数据页重排（J3）→ 三条文档收口。

> ⚠️ **这里刻意不写"几个 commit / 未推几个 / HEAD 是哪个"** —— 这三类数字在 2026-09-22 一天里过期过三次，
> 每一次都会让下一轮把已做完的事再找一遍。要现值就跑：
> `git rev-list --left-right --count origin/main...HEAD`（左边是落后、右边是未推）
> `git log --oneline origin/main..HEAD`

全库 635 条 JVM 单测绿；深浅两套截图 + 真机逐条对过 `dev.sh sql`，台账每个数都能在 SQL 里复现。

⚠️ **这一页做过两版设计，落地的是 J，不是用户发来的那份附件（方案 H）。**
`.scratch/ironhabit-profile/plan-J.html` 是上一轮按 HEAD `d46dd97` 对附件逐条重核后的后继，
它第 03 节就是「对附件《B×C 混合方案》的勘误」。我这一轮把那三条勘误**独立重验过，全部成立**：

1. 附件说「2 个新增路由」→ 实际只需 1 个（`Destinations.BODY_METRICS` 早就存在）；
2. 附件说环分母「体征 3 + 器械 2 = 5」→ 代码里只有 4 个判据，J 定的 **6 项**（性别/年龄/身高/体脂率/目标体重/器械）
   已写进 `ProfileField`，`UserProfileTest` 钉死了项数与顺序；
3. 附件的数据是**示例值**（它自己 footer 认了）。真库 `body_metrics` 当时只有 1 行（58.0 kg · 9/16）——
   **所以附件那张"多指标折线放首屏"的数据卡在这台机上根本画不出来**，这是 J 把它降级成
   「四联里一个体重格」的真正原因，不是审美取舍。
   另外附件的「128 次」真值是 12 条；「上次备份 9/18 · v5」这个数据在工程里**不存在**
   （没有任何地方存备份时间戳），所以那一块改成了「只存这台手机 · Room v9」。

**两处与提案不同、需要知道的**：
- 「首屏不滚动」没做到：为了不把「身体数据」和「食物库」变成隐藏入口各加了一行，
  「存哪儿」落在折叠线下方。`verticalScroll` 兜底按约定留着。
- 台账的「饮食」缺口只在**超过 50%** 时才用金色那句（2026-09-23 拍板），普通态报普通摘要。

**还剩的**：`versionCode` 仍是 20 / 2.0.9，这一轮全部是 debug 装机验证的。

### 同日续：一 组收尾 + A11/D19（习惯主题色）做掉

- 待办表 4 处过期状态清掉（A4 / A7 其实第 4 步就修完了只是没划、F2、落地顺序第 4 行）。
- 显示格式化收进 `ui/Format.kt`。**两条浮点规则刻意没合并** —— 上一轮我把它记成"重复"是错的：
  `toDisplayNumber` 给用户手填的档案值（`71.25` 必须原样念回去，否则用户以为没存住），
  `formatKg` 给算出来的量（总容量不四舍五入会念出 `1234.5678`）。合并任何一边都会改坏另一边。
- schema 版本提到 `domain/model/DatabaseInfo.SCHEMA_VERSION`，UI 不再直接引 Room 的数据库类。
- 首屏骨架做成**同构**两块（档案卡 / 四联各一块），替掉通用灰条。
- **A11 / D19**：习惯「主题色」以前存了、能选、但全 app 没有任何渲染点。现在 `HabitRow` 最左侧一条
  4dp 色条，自律页与今日页一次同时生效。做的时候查出**导入路径不校验 `color_hex`**
  （`HabitBackup.toEntity` 原样透传），而 UI 用 `android.graphics.Color.parseColor` 就地解析 ——
  一份手改过的备份 JSON 就能崩整页。解析收进 `Color.kt` 的 `habitColor()`：纯 Kotlin、
  形状不对回落到默认色，`HabitColorTest` 钉住。
  ⚠️ 第一版测试是**伪绿**的：非法样本拿默认色自己的 hex 去拼（`" #2196F3"`），
  于是把 `matchEntire` 误写成 `find` 也照样通过 —— 抠出来那段恰好等于兜底值。换样本后变异才被抓住。
  "测试看着严、其实分不出对错"是这个形状，值得以后照此自查。
- 单测 635 → **639**。


---

## ✅ 上一轮快照（2026-09-22 晚 · 合并待办的第 1~6 步 + A10/B1 补漏做完 · 每步各推一次）

`docs/REVIEW-BACKLOG-2026-09-22.md` 是唯一待办总表，**它的第三节表格现在就是进度本身**：
第 1 步 A1+A2（`2fd114a` + `bb3c95f`）、第 2 步 A5+A3+A8+B8（`dc95e5f`）、第 3 步 B 组七条（`940927f`）、
第 4 步 A7+A4+DST（`2efb42e`）、第 5 步 A9+C1/C5 与 C4 驳回（`e26e85d`）、第 6 步 C2+C3（`5ccdc9a`）、
第 21 刀 A10 习惯恢复 + B1 补漏。再往后是尾巴（D1–D4 / E1–E3 / F1–F2），多半要真机或外部条件。

三件需要知道的新事实：
1. **报告的 P1-5（色板深色对比）方向是反的** —— 实测六个 hex 对深底 `#111111` 全在 3:1 以上（唯紫 2.99 差一点），
   反倒是浅底上橙 2.16 / 绿 2.78 不合格。数字与治法在 `Color.kt`「五、习惯主题色板」。
2. **A9 修完还剩一条 A11/D19**：习惯「主题色」全 app 只有选色器自己读它，选完哪儿都不变 —— 那是产品决定，没替你定。
3. **C4 驳回**：热力 0 档压深成 `#C9C9C9` 会对白底 1.66:1，比 1 档的 1.56:1 还显眼，整张表最关键的第一跳会倒序。

629 个单测全绿（`--rerun-tasks`）；深浅两套目测各拍两张（`shots/b5-*.png`、`shots/b6-*.png`、`shots/b7-*.png`）；
测深色时把主题临时切到「深色」再切回，收工读回 DataStore 仍是 `theme_mode=LIGHT` 且**文件字节与切之前一致**（md5 相同）。

---

## ✅ 最新快照（2026-09-22 收工 · HEAD `03a040e` · **3 个本地 commit 未 push、未打 tag**）

下面那份 09-20 的快照描述的是**远端**状态（origin/main 已推到 `056c170`，版本 20 / 2.0.9）；
今天先推上去 34 个 commit，之后又落了第 13、14 刀与一份文档（`51be855` `a48cd44` `03a040e` 未推）。
**版本号一直没动**，所以"设备上是 2.0.9"和"代码是最新的"这两件事同时成立，别拿 versionCode 判断新旧。

**待办总表在 `docs/REVIEW-BACKLOG-2026-09-22.md`** —— 那份外部《IronHabit-审查报告》与本轮走查
合成一份，含逐条按当前 HEAD 重核后的勘误表（报告 15 条里 3 条要降级或前提不存在）。

这一轮做完的事、每条的真机读数、以及新查出的 D14/D15/D16/D17，全部记在
**`docs/FIX-PLAN-2026-09-21.md` 末尾「第 10~12 刀」与「第 13、14 刀」两节**；
缺陷的原始现象/证据在 `.scratch/defects.md`（未被 git 跟踪）。一句话版：

- 修了 5 刀：食物库停用不再是单向门（`6e7ece0`）、「已保留 N 条」只数界面上找得着的行（`d29c6f0`）、
  勾「每周相同」不再清空模板行的手改保护标记（`74279ac`）、导入不再改写 `created_at`（`51be855`）、
  预览页说清"是联网失败才落到本地规则"（`a48cd44`）。
- 验了 7 条此前没人走过的路径：预览页「全部采纳」、「每周相同」开关、断网回落本地规则、**导入备份往返**
  （含失败路径）、食物库新建/编辑/搜索、身体数据新增、打卡历史与动作详情→编辑往返。
- 609 个单测全绿（`--rerun-tasks`）；收工**先 `dev.sh sql` 重新拉库**再比对，与开工基线的唯一差异是两条习惯的 emoji（有意换的）。

### 两条会救命的操作事实（本轮实测，别再重新发现一次）

1. **`svc wifi disable` 自己就会把 MuMu 的 adb 桥打死**（不需要 hung dumpsys 参与）：`adb shell` 一律返回空，
   `connect`/`kill-server`/`reconnect` 全无效，而 `MuMuManager.exe info -v 2` 显示 VM 活得好好的。
   **救回来的通道是 `MuMuManager.exe sh -v 2 --cmd "svc wifi enable"`，它不经过 adb。**
   所以任何要关设备网络的测试，整个离线窗口都用 MuMuManager sh 驱动（`input tap` 也能跑），
   adb 只在网络恢复之后用来读界面。
2. **`.scratch/qa_db.py` 不拉库**，它只读 `shots/db/wal/ironhabit.db` 这个本地副本。
   本轮有五次"收工 md5 与基线一致"其实比的是同一份陈旧副本，结论无效；重新 `dev.sh sql` 后
   diff 出 2 格真实残留。**跑 `qa_db.py` 之前必须先 `dev.sh sql`**，
   而且"连续几次 md5 完全相同"本身就是危险信号。
   配套教训：还原脚本的列清单要取自 `pragma table_info`（我手写四列，漏了 `target_weight_kg`，于是"逐行 diff 为空"是假结论）。

### 仍未做 / 仍未验

**总表见 `docs/REVIEW-BACKLOG-2026-09-22.md`**（外部审查报告 + 本轮走查合成，按当前 HEAD 逐条重核过）。
上面那份 09-22 白天的表已过期：D15、D16 已修（`a48cd44` / `51be855`），第 9/11/12 行已验完，
只剩 #9-2 习惯 emoji 兼作勾选（要先出图）、动作库中文搜索（`adb` 打不进中文）、
#10-1 分享兜底（MuMu 上系统吞了 chooser 不抛异常，只能真机）、#10-2 保存面板（用户已定：先不做）。
新查出并待修的三条：**A1 习惯根本没有删除入口**（`HabitRow` 收了 `onDelete` 却没画按钮，全 app 删不掉习惯）、
**A3 身体数据删除是硬删且无确认**、**A5 备份导入在表已提交后才写设置/闹钟，失败时 UI 报"导入失败"而数据已被清**。

---

## ✅ 当前进度快照（2026-09-20 傍晚收工 · 已推到 origin/main · 版本 20 / 2.0.9）

**远端状态**：`70ddb09 → 3919aa8` 全部推上去了，本地与 `origin/main` **同步（0/0）**。
**没有打 tag**（打 tag 要单独授权，签名包走 CI 打 tag 那条路）。
`versionCode` 现在是 **20 / 2.0.9**，`:app:assembleRelease` 本机跑通（2m23s，产物 `app-release-unsigned.apk`）。

### 这一轮做的事：把"每习惯独立提醒"补上（`3919aa8`）

下面那份快照里写"需要产品决策所以没做"—— **那个判断是错的**。
`habits` 表本来就有 `reminder_enabled / reminder_hour / reminder_minute` 三列，
习惯表单也早就让用户逐个设时间，所以"每习惯一条提醒"**不是新功能，是兑现界面已经许下的承诺**，
没有可决策的余地。真正的 bug 在调度层：全应用共用一个 `HABIT` requestCode。

三个可复现的症状（都不是"缺功能"）：
1. 保存第二个习惯 → 把第一个的闹钟**静默顶掉**（"设了却不响"）；
2. 关掉第三个习惯的开关 → 走全局 `cancel(HABIT)`，**前两个的闹钟一起没了**；
3. 每次 `rescheduleAll`（开机 / 换时区 / 启动）→ 重排成"最早的那一条"，
   保存时明明生效过的时间，**重启后自己变了**。

⚠️ **关键技术点，改这里必须先看懂**：分槽只能分在 `requestCode` 上。
`PendingIntent` 判等只看 action / data / type / package / class，**不看 extras** ——
所以"在 Intent 里塞个 habitId"是**完全无效**的，两个习惯照样共用一个槽。
规则收在 `ReminderType.slotFor(type, habitId)`（纯函数，JVM 可测）。

连带面（少做一个就会留新 bug）：通知标题换成习惯名 + **通知 id 也按习惯分开**
（同 id 会互相顶掉，"三个习惯各响一次"在通知栏只剩最后一条）；
`rescheduleAll` 改成遍历**全部**习惯（含已停用）该排的排该撤的撤（自愈，不需要记上次排了哪些）；
`DeleteHabitUseCase` 补 `cancelHabit`（软删后行还在，而 `rescheduleAll` 不在删除时跑）；
广播里习惯已消失 → 不续排并撤槽；**升级前那条不带 habitId 的遗留闹钟**要让它最后响一次
然后撤掉 2 号槽，不续排 —— 否则通用提醒会和每习惯提醒长期并存、永远清不干净。

### 真机验证（MuMu，`dumpsys alarm` 读出来的，不是推断）

- 两个习惯**都设 20:00**（故意用同一个时刻）→ 系统里两条 `REMINDER_HABIT`，
  `origWhen` 完全相同、`PendingIntentRecord` 不同（`9eac3a3` / `ebcb0dd`）。
  2.0.8 下这里只会有**一条**。
- 关掉其中一个再保存 → 只剩一条，另一条活着，系统记了 `reason=alarm_cancelled`。
  2.0.8 下这里会是**零条**（全局 cancel 把另一个也带走）。
- 验完**已把用户设置复原**：两个习惯的 `reminder_enabled` 都回到 0，活跃习惯闹钟 0 条。

### 本轮两条新踩坑

- **`grep -c` 数闹钟会把"已经取消的"算进去**。`dumpsys alarm` 既列活跃闹钟，
  也留历史统计行 `[tag=…REMINDER_HABIT reason=alarm_cancelled …]`。
  我第一次数出"2 条"以为取消没生效，其实活跃只有 1 条。
  数活跃闹钟要用 `awk '/^    RTC_WAKEUP #/{getline l; if (l ~ /REMINDER_HABIT/) n++}'`
  这种"先看块头再看 tag"的方式，别直接 grep 计数。
- **弹层出现/消失会把下面的按钮顶走** —— `tap` 落点保护这一轮**救了两次**：
  打开「提醒」开关后多出一行「提醒时间 20:00」，我按上一屏记的坐标点「保存」，
  实际命中的是 `TextView「20:00」`，弹出了时间选择器。
  规则不变：**每次布局可能变化之后都要重新截图定位**，并且读 `tap` 打印的命中节点，
  别只看它有没有报错。

### 测试

新增 `ReminderSlotTest`（4）、`DeleteHabitUseCaseTest`（2）、`AddEditHabitViewModelTest` 补 3 条。
`slotFor` 做过**变异验证**：把它退回 `type.requestCode`（即 2.0.8 的行为）→ 2 条立刻红，改回即绿。
全量：**57 类 / 497 用例 / 0 失败**（上一份快照是 488）。

---

## ⏳ 上一份快照（2026-09-20 下午 · 综合报告 40 条 bug 收成 3 组根因 · **其中"每习惯提醒没做"那条已作废，见上面**）

用户的要求是：**"先修理会影响使用的 bug，仔细思考观察全局是什么引起的 bug，不要修一个 bug 又引出其他 bug"**，
以及 **"继续做都做完然后真机测试"**。所以这一轮不是按报告逐条修，而是先分组再动手。

| 组 | 一条根因 | 修了什么 | commit |
|---|---|---|---|
| **C** | **约定靠自觉** —— 同一件事在四处各有自己的写法 | 补录表单判据统一走 `InputLimits`；两处多行写入包 `withTransaction`；导入加大小闸 | `4971f2a` |
| **A** | **"今天"没有唯一来源** —— `todayEpochDay()` 被抄了 6 份，各自决定何时读 | `TodayClock`（可订阅 `epochDay` + 同步取值）；两处"固化值"随之消失 | `1742a62` |
| **B** | **"排了计划"被当成"练了"** —— 两处各写一遍 `observePlansForDay().isNotEmpty()` | `TrainingDayResolver` 三条规则；顺带修掉"看别的日子却按本周排课判" | `ac29f26` |
| 时区 | AlarmManager 存绝对毫秒，换时区后闹钟不跟 | 报告里的 Bug 2 的时区那半：manifest + 运行时双路注册 | `da18022` + `249c3ad` |

新增的唯一来源有两个，**以后要判断"今天/这周/星期几"只能走它们**，不要再自己 `clock.now()`：
- `domain/util/TodayClock.kt` —— 可订阅的 `epochDay`，30 秒一跳，跨午夜自己翻。
- `domain/usecase/TrainingDayResolver.kt` —— "这天算不算练过"的唯一谓词
  （练过算；没练但那天是**今天或未来**且排了课仍算；**过去**且没练才算休息日）。

### 真机验证结果（MuMu / Android 12，逐条对着屏幕读过，不是"应该没问题"）

1. **根因 A 通过**。App **不重启**，只 `adb root` + `date 092100052026.30` 把时钟推到 9/21 00:05：
   周标题从 `9/14 - 9/20` 变成 **`9/21 - 9/27`**，日期条选中 **`一 9/21*`**，
   今日完成 `0 / 6`，待完成「深蹲 0 / 3 组」，本周复盘磁贴按新周正确消失。
   —— 修之前这条路径必然失败（进程里的 `today` 还停在 9/20）。
2. **根因 B 通过**。同一份档案、同一个体重，只有 `isTrainingDay` 不同：
   - `9/15`（**过去** + 排了 1 行课 + **0 次打卡**）→ 今日饮食 **`0 / 2199 kcal`** = 休息日系数；
   - `9/16`（**5 次打卡**）→ **`500 / 2479 kcal`** = 训练日系数。
   旧判据会把 9/15 也算成训练日（2479）。数据库侧独立核过：`check_ins` 9/15 为 0 行、9/16 为 5 行。
   探针脚本留在 `.scratch/probe-diet-days.py`，重跑即可复现这张表。
3. **时区修复"先证明它不通、再证明它通"**。logcat 同时给出两条：
   `W BroadcastQueue: Background execution not allowed: ... to com.ironhabit.app/.data.notification.BootReceiver`
   —— manifest 那份被系统**拒投递**；
   `I IronHabitApp: 时钟/时区变更（…）→ 重排提醒` —— 运行时注册那份**收到了**。
   ⚠️ **没验到的部分要说清楚**：MuMu 上 `time_zone` 改来改去实际偏移没变，
   所以"换了真时区后闹钟时刻被按新区重算"这一条**只有代码路径 + 单测，没有设备证据**。
4. **一条没验到**：`9/19`（1 次打卡 + 0 行课 → 新判据判训练日、旧判据判休息日）是最理想的第二个样本，
   但日期条横向滑不动（`swipe` 被父级纵向列表吃掉），没在设备上读到。
   该分支由单测 `actualCheckIn_makesItTrainingDay_evenWithoutAnyPlan` 钉住。

### 本轮明确没做（要产品决策，不是漏了）

- ~~**每个习惯独立提醒时段**~~ → **09-20 傍晚已做完**（见最上面那份快照）。
  当时判断"要产品决策"是**判断错了**：习惯表单本来就能逐个设提醒时间，
  所以"每习惯一条"不是新功能而是**兑现界面已经许下的承诺**，没有可决策的空间。
- 报告里其余条目分三类，**都还没动**：① 需要产品决策才能做的（已单独列在上面）；
  ② 与用户既有约束直接冲突的（例如"每组单独记重量/次数/RPE"，用户明确否过）；
  ③ 只在"发布到商店"才需要的前置项（本地化、无障碍、`allowBackup`、`targetSdk`、lint `abortOnError`）。
  逐条清单在下面的"仍然开着的"那节，以及本会话的开放决策表里。

### 本会话新增踩坑（下一个人别再踩）

- **`dev.sh install` 会把"构建失败"洗成"安装成功"**。它装的是 `build/outputs` 里**当时存在的那个 APK**：
  `assembleDebug` FAILED 之后 `install` 照样报 Success，于是我"验证"的是上一版二进制。
  **装之前必须先确认 `BUILD SUCCESSFUL`**，别用 `install` 的返回值当构建是否通过的证据。
- **manifest 里加了 `<action>` 不等于收得到**。Android 8+ 对后台应用的隐式广播直接不投递，
  而配置层面看起来完全正确、不报错。唯一的证据是 logcat 里那句 `Background execution not allowed`。
  以后碰广播：**先证明投递到了，再谈逻辑对不对**。
- **探针脚本"跑通了"不等于它的输出被我看过了**。本会话我引用过一个从未打开过的结果文件里的数字
  （"库里今天有 3 行打卡 / 14 组"），真实数据是 0 行 / 24 组，于是凭空造出一个不存在的 bug。
  规则：**没 Read 过的输出一个字都不许引用**。

---

## ⏳ 上一份快照（2026-09-19 收工 · 一类待办清账完 · 再下面 09:50 那份只当背景资料看）

用户批准的"一类"6 项处置如下：

| 项 | 结论 | commit |
|---|---|---|
| 1 同步 `spec.md` 过期决策 | 已改。`.scratch/` 在 gitignore 里，所以**没有对应提交**，别去历史里找 | — |
| 2 日期栏钉顶 | 页面外层 `Column`：日期栏 + `HorizontalDivider` 固定，磁贴放进内层 `weight(1f)` 的滚动列 | `60864e3` |
| 3 本周「12 / **35**」的计划总组数 | **没做**，按我的建议缓办（要新增一路按周聚合 `WeekPlan.targetSets` 的查询，还要过 `applyData` 那道坎）。仍开着 | — |
| 4 动效 | 用户拍板"按压动效选择做，不需要全部做" → **只做磁贴按压缩放**；其余 6 项已从 spec §5 **正式划掉**（不是推迟） | `d98f375` |
| 5 习惯目标值缺单位 | 目标值非空而单位为空 → 拒绝保存并提示，**不写库**；单位框 label 一起改掉（原文案写"可选"，与新规则矛盾） | `6e64524` |
| 6 版本号 | `17 / 2.0.6`，`assembleRelease` 跑过 | 本笔 |

**按压缩放是量出来的，不是"看着像"**：静息磁贴宽 983px、按住时 959px（两侧各内收 12px）= 2.44%，
对齐 spec §5 的 `0.975`。只有带 `onClick` 的格子有按压 —— 不能点的格子给一个缩放等于谎报"这格能按"。

**弹窗进出动画仍未验证**。用的是 M3 `ModalBottomSheet` 默认转场、与 `CheckInSheet` 同一写法；
我两次帧分析都没测准（底部导航条是深色，把整帧指标带偏），已放弃这条验证路径。

**习惯校验的现网前提**：先查过设备库，`target_value` 非空而 `target_unit` 为空的行**有 0 条**，
所以这条新校验不会把已有习惯锁成"想改个 emoji 就得先补单位"。以后若有别的路径（AI / 导入）
写出这种行，编辑页会拦下来 —— 那时该补的是那些路径，不是放宽校验。

**release 构建的边界**：`:app:assembleRelease` **BUILD SUCCESSFUL**（2m16s，R8 + `isShrinkResources` 全开），
产物 `app/build/outputs/apk/release/app-release-unsigned.apk` 2.28 MB。但 `keystore.properties` 不存在
→ `signingConfig` 为 `null` → **产物未签名、装不上机**。这趟只证明 R8 没弄坏东西，不证明可分发。

### ⚠️ ① 查"有没有 push 过"查出来的分叉（2026-09-19 收工后，已核实）

**本树确实一笔都没 push 过**：`git show-ref` 里原本没有任何 `refs/remotes/origin/*`
（push 成功必然写远端跟踪 ref），本地 `main` 也没有 upstream。

**但 `origin` 不是停在原地**。`git ls-remote` + `git fetch origin main` 实测：

| | 提交 | 内容 |
|---|---|---|
| 分叉点 | `8903ec0` | 就是本地标签 **v2.0.5** 指向的那笔 |
| 只在远端 | `d98964b`（2026-09-18 18:27） | **fix(p0): 修 4 个 P0** —— 老备份清空饮食记录 / 加密存储崩溃 / 模板手改被绕过 / 保存连点。21 文件 +718/−75 |
| 只在远端 | `3dd3597`（2026-09-18 21:45） | **chore: bump versionCode 17 / versionName 2.0.6**，并打了 `v2.0.6` 标签 |
| 只在本地 | 28 笔 | `db3eee6`（09-18 22:39）起，到今天页改版全部工作 |

`git rev-list --left-right --count HEAD...origin/main` = **28 / 2**，两边互不为祖先 → 真分叉。

**两个直接后果：**

1. **本树缺那 4 个 P0 修复**，包括一个会清空饮食记录的数据丢失 bug。今天所有走查都是在**没有 P0 修复的代码**上做的。
2. **`17 / 2.0.6` 这个号已经被远端占用并打了标签**（`v2.0.6` → `3dd3597`）。本树 `4e1f87c` 又 bump 了一次 17/2.0.6，
   同号不同内容。合并后应改成 **18 / 2.0.7**。

**合并代价已量过**：两边都改的文件只有 5 个（`HANDOFF.md`、`app/build.gradle.kts`、`strings.xml`、
`AddEditHabitViewModel.kt`、`AddEditHabitViewModelTest.kt`）。真正冲突的只有习惯那一处：
远端在 `val targetUnit = ...` 之后插了 `isSaving` 防抖，本树在同一行之后插了"目标值必须有单位"的校验 ——
**两段都要留，顺序是先校验后防抖**，属于手工可解的小冲突。`strings.xml` 两边加在不同区段，可自动合。

**本树已做的网络动作**：只 `ls-remote` 和 `fetch`（对远端只读），本地多出一个 `refs/remotes/origin/main`。
**当时没有 push、没有 merge，`main` 仍是 `4e1f87c`** —— 下面那次合并是用户拍板"方案 A"之后才做的。

#### 已按方案 A 合并（同一晚，用户拍板"A"）

| 项 | 结果 |
|---|---|
| 合并提交 | `42bc526`（parent = `c91f521` + `origin/main 3dd3597`）；版本让位另笔 `7e69227` |
| 冲突 | **只有 `HANDOFF.md`**（§0/§1/§9/§10 四段，按"远端更新"取再补回本树事实）。代码 3 个重叠文件全自动合上 |
| 合出来的顺序 | 正好对：`AddEditHabitViewModel` 里"目标值必须配单位"在前、P0-4 的 `isSaving` 防抖在后；习惯测试两边 10 条都在 |
| 两边修复共存（逐条 grep 核过） | 本树 `IconButton(onClick = onCreateHabit)` 1 处；远端 `isSaving` 在 habit/plan/exercise 三个 Screen 各 1 处；`carriesMeals` 4 处 |
| 全量单测 | **48 类 / 447 用例 / 0 失败**（`--rerun-tasks`） |
| 构建 | `assembleDebug` + `assembleRelease` 同一趟 BUILD SUCCESSFUL（1m01s），release 仍是未签名包 |
| 装机 | 已装合并包，崩溃缓冲为空；今日页（磁贴 + 钉顶日期栏 + 热力条）与「我的」页渲染正常 |

**签名的事澄清**：`§8` 约定"本机不打 tag"——CI 的 `android-release.yml` 在 tag 时用 4 个 secret 出签名 APK。
所以本机 `assembleRelease` 出未签名包**不是缺陷**，是分工；要出包得推 tag，那是主理人的活。

**已推 `origin/main`（09-19，用户明确授权"推"）**：`3dd3597..7bc04ef`，**fast-forward、没 force、没打 tag**
（`git ls-remote --tags` 复核过，远端仍只有 `v2.0.5` / `v2.0.6` → CI 没被触发，没有新 Release）。
本地 `main` 现已 track `origin/main`，以后 `git status` 会直接显示领先/落后。
**要出 2.0.7 的签名包，还差一步"打 tag `v2.0.7` 并推 tag"** —— 那一步本机不做，需你或主理人来。

#### ③ `D:\ih-check` 已删除（09-19，删前逐文件核过）

不是"更早的无 git 镜像"，是 **09-18 那次被中断的工作树**：它自己的 `HANDOFF.md` 顶部写着
「阶段 1 · WIP，被中断 · 这一版代码从来没有编译过、也没有跑过任何一次测试」。

**diff 的坑**：跟 `8903ec0` 直接比报 72 个文件不同，其中 **63 个只是 CRLF 行尾噪声**。
加 `--strip-trailing-cr` 后真差异只剩 **12 个文件**（9 源文件 + 1 测试 + `HANDOFF.md` + `.gitignore`）。
**下次跨目录比源码，一律带 `--strip-trailing-cr`，否则会把行尾差异当成内容差异。**

独有内容与价值判定：

| 东西 | 有没有价值 |
|---|---|
| `CheckInSetMaskTest.kt` 的另一版 7 条用例 | **零** —— 用例名与活动树逐字相同，活动树是 7 条 + 今天新增 3 条 `coversTargetSets` 的超集 |
| 9 个源文件的 WIP 改法（`CheckIn`/`DetailedCheckInUseCase`/`BackfillCheckInUseCase`/`ExerciseCheckCard`/`PlanDateStrip`/`AppRoot`/`CheckInSheet`/`TodayScreen`/`Color`） | **零** —— 未编译未测试，同一批文件今天已重做并真机验过 |
| 那份从未入库的 WIP 快照文本（`git log --all -S'WIP，被中断'` 零命中） | 低 —— 曾留档，随后一并删除（结论已抄进本节） |
| `.scratch` 里早期版 `spec.md` / `prototype.html` | 低，活动树是更新版 —— 曾留档，随后一并删除 |
| 4 张原型截图 `shot-comp/comp2/drill/fprime.png` | **零** —— 与活动树**字节相同**（`cmp` 核过） |

曾留档 116KB（`ih-check-wip-vs-v205.patch` 12 文件差异 52KB / 那份 WIP 快照 HANDOFF 24KB / 早期 spec+prototype 40KB），
**判完价值后也删了**：补丁只有 +324/−135 行，改的正是今天重做并推到远端的那批文件，
留一份"从未编译过的旧写法"diff 没有任何后续用途。要找回那段 WIP 的思路，只能从本节这段记录里读结论。
**目录本身已 `rm -rf`，释放 90MB**（其中 ~87MB 是 `build/` + `.gradle/` + `.kotlin/` 缓存）。

#### 3 已做：本周总组数补上分母（`af44c4b`）

**不需要新写按周 SQL 聚合** —— `GetTodayOverviewUseCase` 早就把
`observeEffectivePlanForWeek(weekStart)` combine 进来了，只是把生效行丢掉、只留下星期。
现在让这条流把行交出来，`sumOf { targetSets }` 就是分母。
**为什么坚持走 resolver 而不是 `SUM(week_plans)`**：模板回落、逐天覆盖、软删行三条规则
只在 `WeekPlanWeekResolver` 有一份，绕开它磁贴的分母就会和清单对不上。

顺手统一了一处口径不一致：分母会剔掉「动作已不在活跃动作表里」的行（清单压根不显示它们，
留在分母里就是虚高、永远练不满），`plannedWeekdays` 现在和分母共用同一份 `usable` 列表。

- 真机读数 **「总组数 24 / 52」**，并用一条照 resolver 规则写的 SQL 独立复算过：
  周一3 周二3 周三20 周四3 周五20 周六3 周日休 = **52**，完成 **24**。
- ⚠️ **已知语义，别当 bug 修**：分子是本周 `check_ins.completed_sets` 之和，
  **含没进计划的临时打卡**（9/16 那天 5 行 `plan_id` 全 NULL、共 7 组），
  所以练得比计划多时会看到 `60 / 52` 这种超 100% 的读数 —— 那是事实，不做截断。
- 字段链：`TodayOverview.plannedSetsThisWeek` → `toUiState()` → **`applyData`** → `TodayUiState`。
  `applyData` 逐字段搬运这个坑**第三次**踩到（前两次是 `weeklyReview`、`weekHeatmap`），
  已补一条 `plannedSetsThisWeekReachesUiState` 回归。分母为 `0`（这周没排课）时磁贴**不挂分母**。

#### A1 已发版：tag `v2.0.7` → CI 签名包（09-19 20:4x）

| 项 | 实测 |
|---|---|
| tag | 带注释标签 `v2.0.7` → `dd54325`（与 `v2.0.5` 同形态；`v2.0.6` 当年是轻量标签） |
| CI | `Android Release (Signed APK)` run **completed / success**；`Android CI` 在 `dd54325` 也已 success |
| Release | https://github.com/science-ken/IronHabit/releases/tag/v2.0.7 （非 draft、非 prerelease） |
| 产物 | `app-release.apk` **2,295,311 字节**，已下载并用 `apksigner verify` 核过：v2 方案通过，签名者 `CN=IronHabit`（RSA 2048）；`aapt2 dump badging` 回读 **versionCode 18 / versionName 2.0.7**，确认是这一笔 |

### ⚠️ 想装这个 release 包，先导出备份

release 用 **`CN=IronHabit`**（CI 的 keystore），本机 debug 包用 **`CN=Android Debug`** ——
**两个签名者，不能覆盖安装**。要换 release 包必须先卸载 debug 包，
而**卸载会连带删掉 `/data/data/com.ironhabit.app/databases/` 里的真实训练记录**。
所以顺序只能是：**我的 → 数据备份 → 导出** → 卸载 → 装 release → 导入。
（P0-1 修的就是"导入老备份清空饮食记录"，v4 备份才安全。）

#### B1 / B2 两处习惯文案（`9bc1224`，用户看过网页预览后拍的板）

- **B1 选了 C**（不是我推荐的 B）：单位框 label = 「目标单位（如 杯/分钟/步）」，与左边「目标值」两行齐平。
  ⚠️ **label 里不再出现"必填"二字，但校验还在** —— `AddEditHabitViewModel.onSave` 依旧会拦
  「有目标值没单位」，由报错文案负责说明。别因为 label 变短就以为那条校验被撤了，也别把它加回 label。
- **B2 选了 B**：习惯行值与单位之间加「 · 」。单位是自由文本，填成数字时直接拼会读成一个数（`4`+`"2"`→「42」）。
  无单位时不加，避免孤零零的分隔点。
- 两处都重新装机看过：「4 · 2」「8 · 40」，新增习惯页两框等高。
- 预览页留在 `.scratch/ironhabit-today-bento/preview-habit-words.html`（hash 可选状态，如 `#list-B-dark`）。

#### C 三件（09-19 深夜，用户说"按推荐的做"）

1. **勾选身份的接线测试** —— 新文件 `CheckInMaskWiringTest`（4 条）。
   `mergedMask` 早就有单测，缺的是**接线**：某个用例改回 `maskFromCount(sets)` 或把
   `previousMask` 传成 `0`，函数全对、测试全绿，而"勾过第 1、3 组"会在一次补录后变成"第 1、2 组"。
   用例数值是挑过的（`previous=0b101` + 提交 2 组：正确 `0b101`，断线 `0b011`）。
   **做过变异验证**：把 `DetailedCheckInUseCase` 临时改成 `maskFromCount(sets)` → 2 条立刻红，
   改回即绿。所以这几条是真会咬人的，不是摆设。
2. **`dev.sh tap` 加了落点保护**（`scripts/tap-target.py` + `tap` 分支重写）。一次点击前先做三件事：
   - 拿 `wm size` 比对坐标，**越界直接拒绝**（今天照着被缩放的截图估出 `y=2130`，屏幕只有 1920，
     那一指是**静默空操作**，我却以为点过了）；
   - 拍一张 `pre-tap-<时刻>.png`；
   - dump 层级后打印**最内层命中节点**，并给两种警告：链路里含"打卡/完成"字样，
     或命中节点**可点且占屏 >5%**（动作卡 ≈9.5%、卡上的按钮 ≈0.5%，误触的正是前者）。
   顺带修了 `pull_ui`：**先删设备上的旧 ui.xml 再 dump** —— dump 失败时 `cat` 会读到上一轮的层级，
   本会话被这个假象骗过一次（明明在今日页，却报出"数据备份"页）。
   ⚠️ **只离线验过**（fixture + 语法 + 两种警告各自触发）。写它的时候模拟器已经关了，
   真机第一次用之前当作"未验证"看待。
3. **spec §5 结案**：7 项动效里 1 项已做、6 项逐条划掉并写明理由（"下钻/返回"两条是因为形态
   从路由改成同屏弹窗后**场景本身没了**），另加"什么条件下才重开"。
   以后看到 §5 不要再当待办捡起来。

### 本会话三条踩坑记录（下一个人别再踩）

- **结构性改动不要交给"按花括号猜边界"的脚本**。日期栏移顶时我用脚本找块的右括号，
  多吞了 69 行，把整个 `else ->` 分支（含 `TodayBento` 和三个常驻入口）删掉了。
  靠 `grep -c 'TodayBento('` 归零才发现，从 `5dbf310` 检出恢复，改成手写整块 Edit。
- **误触写库第三次**。`adb input tap` 打在训练弹窗的动作卡片上就是打卡 ——
  整张卡是 `combinedClickable(onClick = { if (completed) onUndo() else onQuickCheckIn() })`。
  本次 总组数 24→27，靠「撤销」复原。**点之前必须重新截图定位**，滚动位置会跨页面保留。
- **跨目录比源码必须带 `--strip-trailing-cr`**。删 `D:\ih-check` 前的 diff 第一版报 72 个文件不同，
  真差异只有 12 个 —— 其余 60 个全是 CRLF/LF 噪声。不带这个参数就会把"两份树不一样"夸大 6 倍，
  足以让人放弃清理、或误判"那边有独有工作"。（详见上面 ③ 那节）

---

## ⏳ 上一份快照（2026-09-19 09:50 · 今日页改版 + 配色改版都已完成 · **上面 00:40 那份的形态与配色结论已作废**）

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

### 阶段 5（`217059b`）之后

- **删掉「连续 N 天 🔥」庆祝 Snackbar**（用户不要）。`msg_streak_up` 字符串、契约测试里的
  registry 登记与 `channelStringArgs` 锚点、`applyData` 的 args 特判一并清掉。
  **「已断档，重新开始」保留** —— 那是警告不是庆祝，删了没人知道连续记录断了。
- **修回一处我自己写塌的东西**：阶段 4 把 `surfaceContainer` 与 `surfaceContainerHigh`
  写成了同一档 `#F0F0F0`，容器阶梯中间塌一级（正是 spec §4 刚修过的同类问题）。
  补 `Neutral96 #F5F5F5`，恢复 `#FFF → #FAFAFA → #F5F5F5 → #F0F0F0 → #DCDCDC` 单调。

### 已知缺陷：`AiCoachScreen.kt` 注释是乱码（既有，**刻意没改**）

`ui/screens/ai/AiCoachScreen.kt` 有 **59 行注释**是 UTF-8 字节被当 GBK 读出来的产物
（`/** 鏁存暟閲嶉噺... */` 这种）。逐行分类过：**全部在注释里，字符串字面量 0 处、代码 0 处**，
所以界面文案和运行行为都不受影响，纯粹是文档不可读。

- 基线 `8903ec0` 就已经是坏的，不是改版改坏的；本轮写过的 23 个文件编码全部干净。
- 该文件在仓库里只有 2 个提交，**没有任何一个干净版本**，`git show` 捞不回来。
- 损坏是**有损**的（原文里已出现 `?`），GBK 反推也还原不了。
- **没有重写它**：凭代码反推注释等于替原作者编造结论，而这个项目的交接全靠注释当真。
  要修的话应当由知道原意的人补，或者干脆删掉这些注释行。
- 另外 7 个曾被怀疑的文件实测 0 命中，是我第一版检测脚本字符表太松导致的误报。

### 阶段 6（`6020d8e`）真机走查：修掉 2 个，验证过 11 条

**修掉的两个都是"入口不可达"，不是崩溃，所以单测和编译都发现不了：**

1. `habits` 表为空时习惯格整个不渲染，而三个常驻入口里没有 `onCreateHabit` ——
   磁贴化之前页面上那条「还没有习惯 → 去创建」彻底断了。改成习惯格常驻。
   （饮食格不用改：常驻入口里已经有「生成饮食计划」。）
2. 弹窗里三个**跨路由**的空态按钮（`onCreatePlan` / `onCreateHabit` /
   `WeekPlanEmptyCard.onCreateManually`）漏了先收弹窗，而同类的
   `HabitRow.onEdit`、`MealBlock.onEdit`、`ExerciseCheckCard` 那几个都收了 —— 同类操作两种行为。
   `onCreateByAi` **故意不收**：它不切路由，弹窗开着用户才能当场看到卡片变化。

**已在真机上走通、下一个人不必重跑的路径：**

| 路径 | 结果 |
|---|---|
| 今日完成格 / 待完成格 → 训练清单弹窗 | ✓ |
| 弹窗内勾一组 → 磁贴同步（今日完成、分段条、总组数、连续） | ✓ |
| 弹窗内「撤销」→ 复原 | ✓ |
| 饮食格 → 饮食弹窗（DietTotalsBar + 四餐） | ✓ |
| 弹窗内「编辑」→ 收清单、弹 `MealEditSheet`，无两层叠加 | ✓ |
| 弹窗内「补录详情」→ 收清单、弹 `CheckInSheet`，无遮罩残留 | ✓ |
| 习惯格（0 习惯）→ 弹窗空态 →「去创建」→ 干净落到自律 tab | ✓ |
| 翻到下一周（未来日）→ 金色只读提示 + 两个生成按钮置灰 + 去创建仍可用 | ✓ |
| 配色扫过 今日 / 自律 / AI 教练 / 训练 四页 | ✓ 无白字压白底 |
| 崩溃缓冲 | 全程为空 |

**走查方法上的教训（第三次踩）：滚动位置会跨页面保留**，回到今日页时上次 swipe 留下的
偏移还在，按旧截图的坐标点就会点到别的格子。点之前必须先看一眼当前帧。

### 阶段 A（`d1474d0`）：三件待决已定案

| 原待决 | 结论 |
|---|---|
| 「这项算不算完成」语义不一致 | **统一到"勾满要求组数"**（与 `prototype.html` 一致）。判据收在 `CheckIn.coversTargetSets(targetSets)` |
| `ProgressRing` 零引用 | **已删除**（114 行死代码）。契约测试里指向它的三条证据注释改指 `TodayBento` |
| 顶栏要不要区分度 | **维持 `surface` 白底，不改**。磁贴本身已承担视觉重量，再压一条灰带等于把用户明确说过"土"的平色应用条请回来 |

**新的完成口径（会影响别的页面读数，改之前先读这段）：**

- `TodayPlanItem.isCompleted` = 有打卡记录 **且** 勾满了 `plan.targetSets`。
- `targetSets <= 0` 的动作**有记录即完成** —— 这种动作没有勾选框，一键打卡得到的 mask 是 `0`，
  要求"勾满"会让它永远完成不了。
- 判定用 `completedSets >= targetSets`，不是逐位对齐：计划组数被改小后，
  旧打卡多勾的位不该反判成未完成。
- **连续天数不受影响**：`StreakCalculator` 走 `observeActiveDays`（这天有没有打卡行），
  与 `isCompleted` 无关。
- 顺带修掉的副作用：以前只勾 1/3 组，卡片就立刻变灰打勾、点卡片从「一键打卡」翻成「撤销」、
  今日完成数提前跳满。
- 全部勾完时「待完成」格换成「今日训练 / 已完成 ✓」，不再凭空消失。

### 阶段 B（`b6f9ef9` + `a4b1fea`）：深色回归已修、乱码注释已删

**深色模式现在是对的，且真机看过。** 两条新规则要守住：

- **热力色阶分主题两张表**（`HeatmapLevelsLight` / `HeatmapLevelsDark`）。
  深色下方向与浅色相反：**越练越亮**。写死一张表会让明暗关系倒过来
  （实测同一套颜色在深底上是 13.77 → 3.05，无数据格最扎眼、练满的最暗）。
- **判断深浅一律走 `LocalIsDarkTheme`，不要用 `isSystemInDarkTheme()`**。
  本 app 允许在设置里显式选浅色/深色，那时系统值和实际渲染的主题会不一致。
- `res/values-night/` 已建：深色 splash 回 `#111111`、父主题换 Material 暗、
  `windowLightStatusBar=false`。桌面图标底色**不随主题变**（保持白底深青哑铃）。

**乱码注释**：`AiCoachScreen.kt` 那 59 行已删除（`a4b1fea`），1049 → 990 行。
只碰"整行只是注释"的行，删完反向核过：非注释行 0 条。**没有重写**，
凭代码反推等于替原作者编造结论；要补原意由用户口述。

### 已修：「建完第一个习惯后就再也无法新增」（`1a836d9`）

用户报"无法创建习惯"。**不是本轮改版造成的**，是既有缺陷被今天建了第一个习惯后暴露：

`DisciplineScreen` 的 `onCreateHabit` 全文件只有一处调用，且包在
`if (uiState.habits.isEmpty())` 的 `EmptyState` 里 —— 0 个习惯时能建，
一旦列表非空，整页再没有任何添加入口。而空态文案写着"点右上角「+」添加第一个吧"，
那个 + **从来没被画出来过**。

修法：「习惯」标题右侧加常驻 `IconButton(Icons.Filled.Add)`，沿用训练页 `PlanSection`
同一套写法。顶栏是 `AppRoot` 全 app 共享的，所以入口放区块标题旁、不动共享顶栏。

### 本次真机新发现：裸数字显示（**已修 `6e64524`**，当时记为"待表单校验"）

自律页习惯行显示「喝水 / 连续 1 天 / **42**」—— 那个 42 是 `habit.targetValue`，
因为 `targetUnit` 留空，`HabitRow` 按"值+单位"渲染就只剩一个光数字，看不出是什么单位。
设计意图是「8杯」「30分钟」这种。**渲染没错**（表单里目标值与单位都标了"可选"），
缺的是"填了目标值就必须填单位"的**表单校验**，要改在习惯新增/编辑页，不在 `HabitRow`。

### 仍然开着的（等用户定）

1. **「本周 12 / 35」里的 35** —— 曾长期未实现（只显示已完成的组数）。
   → **已做（`af44c4b`）**：不新增 SQL 聚合，改用 `GetTodayOverviewUseCase` 里已 combine 进来的
   `observeEffectivePlanForWeek` 生效行求和，真机读数「24 / 52」。详见文首快照。
2. **动效全部推迟**（spec §5 的 7 项）。→ 已做磁贴按压缩放（`d98f375`），**其余 6 项已划掉**，
   逐条理由与"什么条件下重开"写在 spec §5。
3. **版本号仍是 16 / 2.0.5**，改版后没 bump、没跑 release 构建。→ 先 bump 到 17 / 2.0.6，
   合并后发现远端已用 17/2.0.6 发过版并打标签，**本树最终落在 18 / 2.0.7**。
4. 习惯目标值缺单位时的裸数字显示（见上）。→ 已修（`6e64524`）。

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

1. **源码在哪**：**带 git 的活动树 = `D:\fitness-app-v204 1`（本文件所在处）**，今日页改版在这里。
   另有两份**别当最新**的副本：`D:\Workbuddy data\2026-09-14-09-31-06\fitness-app-v204`
   是 09-18 那轮的免 git 镜像（**不含 09-19 今日页改版**）。
   第三份 `D:\ih-check` **已于 09-19 删除**（90MB）—— 它是 09-18 那次"阶段 1 WIP、从未编译从未测试"
   的中断工作树；独有内容当时留过档，判完价值后连归档一起删了（见 §0 上方"③"那节）。
2. **跑一次全量测试**确认环境没问题（命令见 §2），**期望以 §9 的实测数为准**。
3. **读 §3（红线）和 §4（数据模型）**，再动代码。这两节是"不知道就会踩雷"的部分。
4. **⚠️ 这棵树不止一个会话在用**（2026-09-19/20 实测：另一个会话同期提交了
   `a33d72c` 肌群词表 + Room v7、`63daab1`、`f3265ad`，并且**正在跑 Gradle**）。四条硬规矩：
   - **动手前先 `git status --short`**。看到不是自己改的文件，就**绝对不要 `git add -A`**
     ——那会把别人没写完的东西（含未完成的数据库迁移）吞进你的 commit。只按文件名逐个 `git add`。
   - **构建一律加 `--no-daemon`**。共享守护进程会被对方的 `gradle --stop` 一起带走，
     今天三次构建莫名死于 `daemon has been stopped: stop command received`；
     强停还会把 `app/build` 的增量状态弄坏（`Cannot access output property 'classesOutputDir'`）
     并留下 `R.jar` 文件锁（`Device or resource busy`，此时只能等对方跑完，别去杀别人的 java）。
   - **不要用 `timeout` 包构建**：超时会把守护进程连带打死，症状和上面一样、还更难查。
   - **`app/build/test-results/*.xml` 会是陈旧文件**：上一次失败/挂死的运行可能留下旧结果。
     判断"到底跑没跑"要看 XML 里有没有**这一版新增的用例名**，或直接先删掉那个 XML。
   - 想知道是谁在占：`powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Select ProcessId,CreationDate,CommandLine"`
     ——启动时间是新的，就是对方正在跑。

产出的东西（APK / 报告）放哪、怎么交付，见 §8。

---

## 1. 这个项目是什么、现在到哪一步

**IronHabit**：一个 Android 健身打卡 App（Kotlin + Compose M3 + Hilt + Room + DataStore，单模块 `:app`）。
单人本地应用，无账号、无后端；"AI 教练"是可选的联网增强，**断网 / 没填 Key 时一律回落本地确定性规则，并如实标注"这不是 AI"**。

| 项 | 值 |
|---|---|
| 远端仓库 | `git@github.com:science-ken/IronHabit.git`，分支 `main` |
| 远端 HEAD | `origin/main` = `3dd3597`（P0 修复 + 17/2.0.6 发版，tag `v2.0.6`）。**本树另有 09-19 的 28 笔未推**，见文首分叉记录 |
| 版本 | `versionCode = 18` / `versionName = "2.0.7"`（17 / 2.0.6 已被远端发版占用，不再复用） |
| 当前 APK | GitHub Release `v2.0.6` 的 `app-release.apk`（**签名由 CI 在打 tag 时产出**，本机 `assembleRelease` 只能出未签名包） |
| 测试 | 见 §9 的实测数（合并后以本机跑出来的为准，不要抄历史数字） |
| 模拟器 | MuMu 实例「软件测试」，`adb 127.0.0.1:16448`，Android 12，1080×1920 |

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

- 全量：**2026-09-18 在 tag `v2.0.5` 上是 47 类 / 435 用例 / 0 失败**（历史交接文里写的 43/395 是 v2.0.4 时代的数字）。
- **合并后实测（2026-09-19，`--rerun-tasks`）：48 类 / 447 用例 / 0 失败** —— 两边的新增用例都在里面
  （远端那 13 条 P0 测试 + 本树的 `coversTargetSets` 3 条、`weeklyReviewReachesUiState` 1 条、
  目标值必须配单位 1 条，以及本树更早补的日期游标回归）。
  **别抄任何一个旧数字**，历史值只用于判断"少跑了/被缓存"。
- `app/src/test/java/com/ironhabit/app/verify/`：**41 条对抗性用例**（另一位 agent 写的，已收进主仓）。
  它们专门打边界：极端档案（3840 组组合）、脏数据、幂等、手改行保护、JSON 字段集合冻结、无密钥泄漏等。
  **改动 P1/P2 相关代码后必须跑它们。**
- 真机证据（截图 + UI dump）：`D:\dsh data\shots\`（`p1-basis-local-rules.png`、`p2-week-package.png`、
  `p3-empty-week-card.png`、`p3-repeat-weekly-switch.png`、`p3-v203-today.png`）。
- 设计文档：仓库 `docs/ai-coach-local.md`（§12 是 P1/P2 的落地记录）、`docs/ARCHITECTURE.md`、`docs/schema-v3-meals.md`。
- 复核报告全文：`D:\Workbuddy data\2026-09-14-09-31-06\fitness-app-v202\REVIEW-p1p2.md`。

---

## 10. 需要用户拍板的事

1. **这一轮做哪块**：§6 的 A（P3 收尾）是最有价值的，但它依赖远端 AI 路径（需要 Key）；B/C/D/E 都可以纯本地做。
2. **谁能 push / 谁能 bump 版本**：**2026-09-19 用户改定了口径** ——
   「用户在本会话里明确说'推'，当前会话就可以 `git push origin main`」；
   **但打 tag 必须单独获授权**（tag 会触发 `android-release.yml` 出签名包和 GitHub Release，
   那是对外发布，不是留痕）。当天按这条打了 `v2.0.7`。
   历史教训：这条原来写的是"只有主理人能推"，09-18 晚上被另一棵树破过一次 ——
   它推了 4 个 P0 修复并把版本占到 17 / 2.0.6，本树不知道、同一天也 bump 成 17 / 2.0.6，
   同号两份内容（见文首分叉记录）。
   **落地规矩**：动 `versionCode` 前先 `git fetch` + `git ls-remote --tags`，确认号没被占用。
3. **是否要改产品行为**：涉及"AI 能不能自动改计划""伤病替代的粒度""计划是否默认每周相同"这类，
   都已经由用户拍过板（见 §3 / §4），**要改先问用户**。


---

## 11. P0 缺陷修复记录（2026-09-18 · 基线 tag `v2.0.5`）

来源：资料库《IronHabit 审查报告与功能补齐工作单》（逐条复核为属实，复核文档见
`deliverables/IronHabit-v2.0.5-审查复核与修复方案.md`）。本轮只做 **§2.1 的 4 个 P0**，
分支 `fix/p0-round1`，未 bump 版本（发布前统一 bump）。

| 编号 | 缺陷 | 修复要点 |
|---|---|---|
| P0-1 | 导入 v1–v3 老备份会**静默清空本机全部饮食记录** | `BackupRestoreRules` 增 `MEALS_SCHEMA_VERSION=4` + `carriesMeals()`；`BackupRepositoryImpl` 的 `mealDao.clearAll()/insertAll()` 改为仅当备份确实携带 `meals` 时执行。判据用**版本号**而非 `isEmpty()`（用户真的没记录时列表同样为空） |
| P0-2 | Keystore 异常 → 设置页/AI 页**一进就崩且无法自恢复** | `AiCredentialsStore` 初始化 `runCatching` 兜底（失败 = 未配置，不抛）；`setKey` 返回 `Boolean` 让 UI 如实报错；新增「重置加密存储」入口（`resetStorage()` + 设置页按钮 + 4 条新文案）；**禁止降级明文**。`SettingsViewModel.init` 的同步读取也加了兜底 |
| P0-3 | 「每周相同」模板里的手改/删除在**生成本周计划时被绕过** | `GenerateTrainingPlanUseCase` 拆成 `weekRows`（喂 advisor + 陈旧行回收，保持"只看本周"）与 `templateEditedRows`（只做保护）。新增**日级保护**：模板手改过、且本周没有启用专属行的天，本周整日不写专属行。新增 `PlanRepository.getRepeatRows()`（含软删行的一次性快照） |
| P0-4 | 保存按钮无防抖，连点产生重复数据 | `AddEditHabit/Plan/Exercise` 三个 ViewModel 的 UiState 增 `isSaving`，`onSave` 进入即守卫 + `finally` 复位；三个 Screen 的保存按钮绑 `enabled`。`TrainViewModel.onSubmitAddToPlan` **原本就有**守卫，未改 |

### ⚠️ P0-3 的两条重要结论（别按审查报告的字面改）

1. **槽位级保护不够**：`WeekPlanWeekResolver` 的生效规则是**日级**的（"该天有启用专属行 → 用专属行；
   否则回落模板"）。所以只把模板手改槽位并进 `blockedSlots` 仍会绕过用户改动 —— 必须**整日不写**。
2. **不能把模板行并进 `existing`**：`existing` 同时喂 `deactivateGenerated` 的输入，
   合并写法会把整份「每周相同」计划当成陈旧 AI 行**停用**（比原缺陷更严重）。
   `GenerateTrainingPlanUseCaseTest.generateTrainingPlan_neverRetiresRepeatTemplateRows` 就是钉这条的。

### 本轮验证现状

- JVM 全量：**47 类 / 435 例 / 0 失败**（`gradle :app:testDebugUnitTest`；新增 13 条：
  `carriesMeals` 2 条、`AiCredentialsStoreTest` 6 条、生成计划模板保护 3 条、保存防抖 2 条）。
- 既有 41 条对抗性用例同步补了 `getRepeatRows()` 桩（严格 mockk 会因新接口方法未打桩而失败）。
- **未做**：真机/模拟器手工验收（P0-1 的导入、P0-3 的"模板删一条 → 重新生成"仍需在真机上走一遍）。
  出包前必须补，单测证明不了界面上的东西。


---

## 12. v2.0.6 发布记录（2026-09-18）

**范围**：§2.1 的 4 个 P0 修复（详见 §11），无其他功能改动。

| 项 | 值 |
|---|---|
| 版本 | `versionCode = 17` / `versionName = "2.0.6"` |
| 单测 | `gradle :app:testDebugUnitTest` → **47 类 / 435 例 / 0 失败** |
| 真机验收 | **P0-1 通过**（3 轮真实导入：v3 老备份 meals 8→8；v4 备份 meals→2 对照；再还原→8，全程崩溃 0）<br>**P0-3 通过**（模板手改本周周一后重新生成 → 周一 `is_active` 全 0 整日未写；周三/周五照常写入；UI 显示「已保留 18 条」）<br>覆盖安装时 Room schema **v5 → v6 迁移成功、无崩溃** |
| 未在真机覆盖 | P0-2 的"Keystore 损坏"路径、P0-4 的真机连点 —— 均由 JVM 单测覆盖（6 条 + 2 条） |

**发版方式**：bump + 推 tag `v2.0.6` → `android-release.yml` 自动构建签名 APK 并挂到新 Release（与 v2.0.5 同一条流水线）。
**给下一位的提醒**：`today` 页的星期条只显示 4 个 chip，切"五/六/日"要先横向滑动；训练页同理（滑动起点避开右侧「新增」按钮）。
