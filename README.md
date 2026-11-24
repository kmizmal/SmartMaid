# SmartMaid · 智能女仆模组

> 一只能在 Minecraft 里替你干活的女仆：跟随、战斗、跳跃寻路、挖矿建造，还能听懂自然语言指令。

Minecraft **26.2 / Fabric** 模组。行为由**本地规则引擎主导**（安全优先、毫秒级响应），AI 作兜底增强；
女仆由 `/summonmaid` 召唤、唯一归属玩家，可与**桌面宠物 DeskPet** 联动 —— 女仆的 AI 对话 / 语音 / 气泡由桌宠提供。

## 相关仓库（下载看这里）

| 仓库                                                                                           | 是什么                                                              | 谁需要                             |
|------------------------------------------------------------------------------------------------|---------------------------------------------------------------------|------------------------------------|
| **[Multimodal-AI-Companion](https://github.com/oyxdsg/Multimodal-AI-Companion)**               | 桌面宠物 **DeskPet**（AI 对话 / 语音 / 气泡），**本模组的联动对象** | 想让女仆会聊天、会说话就**必须**装 |
| **[Multimodal-AI-Companion-Assets](https://github.com/oyxdsg/Multimodal-AI-Companion-Assets)** | 桌宠的完整动画素材包（Release 附件，解压到 `desktop-pet/assets/`）  | 想让桌宠动起来                     |
| **SmartMaid**（本仓库）                                                                        | 游戏内的智能女仆（Minecraft 26.2 Fabric 模组）                      | 想在游戏里指挥女仆干活             |

> 只想单机玩女仆、不接 AI 也可以：跟随 / 战斗 / 跳跃寻路 / 挖矿建造 / 任务 / 设置菜单**完全独立可用**，
> 只有 `/maidchat` 聊天栏对话与语音朗读需要桌宠在线。

## 快速开始

> [!TIP]
> 非开发者请直接到 release 或 action 下载编译好的mod
>
```bash
cd SmartMaid
gradlew.bat build
#  产物：SmartMaid/build/libs/smartmaid-<version>.jar

# 2) 放进 .minecraft/mods/（需已安装 Fabric Loader 0.18.4+ 与 Fabric API 0.161.0+26.2、Fabric Language Kotlin 1.14.2+kotlin.2.4.21 或满足元数据要求的版本，Java >=25）

# 3) 进游戏执行
/summonmaid
```

开发验收以 [当前迁移状态](SmartMaid/KOTLIN_MIGRATION_STATUS.md) 和 [Loom 操作卡](docs/superpowers/handoffs/2026-11-24-loom-test-runbook.md) 为准；下一阶段见 [开发计划](docs/superpowers/plans/2026-11-24-next-stage-development.md)。26.3 已完成独立构建/测试，游戏输入验证范围见状态文档。

## 目录

| 路径                    | 是什么                                                   |
|-------------------------|----------------------------------------------------------|
| `SmartMaid/`            | 模组工程本体（源码 / 资源 / 工具 / 开发文档）            |
| `SmartMaid/README.md`   | **完整文档** —— 功能特性、指令手册、架构、构建与工具脚本 |
| [`LICENSE`](LICENSE) / [`LICENSE-MIT-upstream.txt`](LICENSE-MIT-upstream.txt) / [`NOTICE.md`](NOTICE.md) | 上游 MIT、维护部分 GPLv3 及第三方声明 |

## 许可

根目录 `LICENSE` 现为 GPLv3 完整正文，与保留的 `GPL-3.0.txt` 一致。

1. `cb9dcaba7538ab018b01a339cd360e2f695b57aa` 及之前的上游 MIT 部分，继续保留原 [MIT 文本](LICENSE-MIT-upstream.txt) 和版权声明。
2. 自 2026 年 11 月 21 日起，[kmizmal](https://github.com/kmizmal) 维护部分以 [GNU GPL 第 3 版](LICENSE)（`GPL-3.0-only`）分发。
3. 第三方素材保留原许可。鲸鱼娘皮肤等素材由 [上善无形](https://space.bilibili.com/4456176) / [ZipZipPipe](https://space.bilibili.com/4168597) 授权，按 CC BY-NC-SA 4.0 分发，不得用于商业目的；Emotecraft 表情按其 GPL-3.0 条款分发。

> 各部分的适用许可和来源见 [`NOTICE.md`](NOTICE.md)。
