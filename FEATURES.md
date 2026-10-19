# 文件管理、编辑器与容器工具使用说明

这些功能共用文件管理器的 Provider、容器 daemon 和 Hub。远程文件、压缩包条目与本地文件通过同一套文件操作接口打开；容器工具通过应用管理的进程与连接运行。

本文描述当前实现和使用路径，不表示已完成 Android 实机、真实远程服务器、语言服务器或 GPU 驱动的联调。构建成功、进程启动成功与实际功能验证是不同层面的结果。

## 入口

| 功能 | 入口 |
| --- | --- |
| AList、SSH 连接 | 侧栏 → 远程连接 |
| 文本与代码编辑器 | 点击文本/代码文件；长按菜单仍可选择系统「打开方式」 |
| 压缩包浏览 | 点击支持的压缩包 |
| 创建 ZIP、解压 | 文件长按菜单 → 创建压缩包 / 解压；创建时可使用当前多选项 |
| 本地 FTP 服务器 | 侧栏 → 工具 → FTP |
| 容器服务 | 工具 → 容器服务；也可点击容器服务通知 |
| Wayland 桌面 | 工具 → Wayland 桌面 |
| VirGL | 工具 → VirGL |

## AList 与 SSH Provider

在「远程连接」中新建连接，填写显示名称、服务器地址和起始目录，使用「连接」进行读取验证后进入文件列表，也可以先保存配置。连接可编辑、删除和重新打开。

### AList

- 使用 AList v3 文件 API。填写 HTTP(S) 服务地址，以及令牌或服务器支持的账号、密码；受密码保护的目录另填目录密码。
- 支持目录列表、文件读取、上传、新建、重命名和删除。写入是否可用取决于 AList 返回的目录权限和后端存储能力。
- 文件列表按页读取；下载使用 AList 返回的文件地址。下载地址与 API 登录凭据分开处理。
- 内部位置形式为 `alist://连接ID/路径`。连接 ID 由应用生成，不需要将密码写进路径。

### SSH

- 填写主机、端口、用户名，可使用密码或导入私钥，并按需填写私钥口令。
- 首次连接会显示主机密钥算法和指纹，确认后保存；已保存主机密钥发生变化时阻止连接。
- 该 Provider 使用 SSH **exec channel**，不要求服务器启用 SFTP 子系统。服务器需提供正常的 POSIX shell，以及 `find`、`stat`、`cat`、`wc`、`mkdir`、`mv`、`rm` 等文件工具。
- 文件内容使用独立的二进制传输通道与长度校验，不把终端输出当成文件内容。上传先写临时文件，成功后再发布。
- 内部位置形式为 `ssh://连接ID/路径`。现有 SFTP Provider 仍独立保留。

这两类连接的凭据使用 Android Keystore 管理的密钥加密保存在应用的 `noBackupFilesDir`，不写入仓库或命令行参数。文件操作失败会返回权限、网络或服务器错误，不把失败伪装成空目录或保存成功。

## 原生文本编辑器与 LSP

文本和代码文件进入基于 Sora 的 Android 原生编辑器，不依赖 WebView。支持原生文本选择、语法着色、撤销/重做、查找/替换、大小写与正则搜索、跳转行、自动换行和字号调整。长按文件的「打开方式」保留系统应用选择器。

文本解码支持 UTF-8，以及带 BOM 的 UTF-8、UTF-16LE、UTF-16BE。无法按相应编码严格解码的内容不会静默替换成乱码；含二进制 NUL 的文件拒绝作为文本编辑。未修改的内容保留原字节；修改后保留原编码/BOM，并使用文件的主要换行类型。状态栏显示编码、换行类型与光标位置。

保存前检查文件是否被外部修改，冲突时由用户决定是否覆盖。文件通过同目录临时项、备份与重命名发布；失败时保留必要的恢复文件，并在错误信息中给出位置。本地/容器文件在发布前保留原 uid/gid、权限和可读取的扩展属性；不能保留时拒绝覆盖。不同远程或文档 Provider 的能力仍受其服务器/API 限制。

### 连接容器内语言服务器

1. 在目标容器内安装所需语言服务器及其运行时，例如已经配置好的 `pylsp` 或 `clangd`。
2. 打开代码文件，在编辑器「更多」菜单进入 LSP 设置。
3. 选择容器，填写服务器启动命令、容器内工作区绝对路径、当前文档在容器中对应的绝对路径。
4. 连接后可使用补全、诊断列表及诊断位置跳转；需要时从菜单主动断开。

服务器经 `ExecService.spawnPipe` 启动，stdin/stdout/stderr 分离，LSP 的 `Content-Length` 字节流不经过 PTY，因此不会插入回显或转换换行。编辑器关闭或连接取消时会回收其专属进程。

容器路径文件可以自动填入容器名和文档路径。其他 Provider 的文件必须由用户提供服务器能够理解的文档路径；界面不会自动复制整个远程工程到容器。语言服务器与工程依赖由用户在容器中安装，应用不附带所有语言服务器，也不假设所有服务器都支持相同能力。

## 压缩包浏览、修改与解压

| 格式 | 浏览与读取条目 | 修改条目 | 解压 |
| --- | --- | --- | --- |
| ZIP，以及 ZIP 容器形式的 APK/JAR | 支持 | 支持非分卷 ZIP | 支持 |
| TAR、tar.gz/tgz、tar.bz2/tbz2、tar.xz/txz | 支持 | 只读，先解压再修改 | 支持 |
| 7z | 支持 | 只读，先解压再修改 | 支持 |

点击压缩包后进入 `archive://会话ID/路径` 虚拟目录。ZIP 可以通过文件管理操作新建、删除、重命名条目；文本条目可在原生编辑器中编辑并写回 ZIP。压缩包来源可以是本地、容器或可读写的远程 Provider。写回仍要求来源 Provider 支持必要的上传与重命名操作。

创建压缩包时选择目标 ZIP 路径；目标已存在时不直接覆盖。可以设置 ZIP 密码，创建的加密文件使用 AES。打开加密包时按界面提示输入密码；分卷 ZIP 不提供修改操作。修改 APK/JAR 的内容会影响其签名，应由相应的构建/签名工具重新处理。

解压时选择已经存在的目标目录，并明确选择是否覆盖同名普通文件。解压预检查拒绝危险的条目路径，并拒绝穿过目标中的符号链接。归档中的链接、特殊文件或不能读取的压缩数据会报告错误，不伪装成正常条目。解压失败时可能保留已经成功提取的项目，应依据错误信息处理。

同一来源压缩包的修改串行执行，且写回前重新检查来源摘要。另一程序改变了原包时，需要重新打开，而不是拿旧缓存覆盖新内容。只读条目缓存会在关闭文件源时清理。

## 本地 FTP 服务器

在「工具」中选择共享根目录、端口、用户名和密码，然后启动。端口范围为 `1024..65535`，默认 `2121`。可选择仅监听本机 `127.0.0.1`；否则界面显示可供同网段客户端使用的 IPv4 地址。

FTP 根目录映射到所选 Provider 路径。客户端只能按共享根内的相对路径操作；实现拒绝越出共享根的 `..` 和共享路径中的符号链接。实际读写权限由该 Provider 决定。

实现提供 UTF-8、被动模式 PASV/EPSV、目录列表/MLSD、下载、上传、新建目录、删除与同目录重命名，并支持下载断点续传；不支持上传断点续传。上传先接收到应用临时文件，再通过 Provider 保存，避免一开始传输就截断原文件。

服务器是用户主动启动的前台服务，运行时有通知，可以从工具页或通知停止。不会在设备开机时自行开启。FTP 使用普通 FTP 协议，不提供 FTPS/TLS；需要加密传输时使用相应的 SSH/SFTP 连接方式。

## Wayland 桌面与原生 RFB 查看器

「工具 → Wayland 桌面」有两种使用方式：

- **在容器内启动**：选择容器和 compositor 命令（默认 `labwc`），填写本机端口后启动。
- **连接已有 RFB 服务**：填写地址、端口和密码，连接已经运行的兼容 VNC/RFB 服务。

容器托管方式需要用户先安装 `labwc` 或兼容的 wlroots compositor，以及 `wayvnc`、其运行时依赖和要运行的桌面程序。应用不把缺失的容器软件包伪装成已安装。`wayvnc` 需要 compositor 提供相应的屏幕捕获和输入协议；仅有一个任意 Wayland socket 并不足够。

托管启动会创建独立的临时 `XDG_RUNTIME_DIR`，使用 headless 输出，并以 `WLR_RENDERER=pixman` 启动 compositor。Wayvnc 仅监听 `127.0.0.1`，本机托管连接不使用密码。查看器是 Android 原生绘制与输入实现，支持缩放/适配、平移、鼠标点击与滚轮、软键盘和常用特殊键；不使用 noVNC 网页。

手动连接支持 RFB 3.3/3.8 及经典 VNC 密码认证；未认证连接仅允许回环地址。VeNCrypt/TLS 等未实现的认证方式会报错。诊断启动问题时可以查看页面日志。

断开或关闭桌面页面会结束本页面创建的 compositor/wayvnc 进程组和专属临时目录；不会停止用户另外启动的桌面服务。需要让自定义桌面后台长期运行时，可用容器服务管理器管理它，再使用「连接已有服务」。

## systemd 风格的容器服务子集

在「工具 → 容器服务」中选择容器，可以查看服务状态、编辑服务单元、启用/禁用自启动、启动、停止、重启和查看日志。启用的服务随该容器 daemon 启动，不依附于某个终端窗口。关闭终端后容器继续运行；容器服务通知提供独立停止入口。

原名 `systemctl` 工具通过 Hub 控制同一个管理器：

```sh
systemctl list-units
systemctl enable --now example.service
systemctl status example.service
systemctl logs example.service
systemctl disable --now example.service

# 在宿主终端明确选择容器
systemctl --machine CONTAINER status example.service
```

支持 `Type=simple/exec/oneshot`、单个 `ExecStart`、`After/Requires/Wants`、环境与工作目录，以及 `Restart=no/on-failure/always` 等明确的子集。它不是完整 systemd，不提供 PID 1、cgroup、socket activation、journald 或 systemd 的 D-Bus 管理接口。完整字段、依赖语义与退出策略见 [容器服务说明](native/SERVICES.md)。

如果容器中已安装实际的 `dbus-daemon`，管理器默认启动系统总线与共享 root 会话总线，并向终端/服务/pipe 进程提供对应地址。没有安装 D-Bus 时会显示缺失或启动错误。Subreaper 负责回收被收养的孤儿；被 Tokio 和 PTY 管理的子进程保持各自的退出状态归属。

从侧栏或服务页面停止容器、删除容器、重命名容器之前，会先结束相关终端与 daemon。显式停止的容器会从前台服务的恢复名单移除。Android 强制停止应用仍会终止应用及其服务，不保证绕过系统生命周期限制。

## VirGL：宿主 renderer 与带 FD 的 Hub 桥

在「工具 → VirGL」中为目标容器开启开关。应用启动实际的 Android ARM64 `virgl_test_server`，容器内由 `file-manager-virgl.service` 启动 `virgl_bridge`。桥通过 Hub 请求宿主 renderer 连接，并双向转发字节与 `SCM_RIGHTS` 文件描述符，以支持共享缓冲区。

这是实际的宿主 EGL/GLES renderer 与 vtest 传输路径。容器仍需用户安装具有 **virpipe** 驱动的 Mesa 和所需 OpenGL 程序；应用不会自动替换容器的 Mesa 软件包。开启后为新的容器会话配置：

```text
GALLIUM_DRIVER=virpipe
VTEST_SOCKET_NAME=/run/file-manager/virgl.sock
LIBGL_ALWAYS_SOFTWARE=1
```

这里 `LIBGL_ALWAYS_SOFTWARE=1` 配合容器内的 virpipe 选择路径；实际宿主 renderer 仍使用 Android EGL/GLES。它本身不是硬件加速成功的证明。已经打开的 shell 不会被追溯修改环境，改变开关后可新开终端；页面可查看桥服务日志。

Wayland 托管桌面的 compositor 默认使用 pixman，这与桌面内 GL 应用通过 virpipe 使用宿主 renderer 是两个独立环节。设备驱动、Mesa 版本和应用图形接口是否兼容，需通过真实 GL 程序的 renderer 信息及画面验证。构建过程、依赖、补丁、协议和验证边界见 [VirGL renderer 说明](native/virgl/README.md)。

## 构建与打包

当前原生构建目标为 Android ARM64，NDK API 26。准备 JDK 17、Android SDK platform 35 与相应 Build Tools、Android NDK、Rust/Cargo、可用于构建主机的 `protoc`、Git、Python 3.12+。VirGL 构建需要联网获取固定版本源码与隔离安装的构建工具；来源和校验值记录在其 README 中。

在仓库根目录执行，例如在 Linux 上：

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk-r30
export PROTOC=/path/to/protoc

# Rust workspace + 自动调用 Python 构建真实 VirGL renderer
bash native/build-android-aarch64.sh

# proot 仍单独构建；当前该脚本使用 Linux NDK host toolchain
bash native/build-proot.sh

# 本仓库未跟踪 gradle/wrapper 目录，使用已安装的 Gradle 8.7
gradle :app:assembleDebug
```

如果已经自行准备 Gradle wrapper，也可使用 `./gradlew :app:assembleDebug` 或 Windows 的 `gradlew.bat :app:assembleDebug`。Windows 的 Rust/VirGL 主构建可从 Git Bash 调用 `build-android-aarch64.sh`；`PYTHON_BIN` 可以指定实际 Python 可执行文件。`build-proot.sh` 当前仍需要 Linux 构建环境，或与其固定源码和编译步骤一致的 Windows 适配；不能因为主脚本完成就遗漏 proot 产物。

也可以单独重建宿主 renderer：

```sh
python native/build-virgl.py --ndk /path/to/android-ndk-r30
```

完整 ARM64 APK 包含以下条目：

| APK 位置 | 用途 |
| --- | --- |
| `lib/arm64-v8a/libfiledaemon.so` | 文件、进程、pipe 与容器服务 daemon |
| `lib/arm64-v8a/libproot.so` | proot 容器运行程序 |
| `lib/arm64-v8a/libvirglserver.so` | Android 宿主 VirGL renderer |
| `lib/arm64-v8a/run0` | 提权/跨容器终端命令 |
| `lib/arm64-v8a/here` | 从终端定位并选中文件管理器条目 |
| `lib/arm64-v8a/agent` | 单文件 Rust TUI AI 对话程序 |
| `lib/arm64-v8a/systemctl` | 容器服务控制程序 |
| `lib/arm64-v8a/virgl_bridge` | 容器内 vtest/SCM_RIGHTS 桥 |

工具保留原名，通过 `app/src/main/executables/lib/arm64-v8a/` 的 Java resources 打包，避免 JNI 输入过滤器移除无 `.so` 后缀的可执行文件。daemon、proot 和宿主 renderer 通过 `jniLibs` 打包。当前可调试构建允许 Android 将原名工具提取到 `nativeLibraryDir`；改变构建类型的可调试标志后，需要重新确认原名提取行为。

普通 Kotlin 编译检查可使用 `gradle :app:compileDebugKotlin`；完整 native 构建和 `assembleDebug` 还需确认上述产物都存在。仓库不使用新增单元测试来替代人工行为检查。尚未进行的实机/外部服务验证不能由这些构建命令的成功推导出来。
