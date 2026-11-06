# Mission: 吃透 file-manager 的 VFS 设计

> 状态：**暂定**。用户被问及目标时未作答，此文档按“维护并扩展本项目”假设起草。
> 一次对话即可改写。

## Why

用户正在这个仓库上工作，需要在不破坏分层的前提下加 provider、加 renderer、
加文件操作。目标是让每一次改动都落在正确的那一层，而不是靠试错发现边界。

## Success looks like

- 给出任意路径字符串，能在 30 秒内说清楚它落到哪个 provider、provider 收到什么字符串。
- 新增一种后端时，能独立写出 `FileProvider` 实现并只改 `Providers.kt` 一处注册。
- 能判断一个需求该加 provider、service 还是 renderer，并说明为什么。
- 能指出 `:core` 现存入口的重复与阻塞桥，并提出收敛方案。

## Constraints

- 中文教学，术语保留英文原名（`Vfs`、`innerPath`、`ViewRef`）。
- 每课 10 分钟内可完成，单课只给一个可验证的收获。
- 不在仓库里跑单测（项目 TESTING POLICY 禁止）；验证靠编译与手工观察。
- 教学材料放 `docs/teach/`。

## Out of scope

- Android UI/Compose 细节（除非某课需要它解释 renderer 边界）。
- Rust daemon 与 protobuf 线协议内部实现。
- 用测试框架替代真实行为验证。
