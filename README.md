# IronHabit（自律健身）· 单机离线 Android App

> 个人自用 · 单机离线（可选联网：默认关闭，仅 AI 教练） · 仅 Android · 无账号无广告 · 不上架
> 包名：`com.ironhabit.app` ｜ 语言：简体中文 ｜ 技术栈：Kotlin + Jetpack Compose(M3) + Hilt + Room

## 这是什么

一个**自律 + 健身**的个人打卡 App：

- **今日**：打开即见今日训练计划 + 习惯，一键打卡，进度环 + 连续打卡（streak）大字。
- **训练**：周一~周日周计划、≥40 个内置动作库 + 自建动作、打卡历史。
- **自律**：习惯追踪（每日/每周）、日历热力图、连续天数。
- **我的**：近 30 天趋势图 + 分类占比图、身体数据、设置（主题/单位/提醒/备份）。

**核心红线：核心链路纯离线运行。** 打卡 / 统计 / 提醒 / 备份全部数据落本地 Room，飞行模式下功能完整。联网是**可选**能力（AI 教练一期 · DeepSeek · 用户自填 Key · **默认关闭**）：关闭、未配 Key 或断网时自动回落本地规则，功能完整；详见 `docs/ARCHITECTURE.md` §7.7。

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
│       ├─ AndroidManifest.xml  # 权限白名单：本地能力 + INTERNET（仅 AI 教练，默认关闭）
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

联网是**可选**能力，红线由 `android-ci.yml` 的**权限白名单**守住（push / PR 自动执行）：

```bash
# ① 依赖红线：不得引入任何网络 / 云服务库（核心链路只允许 HttpURLConnection 直连）
grep -RE "retrofit|okhttp|ktor|firebase|play-services|volley" \
     app/build.gradle.kts gradle/libs.versions.toml       # 期望：无输出（命中即 ::error:: 失败）

# ② 权限白名单：AndroidManifest 只允许下面 6 个权限，多一个即失败
ALLOWED='android.permission.SCHEDULE_EXACT_ALARM|android.permission.USE_EXACT_ALARM|android.permission.POST_NOTIFICATIONS|android.permission.RECEIVE_BOOT_COMPLETED|android.permission.VIBRATE|android.permission.INTERNET'
BAD=$(grep -oE 'android\.permission\.[A-Z_]+' app/src/main/AndroidManifest.xml | sort -u \
      | grep -Ev "^($ALLOWED)$" || true)
[ -z "$BAD" ] || { echo "发现白名单之外的权限：$BAD"; exit 1; }
```

> 即：`INTERNET` **已放行，但仅限 AI 教练**；账号 / 云同步 / 广告类权限一律不得新增（白名单之外 = CI 红叉）。
> 关闭联网、未配 Key 或断网时 App 功能完整（自动回落本地规则），打卡 / 统计 / 提醒 / 备份始终不依赖网络。

## 许可

本项目为个人自用工程。第三方依赖均为 Apache-2.0 / MIT 等宽松许可；架构设计**仅借鉴**参考项目的分层与写法，未复制任何受 GPL 约束的代码。
