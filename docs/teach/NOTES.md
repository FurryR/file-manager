# Notes

## 用户偏好

- 用中文提问，回答也用中文；代码标识符、文件路径、错误串保持原文。
- 偏好先给结论再给理由（用户的全局 AGENTS.md 要求 caveman 风格：答先、去装饰）。
- 不喜欢被反复追问选项：2026-11-06 第一次设置工作区时，询问被直接关闭。
  以后默认选一个合理方案并注明“可一键改”，只在真正阻塞时提问。

## 教学假设

- 用户是这个仓库的作者或主要维护者，熟悉 Kotlin 与 Compose，但可能没有系统
  梳理过 `:core` 的分层，因此课程从“路由契约”而不是“什么是文件系统”开始。
- 已知风险点（待用户确认是否要成为课程主题）：
  - `Vfs` 的 `runBlocking` 桥 vs `VFolder`/`VFile` 的 suspend API。
  - `Vfs.node()` 与 `NodeService.resolve()` 职责重叠。
  - `Folder.innerPathFor()` 每次重建 `ResolvedProvider`。

## 工作区

- 位置：`docs/teach/`（在仓库内，可提交；想移到仓库外只需 `mv`）。
- 课程与参考文档用 HTML；状态文件用 Markdown。
