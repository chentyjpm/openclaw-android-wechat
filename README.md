# OpenClawBot Android

## 项目简介

OpenClawBot Android 是一个基于 Termux 的移动端自动化方案：

- 在 Android 设备内通过 **Termux** 运行 **OpenClaw** 服务
- 通过 **无障碍服务** 读取界面结构、识别控件和页面变化
- 通过 **输入法服务（IME）** 执行输入、按键和交互动作
- 配合前台服务与悬浮窗，实现状态可视化与快速控制

项目目标是将手机侧能力封装为可持续运行的 Bot 执行环境，让 OpenClaw 在安卓设备上完成消息处理、界面操作和任务调度。

## 工作流程

1. App 启动后准备运行环境，将启动脚本和插件资源同步到 Termux 用户目录。
2. 在 Termux 中启动 OpenClaw 主进程，并加载微信相关插件。
3. 无障碍服务持续监听当前页面状态，采集 UI 信息。
4. 输入法服务负责输入、焦点切换、发送等可控交互动作。
5. 前台服务维持进程常驻，降低系统回收概率。

## 核心模块

- `app/`：Android 主应用与界面入口，包含启动脚本和插件资源打包。
- `wx-server/`：无障碍能力、任务桥接、状态上报，以及与 OpenClaw 侧的数据交互。
- `app/src/main/res/raw/startup_openclaw.sh`：Termux 侧启动与初始化脚本。

## 方案优势

- 在 Android 真机上部署 OpenClaw Bot，支持独立运行和物理隔离。
- 可对接国内平台与 API，便于在本地网络环境中落地。
- 可结合无障碍服务与 IME 对聊天软件进行自动化交互。

## 风险与合规

- 本项目依赖系统级权限（无障碍、输入法、前台服务）。
- 聊天软件自动化（尤其微信）存在账号和合规风险，请在合法、可控场景中使用并自行评估责任边界。

## TODO

- 支持调取更多手机硬件能力。
- 支持调用摄像头拍照。

## 参考项目与致谢

- https://github.com/termux/termux-app
- https://github.com/hillerliao/install-openclaw-on-termux
- https://github.com/Tencent/ncnn
- https://github.com/nihui/ncnn-android-ppocrv5
