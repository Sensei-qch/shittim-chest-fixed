# 什厅之匣 · Shittim Chest v2.0

《蔚蓝档案》同人应用，非官方作品，仅供学习交流。

## 功能

- **内置 Linux 环境（实验性）**：基于 proot + Ubuntu 22.04 aarch64 rootfs，在应用私有目录提供可 `apt` 安装软件的 GNU/Linux 用户态，无需 root。终端输入 `linux init` 初始化、`apt update` / `apt install <包>` 装软件，未识别命令自动交给 Linux bash 执行。
- AI 对话：OpenAI 兼容接口、SSE 流式输出、ChatAnywhere 双线路自动切换
- 学生地图：9 张地图、点位与头像
- 桌面小组件：地图 / 阿洛娜 / 终端，可自由缩放、桌面输入、历史翻页（**实验性功能**）
- 半透明毛玻璃界面，地图切换使用下拉菜单

## 下载

| v2.0 · APK 发布页 |
| :--- |
| [https://github.com/Sensei-qch/shittim-chest-fixed/releases/tag/v2.0](https://github.com/Sensei-qch/shittim-chest-fixed/releases/tag/v2.0) |
| 内置 Linux 与桌面小组件均为**实验性功能**，不同设备 / 启动器表现可能不一致。 |

## 安装与使用

- **Linux 环境仅支持 arm64-v8a 设备**，Android 8.0+；APK 约 43 MB。
- 首次使用在「设置」里填自己的 API Key（ChatAnywhere / OpenAI / SiliconFlow 等）；不含任何密钥。
- 进入「终端」，输入 `linux init` 释放并配置 rootfs（需等待 1–2 分钟），完成后即可 `apt update`、`apt install vim` 等。`linux status` 查看状态，`! <命令>` 强制用 Linux 执行。
- 若安装时提示签名冲突，请先卸载设备上同包名的应用。

## 说明

Linux 环境为实验性集成，`apt` 首次安装需联网；图形桌面（VNC/noVNC）相关组件需自行在 rootfs 内安装配置。内置环境不替代 Termux，功能以稳定版系统终端为准。

## 源码

`src/` 是 Java 源码（含 `LinuxEnv.java` 环境引擎），`widget-src/` 是 Android 清单与资源，`www-src/` 是前端。

## 声明

所有素材归 Nexon、Yostar 及原作者所有，素材版权归各自权利人。如涉侵权，请在 [Issues](https://github.com/Sensei-qch/shittim-chest-fixed/issues) 留言告知，我会及时处理。
