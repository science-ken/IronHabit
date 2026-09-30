# IronHabit（自律健身）· 单机离线 Android App

> 个人自用 · 单机完全离线 · 仅 Android · 无账号无广告 · 不上架
> 包名：`com.ironhabit.app` ｜ 语言：简体中文 ｜ 技术栈：Kotlin + Jetpack Compose(M3) + Hilt + Room

## 这是什么

一个**自律 + 健身**的个人打卡 App：

- **今日**：打开即见今日训练计划 + 习惯，一键打卡，进度环 + 连续打卡（streak）大字。
- **训练**：周一~周日周计划、≥40 个内置动作库 + 自建动作、打卡历史。
- **自律**：习惯追踪（每日/每周）、日历热力图、连续天数。
- **我的**：近 30 天趋势图 + 分类占比图、身体数据、设置（主题/单位/提醒/备份）。

**核心红线：零联网。** 打卡 / 统计 / 提醒 / 备份 / 教练页全部数据落本地 Room，App 不申请网络权限，飞行模式下功能完整。曾经有过一条「用户自填 API Key 直连 DeepSeek」的可选联网通道，连同 `INTERNET` 权限与密钥存储在 v2.0.14 整条删除；详见 `docs/ARCHITECTURE.md` §7.7。想用真 AI 的判断，走「导入训练 / 导入饮食」那条离线回程：App 生成提问模板 → 用户在自己手机外面问 AI → 把回答粘回来逐天确认，App 一次网络都不发。

## 快速导航

| 文档 | 内容 |
|------|------|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 系统架构设计 + 任务分解（唯一施工依据） |
| [`docs/PRD.md`](docs/PRD.md) | 产品需求文档 |
| [`docs/CI.md`](docs/CI.md) | **首次部署 / 出签名包 / 手机安装 的图文步骤（必读）** |

## 本机没有开发环境？没问题

本工程**不需要**在本地安装 Android SDK / Android Studio，全部编译与出包都跑在 **GitHub Actions** 云端。你只需要：

1. 把本工程上传到你的 GitHub 仓库（见 `docs/CI.md` 的「两条上传路径」）；
2. 在**自己的电脑上**生成签名用的 keystore，取出 base64 后在仓库配置 **4 个 Secret**（见 `docs/CI.md` §2、§3）；
3. 运行 `Android Release` 工作流，下载**已签名 APK**，用手机安装。

> ⚠️ **唯一的例外**：签名密钥必须在**本机**生成——这是全流程唯一需要 JDK（`keytool`）的一步，
> 也**绝不能**交给云端 CI 代生成（CI 代生成 = 私钥进日志 / 变成可下载的 Artifact = 签名身份公开，
> 而 Android 的签名身份**无法吊销**）。为什么、怎么做 → `docs/CI.md` §2。

完整图文步骤 → **[`docs/CI.md`](docs/CI.md)**。

## 目录结构（关键部分）

```
IronHabit/
├─ .github/workflows/           # 云端流水线（2 个）：CI / 出签名包
│   ├─ android-ci.yml           # push/PR：网络红线校验（权限白名单制）+ 单测 + debug APK
│   └─ android-release.yml      # tag/手动：出已签名 release APK + Release（缺 Secret 即失败）
├─ docs/
│   ├─ ARCHITECTURE.md          # 架构与任务分解
│   ├─ PRD.md                   # 需求
│   └─ CI.md                    # 部署 / 出包 / 安装 步骤
├─ gradle/
│   ├─ libs.versions.toml       # 版本目录（唯一版本来源）
│   └─ wrapper/                 # Gradle 8.11.1 Wrapper（含 jar）
├─ app/
│   ├─ build.gradle.kts         # 模块构建 + 签名配置
│   ├─ proguard-rules.pro       # R8 保留规则
│   └─ src/main/
│       ├─ AndroidManifest.xml  # 权限白名单：全部为本地能力，无 INTERNET
│       ├─ java/com/ironhabit/app/
│       │   ├─ IronHabitApp.kt  # @HiltAndroidApp 入口
│       │   ├─ MainActivity.kt  # @AndroidEntryPoint
│       │   ├─ di/              # Hilt 装配
│       │   ├─ data/            # Room / DataStore / 通知 / 仓库实现
│       │   ├─ domain/          # 模型 / 接口 / 用例 / 纯函数
│       │   └─ ui/              # Compose 屏幕 / 组件 / 主题 / 导航
│       └─ res/                 # 中文文案 / 图标 / 主题
├─ settings.gradle.kts
├─ build.gradle.kts
└─ gradle.properties
```

## 换机不丢数据

- Room 数据库 + DataStore 已纳入 **Android 12+ 云备份 / 设备迁移**规则（`data_extraction_rules.xml`、`backup_rules.xml`），登录同一 Google 账号换机可自动恢复。
- 也支持 App 内**手动导出 / 导入 JSON**（「我的 → 备份」），适合离线迁移。

## 网络红线校验（CI 自动执行）

零联网是硬约束，红线由 `android-ci.yml` 两道检查守住（push / PR 自动执行）：

```bash
# ① 依赖红线：现扫全部构建脚本，不得出现任何网络 / 云服务库（-i 堵大小写变体）
grep -iHE 'retrofit|okhttp|ktor|firebase|play-services|volley' $BUILD_FILES
#   grep 退出码：0=命中（违规）1=未命中（放行）2+=脚本异常（一律按违规处理）

# ② 权限白名单：解析**合并后**的 manifest（依赖库注入的权限同样逃不过白名单）
ALLOWED='android.permission.schedule_exact_alarm|android.permission.post_notifications|android.permission.receive_boot_completed|android.permission.vibrate'
```

> 白名单是**子集判定**：声明了表外的权限 = CI 红。`INTERNET` 与 `USE_EXACT_ALARM` 都**刻意不在表里** ——
> 前者随 v2.0.14 删掉「用户自填 API Key 直连 DeepSeek」那条通道一起摘除，后者是 Play 只发给
> 闹钟 / 日历类应用的特殊权限。重新声明它们应当逼出一轮决策，而不是被白名单顺手放行。
> 只统计 `<uses-permission>`：组件上的 `android:permission=` 是访问守卫（"谁才有资格调用我"），
> 不赋予 App 任何能力 —— 早先的写法把它算进来过，判成 `DUMP` 假阳性。

## 许可

本项目为个人自用工程。第三方依赖均为 Apache-2.0 / MIT 等宽松许可；架构设计**仅借鉴**参考项目的分层与写法，未复制任何受 GPL 约束的代码。
