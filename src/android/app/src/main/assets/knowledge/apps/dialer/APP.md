---
name: dialer
display: 拨号
description: 系统拨号器：拨号盘、通话记录、联系人、骚扰拦截
packages: ["com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.android.phone"]
aliases: ["Dialer", "dialer", "Phone", "phone", "拨号", "电话", "拨号盘"]
---

## 页面结构
- 页签常见为：拨号盘（数字键盘）、通话记录、联系人、收藏（部分 ROM 只保留部分，以实测为准）。
- 拨号盘底部是拨号键；长按 0 可输入 + 号；长按数字键可拨出绑定的快捷联系人（需先设置）。
- 通话记录条目：点号码直接回拨，右侧"i"或长按进详情（发短信、复制号码、加入黑名单、标记骚扰）。

## 常用入口
- 联系人搜索：联系人页签顶部搜索框。
- 通话录音、快速回复短信、骚扰拦截：右上角菜单 → 设置（AOSP 与厂商差异大）。
- 添加号码到联系人：拨号盘输入后选择"新建联系人"或"添加到现有联系人"。

## 易混点
- 各厂商拨号器包名不同：AOSP 是 com.android.dialer，Google Phone 是 com.google.android.dialer，三星是 com.samsung.android.dialer，小米等厂商把拨号并入 com.android.phone；具体以实测为准。
- 系统拨号器的"阻止号码/骚扰拦截"与第三方拦截 App 是两套黑名单。
- 无 SIM 卡或仅 eSIM 停用时拨号盘仍可开，但拨打会被拒。
- 拨号盘里的联系人联想来自本地联系人，没有联系人权限时不显示。

## 登录与安全页
- 拨号器无需登录；修改 SIM 卡 PIN、来电拦截规则会要求确认或系统权限。
