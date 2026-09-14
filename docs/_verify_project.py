"""IronHabit 工程交叉一致性核验器
用法: python verify_project.py [--write-baseline]
核验项:
 1. §2 施工图的 171 个文件是否全部落盘
 2. Kotlin 文件 package 声明是否与目录结构一致
 3. AndroidManifest 是否含 INTERNET 权限(零网络红线)
 4. 依赖清单是否出现网络库(retrofit/okhttp/ktor/firebase/play-services/volley)
 5. Hilt @Binds 引用的实现类是否存在
 6. Repository 实现是否覆盖 domain 接口的全部方法
 7. BuiltInExercises 动作条数是否 >=40
 8. 占位符/TODO/未完成标记扫描
 9. R.string 引用 vs strings.xml 定义
10. ui 层调用的 UseCase 是否落盘
11. 委托属性智能转换风险（Kotlin 规则级编译错误）
12. 格式化占位符 vs 实参类型（运行时崩溃）
13. 常用类型 import 缺失（Unresolved reference）
14. Manifest / 主题引用的资源是否存在（资源链接失败）
"""
import os, re, sys, json

ROOT = r"D:\Workbuddy data\2026-09-14-09-31-06\fitness-app"
DOC  = os.path.join(ROOT, "docs", "ARCHITECTURE.md")
OUT  = os.path.join(ROOT, "docs", "_VERIFY_REPORT.txt")

KOTLIN_ROOT = "app/src/main/java/com/ironhabit/app"
RES_ROOT    = "app/src/main/res"
PKG_ROOT    = "com.ironhabit.app"
# 包名 -> 目录名（用于 app/src/test、app/src/androidTest 两个源根）
PKG_PATH    = PKG_ROOT.replace(".", "/")

NET_LIBS = ["retrofit", "okhttp", "ktor", "firebase", "play-services", "volley", "okio"]

lines = []
def w(s=""):
    lines.append(s)

# ---------- 解析 §2 施工图 ----------
doc = open(DOC, encoding="utf-8").read().splitlines()
marks = [(i, l.strip()) for i, l in enumerate(doc) if re.match(r"^#{2,4} ", l)]
marks.append((len(doc), "EOF"))

def sec(prefix):
    for idx, (i, t) in enumerate(marks):
        if t.startswith(prefix):
            return doc[i+1:marks[idx+1][0]]
    return []

def resolve(path, section=None):
    """把施工图里的相对路径/省略写法解析为工程内真实相对路径

    section 提示用于消歧：§2.8「测试」小节里的 `.../xxx` 走 test 源根，
    其它小节的 `.../xxx` 走 main 源根（§2.7 的 `.../res/` 走 res 根）。
    """
    p = path.replace("`", "").strip()
    if p.startswith(".../"):
        tail = p[4:]
        # 资源类走 res 根
        if tail.startswith("res/"):
            return os.path.join(RES_ROOT, tail[4:]).replace("\\", "/")
        # §2.8 测试小节：省略根 = app/src/test/java/com/ironhabit/app
        if section == "2.8" and not tail.startswith("androidTest/"):
            return os.path.join("app/src/test/java", PKG_PATH, tail).replace("\\", "/")
        return os.path.join(KOTLIN_ROOT, tail).replace("\\", "/")
    if p.startswith("app/src/main/java/.../"):
        return os.path.join("app/src/main/java", PKG_PATH, p[len("app/src/main/java/.../"):]).replace("\\","/")
    if p.startswith("app/src/test/java/.../"):
        return os.path.join("app/src/test/java", PKG_PATH, p[len("app/src/test/java/.../"):]).replace("\\","/")
    if p.startswith("app/src/androidTest/java/.../"):
        return os.path.join("app/src/androidTest/java", PKG_PATH, p[len("app/src/androidTest/java/.../"):]).replace("\\","/")
    return p

def table_rows(body, skip=("相对路径","项","#","文件")):
    rows = []
    for l in body:
        t = l.strip()
        if not t.startswith("|"): continue
        if re.match(r"^\|[\s\-:|]+\|$", t): continue
        c = [x.strip() for x in t.strip("|").split("|")]
        if c and c[0] in skip: continue
        rows.append(c)
    return rows

expected = []
for n in ["2.1","2.2","2.3","2.4","2.5","2.6","2.7","2.8"]:
    for r in table_rows(sec("### %s" % n)):
        expected.append((n, r[0].replace("`","").strip(), resolve(r[0], n)))

w("=" * 78)
w("IronHabit 工程交叉一致性核验报告")
w("=" * 78)
w("")

# ---------- 1. 文件落盘 ----------
w("【1】施工图文件落盘核验（预期 %d 个）" % len(expected))
missing, empty, present = [], [], []
for n, raw, rel in expected:
    ap = os.path.join(ROOT, rel.replace("/", os.sep))
    if not os.path.isfile(ap):
        missing.append((n, raw, rel))
    else:
        sz = os.path.getsize(ap)
        if sz == 0:
            empty.append((n, raw, rel))
        present.append((n, raw, rel, sz))
w("    已落盘 = %d / %d" % (len(present), len(expected)))
if missing:
    w("    !! 缺失 %d 个:" % len(missing))
    for n, raw, rel in missing:
        w("       [%s] %s" % (n, raw))
else:
    w("    OK 无缺失")
if empty:
    w("    !! 空文件 %d 个:" % len(empty))
    for n, raw, rel in empty:
        w("       [%s] %s" % (n, raw))
w("")

# ---------- 2. package 声明 ----------
w("【2】Kotlin package 声明与目录一致性")
bad_pkg, nokt = [], 0
for n, raw, rel in expected:
    ap = os.path.join(ROOT, rel.replace("/", os.sep))
    if not rel.endswith(".kt") or not os.path.isfile(ap): continue
    nokt += 1
    d = os.path.dirname(rel).replace("\\", "/")
    idx = d.find(KOTLIN_ROOT)
    src_root = KOTLIN_ROOT
    if idx < 0:
        for alt in ("app/src/test/java/" + PKG_PATH, "app/src/androidTest/java/" + PKG_PATH):
            if d.find(alt) >= 0:
                idx, src_root = d.find(alt), alt
                break
    if idx < 0:
        continue
    tail = d[idx + len(src_root):].replace("/", ".").strip(".")
    expect_pkg = PKG_ROOT + ("." + tail if tail else "")
    try:
        txt = open(ap, encoding="utf-8", errors="replace").read()
    except Exception:
        continue
    m = re.search(r"^\s*package\s+([\w.]+)", txt, re.M)
    if not m:
        bad_pkg.append((rel, "无 package 声明"))
    elif m.group(1).strip() != expect_pkg:
        bad_pkg.append((rel, "声明=%s 应为=%s" % (m.group(1).strip(), expect_pkg)))
w("    检查 %d 个 .kt 文件" % nokt)
if bad_pkg:
    w("    !! 不一致 %d 个:" % len(bad_pkg))
    for r, why in bad_pkg:
        w("       %s -> %s" % (r, why))
else:
    w("    OK 全部一致")
w("")

# ---------- 3. INTERNET 权限 ----------
w("【3】零网络红线：AndroidManifest INTERNET 权限")
mf = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
if os.path.isfile(mf):
    t = open(mf, encoding="utf-8", errors="replace").read()
    if "android.permission.INTERNET" in t:
        w("    !! 违反红线：Manifest 中含 android.permission.INTERNET")
    else:
        w("    OK 未声明 INTERNET 权限")
    perms = re.findall(r'android:name="(android\.permission\.[^"]+)"', t)
    w("    已声明权限: %s" % ", ".join(sorted(set(p.split(".")[-1] for p in perms))))
    if not any("SCHEDULE_EXACT_ALARM" in p for p in perms):
        w("    !! 缺 SCHEDULE_EXACT_ALARM（精确提醒需要）")
else:
    w("    !! AndroidManifest.xml 不存在")
w("")

# ---------- 4. 依赖清单网络库 ----------
w("【4】依赖清单禁用库扫描")
for f in ["gradle/libs.versions.toml", "app/build.gradle.kts"]:
    ap = os.path.join(ROOT, f.replace("/", os.sep))
    if not os.path.isfile(ap):
        w("    -- %s 不存在" % f); continue
    t = open(ap, encoding="utf-8", errors="replace").read().lower()
    hit = [lib for lib in NET_LIBS if lib in t]
    w("    %s -> %s" % (f, ("!! 命中 %s" % hit) if hit else "OK 无网络库"))
w("")

# ---------- 5. Hilt @Binds 目标存在性 ----------
w("【5】Hilt @Binds 引用的实现类是否存在")
di_dir = os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep), "di")
all_classes = set()
for r, d, fs in os.walk(os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep))):
    for f in fs:
        if f.endswith(".kt"):
            try:
                t = open(os.path.join(r, f), encoding="utf-8", errors="replace").read()
            except Exception:
                continue
            for cm in re.finditer(r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:internal\s+|abstract\s+|open\s+|sealed\s+|data\s+|enum\s+)*class\s+(\w+)", t, re.M):
                all_classes.add(cm.group(1))
if os.path.isdir(di_dir):
    for f in sorted(os.listdir(di_dir)):
        if not f.endswith(".kt"): continue
        t = open(os.path.join(di_dir, f), encoding="utf-8", errors="replace").read()
        binds = re.findall(r"@Binds[\s\S]{0,300}?fun\s+\w+\s*\(\s*\w+\s*:\s*(\w+)", t)
        miss = [b for b in binds if b not in all_classes]
        w("    %-24s @Binds %d 个 %s" % (f, len(binds), ("!! 缺失目标 %s" % miss) if miss else "OK"))
else:
    w("    -- di/ 目录不存在")
w("")

# ---------- 6. Repository 接口实现完整性 ----------
w("【6】Repository 实现 vs 接口 方法覆盖")
iface_dir = os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep), "domain", "repository")
impl_dir  = os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep), "data", "repository")
def methods(path, kind):
    t = open(path, encoding="utf-8", errors="replace").read()
    # 去掉注释
    t = re.sub(r"//.*", "", t)
    t = re.sub(r"/\*[\s\S]*?\*/", "", t)
    out = set()
    if kind == "iface":
        for m in re.finditer(r"(?:suspend\s+)?fun\s+(\w+)\s*\(", t):
            out.add(m.group(1))
    else:
        for m in re.finditer(r"override\s+(?:suspend\s+)?fun\s+(\w+)\s*\(", t):
            out.add(m.group(1))
    out.discard("equals"); out.discard("hashCode"); out.discard("toString")
    return out
if os.path.isdir(iface_dir) and os.path.isdir(impl_dir):
    for f in sorted(os.listdir(iface_dir)):
        if not f.endswith(".kt"): continue
        base = f[:-3]
        ip = os.path.join(iface_dir, f)
        op = os.path.join(impl_dir, base + "Impl.kt")
        if not os.path.isfile(op):
            w("    -- %sImpl.kt 未创建（可能属他人任务）" % base); continue
        need = methods(ip, "iface")
        have = methods(op, "impl")
        miss = sorted(need - have)
        w("    %-28s 接口 %2d 方法 / 实现 %2d %s" % (
            base, len(need), len(have), ("!! 未实现 %s" % miss) if miss else "OK"))
else:
    w("    -- domain/repository 或 data/repository 目录不存在")
w("")

# ---------- 7. 内置动作条数 ----------
w("【7】内置动作库条数（要求 >=40）")
bip = os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep), "data", "preset", "BuiltInExercises.kt")
if os.path.isfile(bip):
    t = open(bip, encoding="utf-8", errors="replace").read()
    # 每个动作条目必定出现一次 ExerciseCategory.XXX，用它计数最稳
    n_cat = len(re.findall(r"ExerciseCategory\.\w+", t))
    n_ex  = len(re.findall(r"^\s*ex\s*\(", t, re.M))
    w("    动作条目数(按 ExerciseCategory.XXX 计) = %d" % n_cat)
    w("    ex(...) 辅助构造调用 = %d" % n_ex)
    if n_cat >= 40:
        w("    OK 满足 >=40 要求")
    else:
        w("    !! 不满足 >=40 要求（实际 %d）" % n_cat)
else:
    w("    !! BuiltInExercises.kt 不存在")
w("")

# ---------- 8. 占位符扫描 ----------
w("【8】占位符 / 未完成标记扫描")
PH = [r"\bTODO\b", r"\bFIXME\b", r"NotImplementedError", r"\bstub\b", r"待补", r"待实现", r"XXX"]
hits = {}
for r, d, fs in os.walk(os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep))):
    for f in fs:
        if not f.endswith(".kt"): continue
        ap = os.path.join(r, f)
        try: t = open(ap, encoding="utf-8", errors="replace").read()
        except Exception: continue
        for p in PH:
            for m in re.finditer(p, t, re.I):
                rel = os.path.relpath(ap, ROOT)
                hits.setdefault(rel, []).append(t[max(0,m.start()-30):m.end()+30].replace("\n"," "))
if hits:
    w("    !! 命中 %d 个文件:" % len(hits))
    for k in sorted(hits)[:40]:
        w("       %s  (%d 处) 例: %s" % (k, len(hits[k]), hits[k][0][:80]))
else:
    w("    OK 未发现占位符")
w("")

# ---------- 9. R.string 引用存在性 ----------
w("【9】R.string 引用 vs strings.xml 定义（无编译器环境下的关键检查）")
strxml = os.path.join(ROOT, RES_ROOT.replace("/", os.sep), "values", "strings.xml")
defined = set()
if os.path.isfile(strxml):
    t = open(strxml, encoding="utf-8", errors="replace").read()
    defined = set(re.findall(r'<string\s+name="([^"]+)"', t))
src_dirs = [os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep))]
for extra in ["app/src/test/java", "app/src/androidTest/java"]:
    src_dirs.append(os.path.join(ROOT, extra.replace("/", os.sep)))
used = {}
for sd in src_dirs:
    if not os.path.isdir(sd): continue
    for r, d, fs in os.walk(sd):
        for f in fs:
            if not f.endswith(".kt"): continue
            ap = os.path.join(r, f)
            t = open(ap, encoding="utf-8", errors="replace").read()
            t = re.sub(r"/\*[\s\S]*?\*/", "", t)
            t = re.sub(r"//.*", "", t)
            for m in re.finditer(r"R\.string\.(\w+)", t):
                used.setdefault(m.group(1), []).append(os.path.relpath(ap, ROOT))
miss_key = sorted(k for k in used if k not in defined)
w("    strings.xml 定义 %d 个 key；代码引用 %d 个 key" % (len(defined), len(used)))
if miss_key:
    w("    !! 引用但未定义 %d 个:" % len(miss_key))
    for k in miss_key[:40]:
        w("       R.string.%s  <- %s" % (k, used[k][0]))
else:
    w("    OK 全部有定义")
unused = sorted(k for k in defined if k not in used)
w("    (提示) 已定义但未被代码引用 %d 个: %s" % (len(unused), ", ".join(unused[:20])))
w("")

# ---------- 10. ui 层调用的 UseCase 是否存在 ----------
w("【10】ui 层调用的 UseCase 类是否已落盘")
uc_dir = os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep), "domain", "usecase")
defined_uc = set()
if os.path.isdir(uc_dir):
    for f in os.listdir(uc_dir):
        if f.endswith(".kt"): defined_uc.add(f[:-3])
ui_dir = os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep), "ui")
uc_ref, bad_uc = {}, []
if os.path.isdir(ui_dir):
    for r, d, fs in os.walk(ui_dir):
        for f in fs:
            if not f.endswith(".kt"): continue
            ap = os.path.join(r, f)
            t = open(ap, encoding="utf-8", errors="replace").read()
            t = re.sub(r"/\*[\s\S]*?\*/", "", t)
            t = re.sub(r"//.*", "", t)
            for m in re.finditer(r"(\w+UseCase)", t):
                uc_ref.setdefault(m.group(1), []).append(os.path.relpath(ap, ROOT))
for k in sorted(uc_ref):
    if k not in defined_uc:
        bad_uc.append((k, uc_ref[k][0]))
w("    domain/usecase 已落盘 %d 个类；ui 层引用 %d 个" % (len(defined_uc), len(uc_ref)))
if bad_uc:
    w("    !! ui 引用了未落盘的用例 %d 个:" % len(bad_uc))
    for k, where in bad_uc:
        w("       %s  <- %s" % (k, where))
else:
    w("    OK 全部存在")
w("")

# ---------- 11~13 需要遍历全部 .kt（main + test + androidTest） ----------
ALL_KT = []
for sd in [os.path.join(ROOT, KOTLIN_ROOT.replace("/", os.sep)),
           os.path.join(ROOT, "app", "src", "test", "java"),
           os.path.join(ROOT, "app", "src", "androidTest", "java")]:
    if not os.path.isdir(sd): continue
    for r, d, fs in os.walk(sd):
        for f in fs:
            if f.endswith(".kt"):
                ALL_KT.append(os.path.join(r, f))

def strip_comments(t):
    t = re.sub(r"/\*[\s\S]*?\*/", "", t)
    t = re.sub(r"//.*", "", t)
    return t

# ---------- 11. 委托属性智能转换（Kotlin 规则级编译错误） ----------
# Kotlin 官方规范：val 局部变量智能转换恒成立，但 local delegated properties 例外。
# `val x by ...` 之后 `x.p != null -> { ... x.p ... }` 无法收窄类型 -> 编译失败。
w("【11】委托属性智能转换风险（编译错误扫描）")
sc_hits = []
for ap in ALL_KT:
    t = strip_comments(open(ap, encoding="utf-8", errors="replace").read())
    deleg = set(re.findall(r"\b(?:val|var)\s+(\w+)\s+by\s+", t))
    for dv in deleg:
        for m in re.finditer(r"\b%s\.(\w+)\s*!=\s*null\s*->" % re.escape(dv), t):
            sc_hits.append((os.path.relpath(ap, ROOT), dv, m.group(1)))
if sc_hits:
    w("    !! %d 处：在委托属性上做智能转换 -> 编译失败" % len(sc_hits))
    for rel, dv, prop in sorted(set(sc_hits)):
        w("       %s  ::  %s.%s != null ->" % (rel, dv, prop))
else:
    w("    OK 未发现委托属性上的智能转换")
w("")

# ---------- 12. 格式化占位符 vs 实参类型（运行时崩溃） ----------
# snackbarArgs 是 List<String>；若 %d 占位符走这条链路，String.format("%d","3") 抛
# IllegalFormatConversionException。
w("【12】格式化占位符 vs 实参类型（运行时崩溃扫描）")
pct_d = {}
if os.path.isfile(strxml):
    st = open(strxml, encoding="utf-8", errors="replace").read()
    for m in re.finditer(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', st, re.S):
        if re.search(r"%\d*\$?d", m.group(2)):
            pct_d[m.group(1)] = m.group(2).strip()
fmt_hits, generic_sites = [], []
for ap in ALL_KT:
    t = strip_comments(open(ap, encoding="utf-8", errors="replace").read())
    if "toTypedArray()" in t:
        generic_sites.append(os.path.relpath(ap, ROOT))
    if "snackbarArgs" in t or "toTypedArray()" in t:
        for k in set(re.findall(r"R\.string\.(\w+)", t)):
            if k in pct_d:
                fmt_hits.append((os.path.relpath(ap, ROOT), k, pct_d[k]))
w("    含整数占位符(%%d)的 key 共 %d 个: %s" % (len(pct_d), ", ".join(sorted(pct_d))))
w("    动态实参渲染点(toTypedArray) 出现在 %d 个文件" % len(set(generic_sites)))
if fmt_hits:
    w("    !! %d 处风险：字符串列表实参链路里出现整数占位符 -> 运行时崩溃" % len(set(fmt_hits)))
    for rel, k, v in sorted(set(fmt_hits)):
        w("       %s   R.string.%s = \"%s\"" % (rel, k, v))
else:
    w("    OK 未发现整数占位符与字符串列表实参混用")
w("")

# ---------- 13. 常用类型 import 缺失（Unresolved reference） ----------
w("【13】常用类型 import 缺失扫描（Unresolved reference）")
TYPE_IMPORTS = {
    "Color":      "androidx.compose.ui.graphics.Color",
    "Offset":     "androidx.compose.ui.geometry.Offset",
    "Size":       "androidx.compose.ui.geometry.Size",
    "LocalDate":  "kotlinx.datetime.LocalDate",
    "Instant":    "kotlinx.datetime.Instant",
    "Uri":        "android.net.Uri",
}
imp_hits = []
for ap in ALL_KT:
    t = strip_comments(open(ap, encoding="utf-8", errors="replace").read())
    pkgm = re.search(r"^\s*package\s+([\w.]+)", t, re.M)
    pkg = pkgm.group(1) if pkgm else ""
    imports = set(re.findall(r"^\s*import\s+([\w.]+)", t, re.M))
    for typ, full in TYPE_IMPORTS.items():
        owner_pkg = full.rsplit(".", 1)[0]
        if pkg == owner_pkg:            continue
        if full in imports:             continue
        if owner_pkg + ".*" in imports: continue
        if re.search(r"(?<![\w.])%s(?![\w])" % typ, t):
            imp_hits.append((os.path.relpath(ap, ROOT), typ, full))
if imp_hits:
    w("    !! %d 处：用到类型但缺 import" % len(imp_hits))
    for rel, typ, full in sorted(set(imp_hits)):
        w("       %s   使用 %s，缺 import %s" % (rel, typ, full))
else:
    w("    OK 未发现常用类型缺 import")
w("")

# ---------- 14. Manifest / 主题引用的资源是否存在（资源链接失败） ----------
w("【14】Manifest 与主题引用的资源是否存在（资源链接失败扫描）")
res_root = os.path.join(ROOT, RES_ROOT.replace("/", os.sep))
defined_res = {}
def add_res(t, n):
    defined_res.setdefault(t, set()).add(n)

values_dir = os.path.join(res_root, "values")
if os.path.isdir(values_dir):
    for f in os.listdir(values_dir):
        if not f.endswith(".xml"): continue
        t = open(os.path.join(values_dir, f), encoding="utf-8", errors="replace").read()
        for m in re.finditer(r'<(color|string|dimen|bool|integer|style)\s+name="([^"]+)"', t):
            add_res(m.group(1), m.group(2))

FILE_TYPES = ("drawable", "mipmap", "xml", "layout", "raw", "anim", "menu")
if os.path.isdir(res_root):
    for d in os.listdir(res_root):
        base = re.sub(r"-.*$", "", d)
        if base not in FILE_TYPES: continue
        p = os.path.join(res_root, d)
        if not os.path.isdir(p): continue
        for f in os.listdir(p):
            add_res(base, os.path.splitext(f)[0])

scan_files = [mf]
for r, d, fs in os.walk(res_root):
    for f in fs:
        if f.endswith(".xml"):
            scan_files.append(os.path.join(r, f))
res_refs = []
for ap in scan_files:
    if not ap or not os.path.isfile(ap): continue
    t = open(ap, encoding="utf-8", errors="replace").read()
    for m in re.finditer(r"@(drawable|mipmap|xml|layout|raw|anim|menu|color|string|dimen|bool|integer|style)/([\w.]+)", t):
        res_refs.append((os.path.relpath(ap, ROOT), m.group(1), m.group(2)))
res_missing = set()
for where, typ, name in res_refs:
    if name not in defined_res.get(typ, set()):
        res_missing.add((where, typ, name))
if res_missing:
    w("    !! %d 处引用但未定义:" % len(res_missing))
    for where, typ, name in sorted(res_missing)[:40]:
        w("       @%s/%s  <- %s" % (typ, name, where))
else:
    w("    OK 全部有定义（共检查 %d 处引用）" % len(res_refs))
w("")

# ---------- 15. 汇总 ----------
w("=" * 78)
w("汇总：落盘 %d/%d ｜ 缺失 %d ｜ 空文件 %d ｜ package 不一致 %d ｜ 占位符文件 %d"
  % (len(present), len(expected), len(missing), len(empty), len(bad_pkg), len(hits)))
w("      缺失字符串 key %d ｜ 缺失用例 %d ｜ 委托属性智能转换 %d ｜ 占位符类型错配 %d ｜ 缺 import %d"
  % (len(miss_key), len(bad_uc), len(set(sc_hits)), len(set(fmt_hits)), len(set(imp_hits))))
w("      资源引用缺失 %d" % len(res_missing))
w("=" * 78)

report = "\n".join(lines)
open(OUT, "w", encoding="utf-8").write(report)
print(report)
