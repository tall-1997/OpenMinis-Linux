---
name: camera
display: 相机
description: 系统相机：拍照、录像、模式切换（各厂商差异大）
packages: ["com.android.camera2", "com.android.camera", "com.google.android.GoogleCamera", "com.huawei.camera", "com.oppo.camera", "com.vivo.camera"]
aliases: ["Camera", "camera", "相机", "拍照"]
---

## 页面结构
- 取景器占主体；顶部横排是闪光灯、HDR/动态照片、画幅比例、设置齿轮（顺序因机型而异）。
- 底部是模式条（人像、夜景、录像、专业、更多）与快门键；多数机型左右滑动在拍照/录像间切换。
- 相册入口：左下角圆形缩略图，点开是最近照片。

## 常用入口
- 变焦：1x 附近的倍率按钮或双指捏合。
- 前后摄切换：取景器右下或底部的旋转图标。
- 设置：闪光默认值、快门声音、水印、网格线在相机设置里。

## 易混点
- 各厂商相机包名差异大：AOSP 是 com.android.camera2，Pixel 是 com.google.android.GoogleCamera，小米是 com.android.camera，OPPO/vivo/华为各有自己的包；能力（夜景、长焦、水印）也差异大，以实测为准。
- "更多"页里才有人像/专业/全景等模式，不在主模式条上。
- 双击电源键或长按音量下键快速启动相机，需要系统设置里开启对应快捷手势。
- 授权相机权限被拒时应用可能直接闪退或黑屏，先到应用信息里检查权限。

## 登录与安全页
- 相机无需登录；部分品牌的云相册、水印、跨设备能力绑定厂商账号，首次使用会要求登录。
