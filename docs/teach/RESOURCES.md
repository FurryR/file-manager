# VFS 设计 Resources

## Knowledge

- [Linux kernel docs: Overview of the Linux Virtual File System](https://docs.kernel.org/filesystems/vfs.html)
  内核 VFS 的原始设计：superblock / inode / dentry / file 四对象如何把多种
  文件系统统一成一个目录树。用于：理解“对象层 + 后端层”为何要分开，
  以及本项目的 `VFolder`/`VFile`/`FileProvider` 对应哪一层。
- [IntelliJ Platform: Virtual File System](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html)
  与本项目最接近的工业级先例：`VirtualFile` 对象、按协议分派的
  `VirtualFileSystem`、以及“路径字符串只在边界出现”的纪律。用于：
  `VNode`/`NodeService` 设计的对照，以及判断什么时候该加 service 而不是 provider。
- [Apache Commons VFS](https://commons.apache.org/proper/commons-vfs/)
  经典的 scheme → `FileProvider` 路由实现（`zip:`、`sftp:`、`http:` 都是
  provider）。用于：对比“嵌套 scheme”方案，理解本项目为什么改用扁平
  `ViewRef` 而不是 `zip:zip:file:` 这种复合 URI。
- [Go standard library: `io/fs`](https://pkg.go.dev/io/fs)
  极简文件系统抽象：`FS`、`File`、`ReadDirFS` 等可选能力接口。用于：
  讨论能力集（本项目的 `ProviderFeature` / `DirectoryCapabilities`）时
  的“小接口 + 可选能力”参照。
- [Kotlin docs: Sealed classes and interfaces](https://kotlinlang.org/docs/sealed-classes.html)
  `ViewRef` / `NodeRef` 用 sealed interface 表达封闭变体的语言依据。用于：
  解释为什么 `ViewRef.Url`、`ViewRef.Session` 能安全地穷举，而不再需要
  `Nested` 递归类型。

## Wisdom (Communities)

- [r/Kotlin](https://www.reddit.com/r/Kotlin/)
  讨论 Kotlin API 分层、sealed 类型建模。用于：模块边界与 API 形态的
  第二意见。
- [Kotlin Slack](https://kotlinlang.slack.com/)（`#library-development`、`#coroutines`）
  库作者聚集地。用于：`Flow` + `suspend` + `Result` 组合的惯用法争议。
- [Android Platform X (GitHub: `android/platform_frameworks_base`)](https://github.com/aosp-mirror/platform_frameworks_base)
  不是论坛，但对 `DocumentsProvider`/SAF 的实现有最权威的读法。用于：
  核对 SAF 语义而不是看二手博客。

## Gaps

- 没有找到专门讲“扁平视图引用 vs 嵌套 URI scheme”取舍的高质量公开文章；
  这一部分目前只能靠本仓库的 `VNode.kt` 注释与 ADR 式推理补齐。
- 缺少 Android SAF 官方文档的可离线镜像（`developer.android.com` 在本机
  网络下取不到，HTTP 000）。
