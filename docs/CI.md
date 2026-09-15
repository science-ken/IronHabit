# 出包与安装指南（零本地环境版）

> 适用前提：**你的电脑上没有 git、没有 JDK、也没有 Android SDK，本机还访问不了 github.com。**
> 因此本工程的全部编译与出包都在 **GitHub Actions 云端**完成——你只负责「把代码送上去」和「把 APK 拿下来」。
> 本文按顺序做完即可得到能装到手机上的 **已签名 APK**。
>
> **唯一的例外（见第 2 节）**：签名密钥（keystore）必须在**你自己的电脑上**生成，**绝不能**交给云端 CI 代生成。
> 私钥一旦出现在 CI 日志或可下载的 Artifact 里就等于公开——任何人拿到它，都能伪造出一个你手机愿意当作「正常升级」安装的 APK，
> 而这把签名身份**无法吊销**。这一步只需要 `keytool`（临时装个 JDK 即可；也可以在任意一台能装 JDK 的电脑上做完，再把 `.jks` 拷回来自己保管）。

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
   - **重点**：`.github` 文件夹（含 2 个 workflow：`android-ci.yml`、`android-release.yml`）必须上传成功，否则 Actions 不会出现对应流水线。网页若看不到 `.github`（以 `.` 开头会被隐藏），可在系统里先勾选"显示隐藏文件"。
   - `gradle/wrapper/gradle-wrapper.jar` 这个 `.jar` 也必须上传（它是 Wrapper 的核心，不能漏）。
   - **绝对不要**上传任何 `.jks`、`signing-key.txt` 或写有口令的文本文件（第 2 节说明原因）。
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

> 上传前确认一遍：仓库里**不应**出现 `*.jks`、`*.keystore`、`keystore.properties`（`.gitignore` 已忽略它们），
> 也不应出现任何明文口令。签名材料只走 Secret，不进仓库。

---

## 2. 生成本 App 专属签名密钥（keystore）—— 只做一次，且在本机完成

> **为什么必须在本机做？** keystore（`.jks`）+ 库口令 + 密钥口令 = 这个 App 的**签名身份**。
> 谁拥有它，谁就能签出一个你手机愿意当作**正常升级**安装的 APK；而签名身份**没有任何吊销机制**。
> 所以它**只能**生成在你自己的电脑上，并被完整、原样地填进 GitHub 的 Secret。
>
> **下面这些内容一律不许**出现在 Actions 日志、聊天窗口、Issue、PR、仓库文件、Actions Artifact 里：
>
> - `ironhabit-release.jks`（私钥文件本身）
> - 它的 base64 文本（即 `SIGNING_KEY` 的值）
> - 库口令与密钥口令
>
> ⚠️ 早期版本的仓库里有一个 `generate-keystore` 工作流：它在云端生成 keystore、把 base64 私钥**打印进日志**、
> 还把 `.jks` 与 `.base64` **上传成可下载的 Artifact**，并且口令是写死的公开值。**该工作流已删除**，
> 它生成或使用过的 keystore 与口令**一律按已泄露处理**（补救步骤见第 10 节）。

### 2.1 生成 keystore

需要 `keytool`（JDK 自带）。先确认有没有：

```powershell
keytool -help
```

若提示找不到命令 → 先装一个 JDK 17（例如 [Temurin 17](https://adoptium.net/temurin/releases/?version=17)），装完**新开一个终端**再试。

**Windows（PowerShell）** —— 在专门存放密钥的目录里执行（示例用 `D:\keys`，换成你自己的路径）：

```powershell
New-Item -ItemType Directory -Force D:\keys | Out-Null
Set-Location D:\keys
keytool -genkeypair -v `
  -keystore ironhabit-release.jks `
  -alias ironhabit `
  -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass "<你的库口令>" `
  -keypass "<你的密钥口令>" `
  -dname "CN=IronHabit, OU=Personal, O=IronHabit, L=NA, ST=NA, C=CN"
```

**macOS / Linux（bash）**：

```bash
mkdir -p ~/keys && cd ~/keys
keytool -genkeypair -v \
  -keystore ironhabit-release.jks \
  -alias ironhabit \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass '<你的库口令>' \
  -keypass '<你的密钥口令>' \
  -dname "CN=IronHabit, OU=Personal, O=IronHabit, L=NA, ST=NA, C=CN"
```

逐项说明：

| 参数 | 换成什么 |
|------|----------|
| `<你的库口令>` | **你自己定的**长口令（建议 16 位以上随机串，用密码管理器保存）。它将作为 Secret `KEY_STORE_PASSWORD` 的值 |
| `<你的密钥口令>` | 同上；可以与库口令**相同**（`keytool` 也允许不同）。它将作为 Secret `KEY_PASSWORD` 的值 |
| `-alias ironhabit` | **保持不变**，它就是 Secret `KEY_ALIAS` 的值 `ironhabit` |
| `ironhabit-release.jks` | 生成出来的私钥文件，**只留在本机**，不要放进仓库、不要上传 |

> **务必备份**：把 `ironhabit-release.jks` 与两个口令存到**离线**位置（U 盘 / 密码管理器附件 / 加密压缩包）。
> 丢了就再也签不出能覆盖升级的包，只能让用户卸载重装（本机数据会丢，补救流程见第 10 节）。

### 2.2 取出 `SIGNING_KEY` 需要的 base64

`SIGNING_KEY` 这个 Secret 的值 = **整个 `.jks` 文件的 base64 编码**：**一整行**，不能换行、不能漏字符。

**Windows（PowerShell）** —— 直接写成文件（最稳，避免复制残缺）：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\keys\ironhabit-release.jks")) | Set-Content -NoNewline -Encoding ascii D:\keys\signing-key.txt
```

想直接进剪贴板？把命令末尾的 `Set-Content -NoNewline -Encoding ascii D:\keys\signing-key.txt` 换成 `Set-Clipboard`，
再粘进记事本确认是**一整行**即可。

**macOS / Linux（bash）**：

```bash
base64 -w0 ~/keys/ironhabit-release.jks > ~/keys/signing-key.txt
```

> macOS 自带的 `base64` 没有 `-w` 参数，改用：
> `base64 -i ~/keys/ironhabit-release.jks | tr -d '\n' > ~/keys/signing-key.txt`

拿到 `signing-key.txt` 后：**打开它 → 全选 → 复制**，这一整行就是第 3 步要填给 `SIGNING_KEY` 的值。

> 🚫 **再次强调**：不要把这个文件的内容（以及 keystore、两个口令）贴进 Actions 日志、聊天、Issue、PR，
> 不要 `git add` 进仓库，也不要上传成 Actions Artifact。它**只允许**粘进 GitHub 的 Secret 输入框。
> 粘完可以把 `signing-key.txt` 删掉——`.jks` 还在本机，随时能再生成一次。

---

## 3. 配置 4 个 Secret（在 GitHub 仓库的哪个页面？）

路径：**仓库页 → `Settings`（顶部标签栏）→ 左侧 `Secrets and variables` → `Actions` → 右侧 `New repository secret`**。

逐个添加以下 4 个（名字**必须一模一样**，区分大小写）：

| Secret 名称 | 值来源 |
|-------------|--------|
| `SIGNING_KEY` | 第 2.2 步 `signing-key.txt` 里那一整行 base64（不要换行、不要漏字符） |
| `KEY_STORE_PASSWORD` | 第 2.1 步 `-storepass` 用的口令（即 `<你的库口令>`） |
| `KEY_ALIAS` | 第 2.1 步 `-alias` 用的值：`ironhabit` |
| `KEY_PASSWORD` | 第 2.1 步 `-keypass` 用的口令（即 `<你的密钥口令>`） |

> 说明：`android-release.yml` 会读取这 4 个 Secret 生成 `keystore.properties` 并对 APK 签名。
> **这 4 个必须全部配齐**：只要缺任何一个，release 流水线会**立刻失败**（红色 ✗，不出包），
> 日志里用 `::error::` 明确列出缺了哪个 Secret，并提示到 `docs/CI.md` 第 2、3 节查看配置方法。
> 流水线**不再**提供"临时 keystore 兜底"：旧版那个兜底会用公开口令现场生成一份签名，等于把签名身份送人。
> 所以「Secret 没配好 = 出不了包」是**刻意设计**，请按上面的表核对到齐。

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
| Release 流水线红叉，日志报 `缺少签名所需的 Secret` | 第 3 步的 4 个 Secret 没配齐或名字拼错。按表中 4 个名字逐一核对（区分大小写）：`SIGNING_KEY` / `KEY_STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` |
| Release 流水线报 `SIGNING_KEY 解码失败或为空` | `SIGNING_KEY` 的值不是**一整行** base64（多半是复制时被换行或截断）。按第 2.2 节重新生成并复制 |
| 怀疑 keystore 或口令泄露了 | 旧签名**无法吊销**，只能换新 keystore 并递增 `versionCode`，详见第 10 节 |
| 手机提示"应用未安装 / 签名冲突" | 之前装过**不同签名**的版本。先卸载旧版再装 |
| 装好后闪退 | 记下机型与系统版本，附上 `Android CI` 的报错日志，反馈给开发者 |
| 换手机想带数据 | 登录同一 Google 账号开启系统备份；或 App 内「我的 → 数据备份 → 导出备份」，新机「导入备份」 |

---

## 9. 流水线速查

| 文件 | 触发 | 作用 |
|------|------|------|
| `.github/workflows/android-ci.yml` | push / PR / 手动 | 零网络红线校验 + `testDebugUnitTest` + `assembleDebug` → debug APK |
| `.github/workflows/android-release.yml` | 打 `v*` tag / 手动 | 用 4 个 Secret 里的 keystore 出**已签名** release APK + 上传 artifact + 打 tag 时建 Release；**Secret 缺失即失败**（不再有临时签名兜底） |

> 全部构建命令均为标准 Gradle 任务（`assembleDebug` / `testDebugUnitTest` / `:app:assembleRelease`），与本地执行等价。

---

## 10. 如果密钥疑似泄露

**先接受一个事实**：App 的签名私钥**无法吊销**。Android 没有"作废签名"的机制，
一旦别人拿到你的 keystore（或它的两个口令），他就能签出一个**你手机当成正常升级**的 APK。
唯一的止损方式是：**换一把新钥匙，并让所有人卸载重装**。

**以下情况一律按"已泄露"处理（本工程历史版本确实如此）**：

- 早期的 `generate-keystore` 工作流在 Actions 日志里**打印过 base64 私钥**，并把 `.jks` 与 `.base64` **上传成可下载的 Artifact**；
- 同期的两个口令是**写死在仓库文件里的**（并出现在当时的文档中；具体字面量仍可在 git 历史里查到，故此处不再复述），
  而 release 工作流在缺少 Secret 时会用它们现场生成临时签名。

结论：**旧的 CI keystore 视为已泄露；那两个旧口令按"已公开"处理**——
哪怕现在把文件删掉，它们也可能已经存在于历史提交、fork、日志下载件或剪贴板里，**不能再当作有效的保密值**。

**补救步骤（按顺序做）**：

1. **先备份数据。** 换签名之后手机上装的是"另一个 App"，必须**卸载重装**，App 本地数据库会一起被删除。
   先在手机打开 App → **「我的 → 数据备份 → 导出备份」**，把导出的 JSON 存到手机之外（发给自己 / 网盘 / 电脑）。
2. **在本机生成一把全新的 keystore**（按第 2.1 节，建议另取新文件名如 `ironhabit-release-v2.jks`），
   并且**换用两个全新的口令**——不要沿用仓库历史里写死过的那两个旧口令。
3. **重新取 base64**（第 2.2 节），然后到 **Settings → Secrets and variables → Actions**，
   把这 4 个 Secret **逐个覆盖**成新值：`SIGNING_KEY`、`KEY_STORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。
4. **递增 `versionCode`**（建议同时递增 `versionName`），位置在 `app/build.gradle.kts`。
   新旧签名不同，Android 会**拒绝覆盖安装**；递增版本号能让新包在重装后正确参与后续升级，也便于区分新旧包。
5. 打一个新 tag（如 `v1.0.1`）触发 `Android Release`，确认 `apksigner verify` 通过、artifact 里是**新签名**的 APK。
6. 装到手机：**先卸载旧版** → 安装新版 → 打开 App → **「我的 → 数据备份 → 导入备份」** 恢复第 1 步导出的 JSON。
7. 善后清理：删除泄露的旧 keystore、旧口令记录、旧 base64 文件；确认仓库里没有任何 `.jks` / 口令 / base64 残留；
   如果旧 keystore 曾经进过公开仓库或群聊，就把它当作**永久公开**，**永远不要**再用它签任何东西。

> "卸载重装"是换签名期间**唯一**可行的路径：Android 只认签名，签名不一致就是"另一个应用"。
> 这也正是第 2 节反复强调的原因——keystore 与口令只允许留在**你自己电脑 + GitHub Secret** 这两处。
