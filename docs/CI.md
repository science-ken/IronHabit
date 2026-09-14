# 出包与安装指南（零本地环境版）

> 适用前提：**你的电脑上没有 git、没有 JDK、也没有 Android SDK，本机还访问不了 github.com。**
> 因此本工程的全部编译与出包都在 **GitHub Actions 云端**完成——你只负责「把代码送上去」和「把 APK 拿下来」。
> 本文按顺序做完即可得到能装到手机上的 **已签名 APK**。

---

## 0. 一次性准备：注册一个 GitHub 账号

若还没有 → 打开 <https://github.com/signup> 注册（免费）。记下你的**用户名**。

---

## 1. 把工程送进 GitHub（两条上传路径，任选其一）

> 我们要上传的是本工程**根目录**下的全部内容（即 `fitness-app/` 里面的文件），而不是它的上一层目录。

### 路径 ①：网页拖拽上传（最简单，无需 git，适合大多数情况）

1. 浏览器登录 GitHub → 右上角 `+` → **New repository**。
2. 填写：
   - **Repository name**：`IronHabit`
   - 选择 **Public**（私有仓库也能跑 Actions，但公开更省心）
   - **不要**勾选 "Add a README file / .gitignore / license"（避免冲突）
   - 点 **Create repository**。
3. 新仓库页面点 **uploading an existing file**（或 `Add file → Upload files`）。
4. 打开本工程的根目录 `fitness-app/`，**全选里面的所有文件和文件夹**，拖进网页的上传框。
   - 若网页上传框无法一次拖入含子目录的结构，可**分批**：先拖根文件（`settings.gradle.kts`、`build.gradle.kts`、`gradlew`、`gradlew.bat` 等），再逐个进入子目录上传。
   - **重点**：`.github` 文件夹（含 3 个 workflow）必须上传成功，否则 Actions 不会出现对应流水线。网页若看不到 `.github`（以 `.` 开头会被隐藏），可在系统里先勾选"显示隐藏文件"。
   - `gradle/wrapper/gradle-wrapper.jar` 这个 `.jar` 也必须上传（它是 Wrapper 的核心，不能漏）。
5. 下方 **Commit changes** 提交。提交后 GitHub 会自动触发 `Android CI`。

> 提示：网页上传单个文件上限 25 MB，本工程所有文件都远小于此，无压力。

### 路径 ②：找一台能上网的电脑，用 git 推送（结构完整、一次到位）

在**能正常访问 github.com 且装有 git** 的电脑上：

```bash
# 1) 把那台电脑能拿到的本工程目录，初始化为 git 仓库
cd /path/to/fitness-app
git init
git add .
git commit -m "chore: IronHabit 初始工程 + 云端 CI"

# 2) 关联到你在 GitHub 新建的仓库（把 <你的用户名> 换成实际值）
git branch -M main
git remote add origin https://github.com/<你的用户名>/IronHabit.git

# 3) 推送
git push -u origin main
```

推送后 GitHub 会自动触发 `Android CI`。

---

## 2. 生成本 App 专属签名密钥（keystore）—— 只做一次

因为本机没有 JDK（跑不了 `keytool`），我们用云端工作流来生成。

1. 进入仓库页 → 顶部 **Actions** 标签。
2. 左侧列表点 **Generate Keystore (run once)** → 右侧 **Run workflow** → 绿色按钮 **Run workflow**。
3. 等约 1 分钟，点进这次运行，展开 **Generate keystore** 这一步骤的日志。
4. 日志里会打印**一整行很长的 base64 文本**，位于：
   ```
   ===== 请将下面整行内容保存为仓库 Secret：SIGNING_KEY =====
   <这一整行就是 SIGNING_KEY 的值>
   ==========================================================
   ```
   把这一整行**完整复制**下来（不要漏掉任何字符、不要换行）。
5. 同一个日志里还会告诉你另外 3 个值（固定如下，可直接照抄）：
   | Secret 名 | 值 |
   |-----------|-----|
   | `KEY_STORE_PASSWORD` | `ironhabitStore123` |
   | `KEY_ALIAS` | `ironhabit` |
   | `KEY_PASSWORD` | `ironhabitKey123` |

> 也可以在本次运行的底部 **Artifacts** 里下载 `ironhabit-keystore`（含 `.jks` 与 `.jks.base64`），自己留档备份。
> **务必保管好**：以后覆盖安装升级必须用同一份签名，丢了就只能卸载重装。

---

## 3. 配置 4 个 Secret（在 GitHub 仓库的哪个页面？）

路径：**仓库页 → `Settings`（顶部标签栏）→ 左侧 `Secrets and variables` → `Actions` → 右侧 `New repository secret`**。

逐个添加以下 4 个（名字**必须一模一样**，区分大小写）：

| Secret 名称 | 值来源 |
|-------------|--------|
| `SIGNING_KEY` | 第 2 步日志里那一整行 base64 |
| `KEY_STORE_PASSWORD` | `ironhabitStore123` |
| `KEY_ALIAS` | `ironhabit` |
| `KEY_PASSWORD` | `ironhabitKey123` |

> 说明：`android-release.yml` 会读取这 4 个 Secret 生成 `keystore.properties` 并对 APK 签名。
> 若**没配** `SIGNING_KEY`，流水线**不会失败**，而是自动改用"临时 keystore"并打印黄色告警——但这种 APK 每次构建签名都不同，**无法覆盖升级**，仅用于临时试装。

---

## 4. 出「已签名」APK（两种触发方式）

### 方式 A：打标签触发（推荐，会自动建 Release 页）

在仓库页 → **Releases → Draft a new release** → **Choose a tag** 输入 `v1.0.0` → **Create new tag** → 填写标题 → **Publish release**。
打 tag 会自动触发 `Android Release (Signed APK)`。

### 方式 B：手动触发

**Actions → Android Release (Signed APK) → Run workflow → Run workflow**。

---

## 5. 下载 APK

两种触发方式都会产出：

- **Artifacts**（本次运行页底部）：`ironhabit-release-apk` —— 下载得到 `app-release.apk`。
- **Release 页**（方式 A 且打 tag 时）：直接有 `app-release.apk` 资产，点一下即可下载，链接长期有效。

> 想先要一个**未签名**的 debug 包快速验证？看 `Android CI` 运行的 Artifacts → `ironhabit-debug-apk`。

---

## 6. 把 APK 装到手机

1. 把下载到的 `app-release.apk` 传到手机（微信/数据线/网盘均可）。
2. 手机上点开该 APK。首次会提示"**未知来源**"被拦截：
   - 进入 **设置 → 应用 → 特殊应用权限 → 安装未知应用**，找到你用来打开 APK 的那个 App（如"文件管理""浏览器"），允许它安装未知应用。
3. 返回继续安装 → **安装** → 完成。
4. 首次打开时，系统会申请**通知权限**（用于到点提醒）——建议允许；如拒绝，App 仍可正常使用，只是收不到提醒。
5. 若 Android 12+ 想获得"到点精确"提醒，系统可能额外引导你开启**精确闹钟**权限（设置 → 应用 → 特殊应用权限 → 闹钟与提醒）——不开也能用，只是提醒时间可能略有偏差。

---

## 7. 以后如何升级

1. 改代码后重新上传（同第 1 步）或 `git push`。
2. 让 `versionCode` / `versionName` 递增（`app/build.gradle.kts`）。
3. 重新触发一次 `Android Release`，用**同一套 Secret** 出包。
4. 把新 APK 覆盖安装到手机即可（签名一致，数据保留）。

---

## 8. 常见问题

| 现象 | 原因 / 处理 |
|------|-------------|
| Actions 里看不到任何 workflow | `.github/workflows/` 没上传成功，或目录被网页上传搞成了 `.github/.github/...`。检查仓库根目录下确有 `.github/workflows/android-ci.yml` |
| CI 报 `gradlew: Permission denied` | 流水线已含 `chmod +x ./gradlew`；若仍报错，确认 `gradlew` 文件本身以文本形式存在且未被破坏 |
| Release 日志出现 `未检测到 SIGNING_KEY Secret` 告警 | 第 3 步的 Secret 没配或名字拼错；按表核对 4 个名字 |
| 手机提示"应用未安装 / 签名冲突" | 之前装过**不同签名**的版本。先卸载旧版再装 |
| 装好后闪退 | 记下机型与系统版本，附上 `Android CI` 的报错日志，反馈给开发者 |
| 换手机想带数据 | 登录同一 Google 账号开启系统备份；或 App 内「我的 → 备份 → 导出 JSON」，新机「导入」 |

---

## 9. 流水线速查

| 文件 | 触发 | 作用 |
|------|------|------|
| `.github/workflows/android-ci.yml` | push / PR / 手动 | 零网络红线校验 + `testDebugUnitTest` + `assembleDebug` → debug APK |
| `.github/workflows/android-release.yml` | 打 `v*` tag / 手动 | 零配置亦可出包（临时签名）+ 配好 Secret 后出**已签名** release APK + 建 Release |
| `.github/workflows/generate-keystore.yml` | 手动 | 云端 `keytool` 生成 `.jks` 并打印 base64 供存 Secret |

> 全部构建命令均为标准 Gradle 任务（`assembleDebug` / `testDebugUnitTest` / `:app:assembleRelease`），与本地执行等价。
