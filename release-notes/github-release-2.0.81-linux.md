# OpenMinis-Linux 2.0.81-linux 发行说明

本版为 2.0.80 之后的补丁版，收录 1 个修复。

## 虚拟屏（vscreen）修复：常驻 Surface，注入输入不再落空（#193）

### 问题

真机实测复现：`input -d <display>` 注入的 tap/swipe/text 全部静默丢失，虚拟屏里的应用完全收不到操作。日志与代码走查定位到根因——虚拟屏的 `DisplayHandle` 生命周期是「attach → 使用 → detach」循环：每次截图都摘掉 Surface 换一个新的。Android 对没有 surface 的虚拟显示器会暂停其上的 Activity（屏幕休眠、丢输入焦点），detach 之后注入的输入事件全部落空。

### 修复

- **DisplayHandle 接口**：删除 attach/detach 循环语义，新增 `acquireLatestFrame(): Image?`——截图读取常驻 ImageReader 的最新帧，不再摘换 Surface
- **VirtualScreenManager / VirtualScreenCapture**：创建时 attach 一次、仅在 release 时 detach；截图走常驻帧读取
- **测试**：全部假句柄同步切换新接口，并新增用例钉死「截图读常驻帧、绝不摘 Surface」的契约（attach 恰好一次、release 前不 detach）

### 效果

虚拟屏上的应用保持唤醒与输入焦点，注入操作即时生效；截图路径也因省去 Surface 摘换而更稳定。
