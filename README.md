# USB DAC Control App

NW-A105 / NW-ZX500 系列 Walkman 的 USB DAC 模式控制 App。

## 功能

- 一键开启/关闭 USB DAC 模式
- 保留 ADB / 低功耗 PCM 输出等高级选项
- 内置 `uac2_bridge` 守护进程（arm64 静态编译）
- 需要 Root 权限（KernelSU / Magisk）

## 构建

```bash
./gradlew assembleDebug
```

## 依赖

- 设备需刷入含 `CONFIG_USB_CONFIGFS_F_UAC2=y` 的内核
- 内核源码及完整项目参见 [A100_ZX500_USB-DAC](https://github.com/He0xD4C0/A100_ZX500_USB-DAC)
