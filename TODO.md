# APK 体积压缩调研（目标 < 30 MB）

调研对象：`app/build/outputs/apk/debug/app-debug.apk`（初始 34.27 MB，AGP 8.6.1，仅 arm64-v8a）。
初版只做只读调研；2026-11-19 已执行阶段 1 + 阶段 2、阶段 3 第 1/3 项，
实测 **debug 26.49 MB / release 16.96 MB（已签名）**（见文末行动计划）。以下数据均为初始实测（`unzip -l`、zlib 分级重压、`llvm-strip` + deflate 对比），结论保留作对比。

## 结论

需要减少约 4.2 MB。按收益排序：

1. `material-icons-extended` 是全包最大的死代码，约 5–7 MB。
2. AGP 用 deflate level 1 打包（实测条目大小与 zlib level 1 完全一致）；重新用 level 9 压缩可省 3.92 MB。
3. Rust 产物未 strip，符号表约 5.1 MB（未压缩），strip 后压缩体积省 0.83 MB。
4. `release` 当前 `isMinifyEnabled = false`，R8 完全未启用，预计还能省 4–8 MB。

只做 2 + 3 即可进入 30 MB；做 1 + 3 约 27.5 MB；加上 R8 可到 22–25 MB。

## 当前体积构成

| 组成 | 压缩后 |
|---|---|
| dex（18 个，未压缩 66.9 MB） | 20.43 MB |
| assets（DevTools 4.91 + xkb 0.70 + 许可 0.04 + 编辑器语法 0.01） | 5.67 MB |
| lib/arm64-v8a（9 个） | 5.79 MB |
| `resources.arsc`（STORE，未压缩） | 0.71 MB |
| res/（104 个文件） | 0.04 MB |
| `tables/`（jcodings，647 个文件） | 1.30 MB |
| 其他（META-INF、proto、kotlin builtins 等） | 0.33 MB |

dex 明细：`classes.dex` 11.65 MB / 44.70 MB 未压缩，占全部 dex 压缩体积的 57%；全包共 31,743 个类。

## 发现

### 1. material-icons-extended 是最大死代码

- `androidx.compose.material.icons` 共 22,045 个类，代码量 14.2 MB，占全部 dex 代码量（29.4 MB）的 48%。
- 其中 11,400 个图标类落在 `classes.dex`（占该 dex 类数的 63%）。
- 全项目只用到 11 个图标，且全部是 `Filled`：

  `Add`、`Close`、`ContentCopy`、`Description`、`ExpandLess`、`ExpandMore`、`Favorite`、`Refresh`、`SaveAlt`、`Share`、`Warning`

- 五个主题（filled/outlined/rounded/sharp/twotone）全部进包，实际一个都没用到后四种。
- 只依赖 `material-icons-core` 时，`ContentCopy`、`Description`、`ExpandLess`、`ExpandMore`、`SaveAlt` 不在 core 集合里，需本地写 5 个 `ImageVector`（或 vector drawable）。
- **复核（实现时）**：实际使用 71 个图标（58 个 `Filled` + 13 个 `AutoMirrored.Filled`），
  “11 个”是早期统计。全部已从 1.7.6 的 extended AAR 中提取路径数据，内联到
  `app/.../ui/icons/AppIcons.kt`；项目不再从 core/extended 引用图标。

预计收益：5–7 MB（压缩后）。

### 2. 打包压缩等级是 1

逐条目实测：APK 内所有 deflate 条目的 `compress_size` 与 `zlib.compress(data, 1)` 完全一致；level 9 时明显更小。

整包重压到 level 9：34.03 → 30.10 MB（-3.92 MB），主要来自：

| 条目 | 当前 | level 9 |
|---|---|---|
| 全部 dex | 20.43 MB | 17.72 MB |
| `resources.arsc` | 0.71 MB（STORE） | 0.16 MB |
| DevTools 主 bundle | 2.285 MB | 1.908 MB |
| `libwayland_compositor.so` | 1.735 MB | 1.559 MB |

注意：
- AGP 8.6 没有公开的压缩等级配置项，需在 `assembleRelease` 之后后处理：重写 zip（level 9 或 zopfli）→ `zipalign -p 4` → `apksigner sign`。
- `resources.arsc` 当前是 STORE。`targetSdk = 28`（< 30）允许压缩；升到 30+ 会因 mmap 要求禁止压缩，届时此项收益消失。
- zopfli 未安装，预计比 level 9 再省 ~1 MB，但构建时间明显增加。
- 重压后 30.10 MB 只是贴线，不建议单独作为方案。

### 3. Rust 产物带符号表

实测 `llvm-strip --strip-all` 前后 + deflate level 6：

| 文件 | 未压缩 | strip 后 | 压缩后 | 压缩后 strip |
|---|---|---|---|---|
| `agent` | 3,585,712 | 2,392,536 | 1,275,081 | 1,068,398 |
| `here` | 1,094,856 | 687,240 | 376,518 | 302,373 |
| `libfiledaemon.so` | 2,493,656 | 1,611,136 | 839,367 | 693,644 |
| `libwayland_compositor.so` | 5,351,616 | 3,209,048 | 1,567,330 | 1,247,565 |
| `run0` | 1,217,128 | 772,864 | 424,112 | 343,578 |
| `libeglshim.so` | 5,600 | 3,440 | 1,853 | 1,073 |

合计节省：0.83 MB（压缩后）。
`libproot.so`、`libpulsehost.so`、`libandroidx.graphics.path.so` 已无符号表，无需处理。
strip 不影响 `libeglshim.so` 的 SONAME 与 `DT_NEEDED`。副作用：Rust panic 栈丢符号名。

### 4. release 构建没有任何缩减

`app/build.gradle.kts`：

```kotlin
release {
    isDebuggable = true
    isMinifyEnabled = false
    proguardFiles(...)
}
```

- 无 R8、无 resource shrink，未用图标、未用 Compose/库代码、proto 生成类、jsch/commons-net 未用分支全部进包。
- 18 个 dex 也是未 shrink 的直接结果。
- 当前 CI 只构建 `assembleDebug`，所以发布的产物就是未优化包。
- `isDebuggable = true` 是刻意保留的：按 `AGENTS.md`，只有 debuggable 包才会把 `lib/<abi>/{run0,here,agent}` 抽取到 `nativeLibraryDir`；非 debuggable 不会抽取任意文件名。
- 需实测 debuggable 是否会让 R8 关闭部分优化；若影响明显，可关 debuggable，改为安装后从 assets 解压 `run0/here/agent` 到 `filesDir` 再 `chmod`。

### 5. 次要项

- `tables/` 1.30 MB 来自 `language-textmate` 的传递依赖 `org.jruby.jcodings:jcodings:1.0.58`；若语法高亮可退化，删掉 textmate 可省 1.3 MB 及少量代码。
- DevTools 4.91 MB：`bundle/` 4.03 MB、`core/i18n` 0.52 MB（en-US/ja/zh/zh-TW）、`Images` 0.28 MB、`models` 0.05 MB。只留 en-US + 中文可省 ~0.4 MB；整块移出可省 4.9 MB，但会丢功能。
- `assets/xkb` 0.70 MB：Wayland 必需，不建议动。
- `jniLibs.useLegacyPackaging = true` 让 .so 被压缩，利于 APK 体积，保持。
- `res/` 只有 104 个文件，图标 WebP 化收益几十 KB 量级，优先级低。
- 已只有 arm64-v8a，无 ABI 可再拆。

## 目标体积预估

| 组合 | 预计 |
|---|---|
| 现状 | 34.27 MB |
| 仅 level 9 重压 | 30.10 MB（贴线） |
| level 9 + strip | ~29.3 MB |
| 去图标 + strip | ~27.5 MB |
| 去图标 + R8 + strip | ~22–25 MB |
| 全部（含重压/zopfli） | ~20–23 MB |
| **实际（去图标 + strip + R8 shrink + 重压签名）** | **debug 26.49 MB / release 16.96 MB** |

## 行动计划

### 阶段 1：确定性收益（不改行为）

- [x] 移除 `androidx.compose.material:material-icons-extended`，改用 `material-icons-core`（仅保留传递依赖）。
- [x] 本地补齐缺失图标的 `ImageVector`：实际全部 71 个（58 `Filled` + 13 `AutoMirrored.Filled`）内联到
  `app/.../ui/icons/AppIcons.kt`，由 AAR 1.7.6 的编译产物反射导出，路径数据与 autoMirror 原样保留。
- [x] 确认图标可用性：不再从 core 引用任何图标，core 只作为 Compose 的传递依赖留在 classpath。
- [x] 给 Rust 产物加 strip：`native/Cargo.toml` 的 `[profile.release] strip = "symbols"`（workspace 内全部 crate 生效）。
- [x] 重新构建（`./gradlew clean :app:assembleDebug :app:assembleRelease`）：
  **debug 26.49 MB，release 18.92 MB**，均 < 30 MB。

### 阶段 2：正式 release 配置（需回归）

- [x] `release` 开 `isMinifyEnabled = true`、`isShrinkResources = true`。
- [x] 补 `proguard-rules.pro` keep 规则（实际落地）：
  - `com.termux.**`（ASM 按原类名注入，`TerminalBridge` 反射字段/方法）
  - `rikka.shizuku.**`（`Class.forName("rikka.shizuku.Shizuku")`）及 manifest 中的 `ShizukuProvider`
  - `io.furryr.file.proto.**`（protobuf-javalite 生成类）
  - JNI：`NativeCompositor`、`PulseNative`、`PulseService` 回调、`native <methods>`
  - manifest 入口：Application、Activity、各 Provider（含 `ForwardFileProvider`、`PickerDocumentsProvider`）
  - tm4e：`ObjectCloner` + `public *** clone()`（TextMate 主题经 `Class.getMethod("clone")` 克隆）
  - JSch、commons-net（按名实例化的协议/cipher/parser 类）
  - `-dontwarn`：JSch 的可选 BouncyCastle/JNA/GSS/slf4j、jcodings 的 log4j、tm4e 的 jdt 注解、`sun.security.x509`
- [x] 先 `-dontobfuscate` 跑通，再决定是否开混淆（体积收益主要来自 shrink，不来自混淆）：
  已显式写 `-dontobfuscate`，保持关闭。
- [x] 实测 `isDebuggable = true` 是否削弱 R8：会。AGP 警告
  “All code optimizations and obfuscation are disabled for debuggable builds”；
  shrink 仍然生效（debug 26.49 MB → release 18.92 MB）。目标已达成且
  `run0/here/agent` 仍依赖 debuggable 自动抽取，**保留 debuggable**，不改为 assets 解压。
- [ ] 手动回归矩阵（仓库禁止单测，必须手工验证）。**本轮未执行**（无设备），安装
  `app-release-unsigned.apk`（需先签名）或 debuggable release 后逐项验证：
  - 终端 / IME / 文本选择菜单（ASM 注入 + `com.termux.**` keep）
  - `run0`、`here`、`agent` 三个可执行文件启动（debuggable 抽取）
  - 容器创建、proot/chroot 启动、cgroup 回收
  - PulseAudio（`onSinkData`/`onSinkIdle`/`onMicState` 回调）、Wayland viewer（`onTextInputEnabled`）
  - SAF / 文档 / FTP / SFTP / SSH / AList / HTTP 各 provider（JSch/commons-net keep）
  - 编辑器保存（TextMate clone 反射）、DevTools tab、打包器协议、文件选择器
- [x] 确认 `AGENTS.md` 中“debuggable 才能抽取 run0/here/agent”的约束仍成立，并记录结论：
  成立；保留 `isDebuggable = true`，已写入 `AGENTS.md` NOTES。

### 阶段 3：可选保险

- [x] 加 release 后处理任务：level 9 重压 → `zipalign -p 4` → `apksigner sign`。
  实现：`scripts/postprocess-release.py` + `:app:postprocessReleaseApk`
  （`assembleRelease` 的 finalizer）。实测 **18,919,647 → 16,959,192 B**
  （−10.4%，省 1.96 MB）。签名来源优先级：`keystore.properties`（gitignore）→
  `ANDROID_KEYSTORE*` 环境变量 → debug keystore；nix 的 `ANDROID_USER_HOME`
  路径已适配。
- [ ] 评估删除 `language-textmate`（-1.3 MB）与裁剪 DevTools locale/Images 的可行性。
- [x] 评估 `resources.arsc` 压缩（仅 `targetSdk < 30` 可行，升 targetSdk 时需回退）。
  已**随 release 重压任务一起实施**：重压步骤把所有条目改为 deflate，`resources.arsc`
  从 STORE 462,928 B → 88,684 B（省 0.37 MB）。`targetSdk = 28` 允许压缩；
  将来升到 30+ 必须在 `scripts/postprocess-release.py` 中把 `resources.arsc`
  排除出重压（保持 STORE），否则安装时会因 mmap 要求失败。

## 风险

- R8 与 Termux ASM 注入、JNI 回调、Shizuku Provider、protobuf-javalite 的兼容性是最大不确定性，必须先跑手动矩阵。
- `isDebuggable` 与“可执行文件自动抽取”绑定，不能为了体积直接关掉而不改启动路径。
- 后处理重压会让构建流程多一次完整读写 + 重新签名；APK 对齐/签名顺序不能错。
- `resources.arsc` 压缩依赖 `targetSdk = 28`，未来升 targetSdk 会失效。
