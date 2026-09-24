# OpenMinis-Linux 1.36.51-linux

- versionCode **104**
- 基础包已齐时仍会重试失败的 Node 安装；轻量 `minis-dev-setup` 不再多装 pip 和 git-lfs。详见 `docs/github-release-1.36.51-linux.md`。

---

# OpenMinis-Linux 1.36.50-linux

- versionCode **103**
- 目录没给模态的视频/生图模型不再被缺省文本挡住；RAW SSE 仅 VERBOSE 打印；复制会话会拷走当前工作区。详见 `docs/github-release-1.36.50-linux.md`。

---

# OpenMinis-Linux 1.36.49-linux

- versionCode **102**
- 多智能体下的工具限额页重新显示返回箭头。导航已经把返回回调传进来了，页面却写成不显示，只能靠系统返回。详见 `docs/github-release-1.36.49-linux.md`。

---

# OpenMinis-Linux 1.36.48-linux

- versionCode **101**
- 软件源页中文界面补上中科大、上海交大和淘宝 npm。以前这三条没进对照表，仍显示 USTC、SJTU、npmmirror。详见 `docs/github-release-1.36.48-linux.md`。

---

# OpenMinis-Linux 1.36.47-linux

- versionCode **100**
- explore / plan 的只读 shell 不再放过 `2>文件`、`1>>文件` 这类带描述符的重定向，引号里的 `>` 和 `2>&1` 仍放行。`sh -c` 和 `$(...)` 里的写入也会被拒绝。详见 `docs/github-release-1.36.47-linux.md`。

---

# OpenMinis-Linux 1.36.46-linux

- versionCode **99**
- 不折叠时，段尾换行不再让译文写不回去。相同正文仍按出现次序落盘。详见 `docs/github-release-1.36.46-linux.md`。

---

# OpenMinis-Linux 1.36.45-linux

- versionCode **98**
- 不折叠时，翻译某一段只写回这一段。两段正文相同也不会把后一段的译文写到前一段上。详见 `docs/github-release-1.36.45-linux.md`。

---

# OpenMinis-Linux 1.36.44-linux

- versionCode **97**
- 高刷新率只请求当前分辨率下最快的显示模式，不再同时写刷新率，避免被系统盖回 60Hz。关闭时两种窗口参数都会清掉。视频请求没传 `mode` 时结果不再谎报 `std`；传了的 mode 仍只用于这一次。详见 `docs/github-release-1.36.44-linux.md`。

---

# OpenMinis-Linux 1.36.43-linux

- versionCode **96**
- 折叠模式下最后一个翻译按钮翻译整段可见回复，写回不删工具卡片，重新打开也不会把旧正文叠在译文上面。`cronjob` 重试返回已有任务的 id，删除支持唯一前缀并回读确认。详见 `docs/github-release-1.36.43-linux.md`。

---

# OpenMinis-Linux 1.36.42-linux

- versionCode **95**
- 设置 → 翻译里可以翻译一段文字。页面和会话按钮共用已保存的目标语言和模型，结果不写入会话。详见 `docs/github-release-1.36.42-linux.md`。

---

# OpenMinis-Linux 1.36.41-linux

- versionCode **94**
- 会话翻译按钮直接用设置里的目标语言，不再弹出语言表。按钮贴在正文结束后的右下角，不再把整段左移。详见 `docs/github-release-1.36.41-linux.md`。

---

# OpenMinis-Linux 1.36.40-linux

- versionCode **93**
- 模型调用不再把自定义路径改写成图片或聊天接口。空输入返回退出码 2。视频生成先走 `/v1/videos`。
- 混合命令只失败越界的那一段。定时任务删除会落盘并确认。通知清空默认只清本应用。详见 `docs/github-release-1.36.40-linux.md`。

---

# OpenMinis-Linux 1.36.39-linux

- versionCode **92**
- 权限弹窗不再按两分钟挂住后台线程。日历、定位、通知、相册、麦克风都在 15 秒内给出结果。
- 浏览器、无障碍、语音听写、定位和视频生成按各自真实时长等待，不再一律 20 秒被掐掉。
- 屏幕使用时间改用前台切换事件。本地识图先缩小长边，避免大图撑爆识别器。详见 `docs/github-release-1.36.39-linux.md`。

---

# OpenMinis-Linux 1.36.38-linux

- versionCode **91**
- 前台通知不再降到最低重要性，也不再锁屏隐藏。MIUI 状态栏和灵动岛仍能看到任务状态。
- 计时交给系统计时器。工具切换最多 1.5 秒更新一次，纯文字状态最多 30 秒一次，避免灵动岛反复 inflate 把 SystemUI 撑崩。
- 已安装用户会一次性把被降级的通知渠道恢复为低重要性，之后不再改用户自己的设置。详见 `docs/github-release-1.36.38-linux.md`。

---

# OpenMinis-Linux 1.36.37-linux

- versionCode **90**
- 设置一级页去掉多余返回箭头；MCP 工具改为二级页，开关仍会拒绝真实调用。
- 刷新模型会先清掉失效条目并清理悬空引用；失败标记保留到下次成功。
- 镜像名称和地区在中文界面不再显示英文。详见 `docs/github-release-1.36.37-linux.md`。

---

# OpenMinis-Linux 1.36.36-linux

- versionCode **89**
- 主页菜单收进设置，独立翻译页，MCP 工具开关会拦截真实调用。升级不再因重复加列失败。详见 `docs/github-release-1.36.36-linux.md`。

---

# OpenMinis-Linux 1.36.34-linux

- versionCode **87**
- 长文避开键盘、启动图标保留 R 角、分段翻译和翻译模型入口、自定义图片/视频路径。详见 `docs/github-release-1.36.34-linux.md`。

---

# OpenMinis-Linux 1.36.28-linux

- versionCode **81**
- 环境检出报告里确认的沙箱、设备信息和子代理问题。详见 `docs/github-release-1.36.28-linux.md`。

---

# OpenMinis-Linux 1.36.27-linux

- versionCode **80**
- 去掉权限模式和工具上限的重复入口。详见 `docs/github-release-1.36.27-linux.md`。

---

# OpenMinis-Linux 1.36.26-linux

- versionCode **79**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版（1.36.26-linux，2026-09-23）

相对 1.36.25-linux。搜索后端、世界书、显示正则、本地 OCR、屏幕使用时间和独立翻译页。详见 `docs/github-release-1.36.26-linux.md`。

---

# OpenMinis-Linux 1.36.24-linux

- versionCode **77**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版（1.36.24-linux，2026-09-23）

相对 1.36.23-linux。这一版修的是手机改时区后，客户机里不看 `TZ` 的程序仍停在启动时的时区。

### 时区

系统时区变化时，以前只更新新 shell 会继承的 `TZ`，并给已经在跑的 shell 执行 `export TZ=...`。客户机 `/etc/localtime` 和 `/etc/timezone` 仍指向开机时的时区。`date`、Python `datetime` 在环境里没有 `TZ` 时读这个链接，所以显示旧偏移。现在同一次广播会把链接改到手机当前时区。时区数据不在 rootfs 里时保持 UTC，不写一条指向不存在文件的链接。

PRoot 还没启动时，这次广播仍然什么都不做。下次启动会按当时的手机时区写链接。

### 说明

`LINUX.md` 不再写成这个仓库还有 iOS 工程。本仓库没有 iOS 工程，Ubuntu 客户机只用于 Android。

---

# OpenMinis-Linux 1.36.23-linux

- versionCode **76**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版（1.36.23-linux，2026-09-23）

相对 1.36.22-linux。这一版修的是从更低版本升上来会丢数据、装不上包，或把一次失败记成永久状态的问题。

### 升级不覆盖已有数据

跳过 1.36.13、直接升到当前版的设备没有 `force_overwrite_1_36_13`。以前第一次启动会把 `SOUL.md` 整文件换成出厂人格，用户改过的人设回不来。现在只补标记，不改已有文件。仍是出厂四行模板的，继续由原来的模板升级替换。

技能开关以前只写在 `skills.db` 的 `is_enabled`。新逻辑把空的偏好集合当成「用户没关过」，启动时会把关掉的技能全部重新打开。第一次升级会把数据库里已关闭的 id 抄进偏好，之后才套用「默认开启」。

旧版 Ubuntu 根文件系统只写了 `.arch`，没有 `.distro`。当前版把「没装过」理解成半成品，会删掉整个 `ubuntu-rootfs`，包括 `/root` 和已经装好的软件包。架构匹配且 `usr/bin` 或 `bin` 还在时，补上 `ubuntu-noble` 标记，不再重装。空目录加一个 `.arch` 仍视为半成品。

`node-seed.attempted` 以前写在 `apt` 返回之前。升级后第一次安装如果撞上锁或网络失败，标记已经在了，以后再也不会重试。现在只有安装成功，或者仓库里确实没有这个包，才写标记。

### 安装、锁和超时

apt 互斥不再包住整次安装。等待锁最多 5 分钟；已经拿到锁的 `minis-dev-setup-full` 不会在第 5 分钟被取消，也不会在客户机 apt 还在跑时把宿主锁放掉。同时匹配构建和包管理的命令按包管理加锁。

客户机锁改成先用 `mkdir` 占目录，再写 pid。活着的持有者不会因为目录太旧被清掉。释放时只删自己的锁，失败后的 EXIT 陷阱不会拆掉别人的锁。`flock` 超时不再落进第二套协议。拿不到锁时安装脚本以退出码 1 结束，不再报空成功。

`minis-dev-setup` 和 `minis-dev-setup-full` 在 TERM/INT 时放开锁再退出。`minis-dev-setup-full`、`minis-android-sdk-setup`、`minis-self-build` 的超时下限是 30 分钟，默认 10 分钟的 shell 上限不再把长安装掐在中途。这个下限只加长，不缩短用户自己设的更长超时。

### 工作区、下载和命令

空目标目录上的 `renameTo` 失败时，不再把私有文件留在看起来是空的项目目录里。中断的搬移会在下次启动接着做。原子写不再先删目标再改名：改名失败时旧文件还在。

应用内更新遇到 HTTP 416 时，只有分片长度和发布包大小一致才当成下完。更短的分片会丢掉并报失败，不会装成一个残缺 APK。改名失败时先确认目标文件已经写出来。

会话路径被拒绝后，读图和模型调用不再回退到全局绑定目录。权限模式读的是生效值：本会话已经允许全部时，`security.permissionMode` 报 `ALLOW_ALL`。

命令超时会杀掉本机 `Process`，而不是只清回调。自编译在任务体开始前就登记活动状态；登记失败也会打开闸门，避免界面一直停在「正在编译」、下次再点没有反应。从 Activity 打开媒体或分享时，不再无条件加 `FLAG_ACTIVITY_NEW_TASK`，避免把正在使用的界面送回桌面。

---

# OpenMinis-Linux 1.36.22-linux

- versionCode **75**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版（1.36.22-linux，2026-09-22）

相对 1.36.21-linux。修的是沙箱运行时仍能复现的问题，不是把历史版本说明再贴一遍。

### 持久 shell

一次 `read` 不再按字节边界解码。中文如果被切在字符中间，会留到下一块拼完，而不是变成替换字符。命令结束标记跨两次读取时也能认出来，下一条命令不会一直等。超时或取消会杀掉当前进程；以前只清回调，下一条命令会堵在没结束的那条后面。持久 shell 增加 `--kill-on-exit`，宿主退出时客户机 shell 一起结束。

### 机内 SDK 与自编译

下载 CMake 时，下载函数不再改写安装目录变量，解压结果能落到 SDK 的 cmake 目录。NDK 默认改用已发布的 `android-ndk` 标签和 `android-ndk-r29-aarch64.tar.xz`；旧地址是 404。压缩包支持 tar.xz、tar.gz、zip，缺 xz 时再装 xz-utils。设置页的实验性自编译改成进程级任务，离开页面不会取消，只有停止或进程结束才会停。找不到源码树时以非零退出。

### 客户机证书

主机多出来的 CA 写到 `/usr/local/share/ca-certificates/minis-android/`，并复制到 `/usr/share/ca-certificates/minis-android/`，同时在 `ca-certificates.conf` 里启用。这样 `update-ca-certificates` 或重装 `ca-certificates` 重建 bundle 时，主机证书还在。AndroidCAStore 按指纹去重；已经在 Mozilla 包里的证书不再写第二份。钩子 `minis-ca-dedup` 在更新后去掉仍然重复的块，并且设为可执行。下一轮注入扫描 Mozilla 目录时会跳过自己写的 `minis-android`，避免把主机证书当成系统证书删掉。

### 软件源

`minis-dev-setup`、`minis-mirror` 和主机侧重试安装都不再关闭 TLS 校验。`apt-get update` 失败时先放开锁，跑 `minis-mirror auto`（HTTPS 失败会改 HTTP 镜像），再试一次。`minis-mirror --help` 补上 sjtu。

### 共享存储与前台服务

授予所有文件访问后，会话 shell 会绑定 `/sdcard`、`/storage/emulated/0` 和 `/var/minis/mounts/sdcard`。以前只更新了另一份挂载表，`shell_execute` 看不见。权限变化后下一条命令重建 shell。用户自己挂的同名 `sdcard` 不会被覆盖。

应用在后台时，前台服务启动被系统拒绝不再把调用方打崩。服务内部 `startForeground` 失败会停掉这次服务，避免系统再杀一次进程。

### 配置

`minis-config` 可以读到当前权限模式 `security.permissionMode`。这个字段只读，改模式仍在设置 → 权限。

---

# OpenMinis-Linux 1.36.21-linux

- versionCode **74**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版（1.36.21-linux，2026-09-22）

相对 1.36.20-linux。

### 输入

打开已有会话会清掉焦点并收起键盘。只有这次访问里助手确实流式输出过、且用户没有把列表滚走，回复结束后才重新聚焦输入框。新建草稿会话仍会在短暂延迟后聚焦。

### 人格提示词

导入时先比正文，再比文件名。正文相同（含名称也相同）弹出「该提示词文件与××文件内容一致，是否导入」，确认后另存，不覆盖。名称相同、正文不同弹出「该提示词文件与××文件名称一致，是否覆盖」；确认只覆盖私有文件，取消则加后缀另存。内置 `SOUL.md` 不能删除，也不能被覆盖。下拉列表只给非内置项显示删除，删除的是 `minis-global/memory/personas` 里的文件。

### 子代理

同一批并行子代理不再共用一张卡片、也不再把日志堆进同一枚芯片。每个子代理有自己的卡片和芯片；芯片限宽，显示角色和类型。卡片与详情只保留当前步骤。子代理结束或用户停止后，芯片从栏上消失。单个子代理的 `Throwable` 不会取消同一批里的其它子代理。

### 界面与脚本

点工具卡片、导出长文本或打开 Markdown 媒体时，只有当前 Context 不是 Activity 才加 `FLAG_ACTIVITY_NEW_TASK`，避免把正在使用的界面送回桌面。

`execute_code` 仍走 `Context.javaToJS`。APK 内置 `javax.lang.model.SourceVersion`，`latestSupported()` 固定返回 `RELEASE_8`，这样 Rhino 1.7.14 不会去加载依赖 `java.lang.Module` 的实现。R8 保留 `javax.lang.model.**` 和 `org.json.**`。

设置里的实验性自编译会接住异常，并把输出写到该设置项。客户机脚本在找不到 `scripts/build_apk_aarch64.sh` 时以非零退出；找到挂载的源码树后，先跑 `minis-android-sdk-setup`，再执行该脚本。

---

# OpenMinis-Linux 1.36.20-linux

- versionCode **73**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版（1.36.20-linux，2026-09-22）

### 权限

工具闸门和系统权限是两层，不再只出现在多智能体页。设置 → 权限顶部是工具模式（询问 / 全部允许 / 只读 / 计划 / 全部拒绝），下面仍是无障碍、Shizuku、存储。

「本会话全部允许」和全局「全部允许」走同一套判断：用户规则优先；拒绝规则仍然生效；`rm -rf /` 在全部允许下改为弹确认，不再静默拒绝；权威路径围栏只在询问模式下拦住工作区外写入。

### 会话隔离

文件工具解析路径后会规范化，拒绝落到别的会话 `minis-sessions/<other>` 或别的项目 `minis-workspaces/<other>`。同一项目的共享工作区、自己的日记、全局技能、rootfs 仍然可读。`search_sessions` / `read_session` 不受影响。删一个会话不会删项目树。

### 客户机证书

注入的 CA 改为目录 0755、文件 0644，避免 Android umask 0077 加上 PRoot 的权限检查让非 root 读不到证书。同时按 OpenSSL 的 subject hash 写出 `.0` 文件，并把 `SSL_CERT_FILE` 写进 `/etc/environment` 和 bash 启动脚本，不依赖本次进程的环境变量。

---

# OpenMinis-Linux 1.36.19-linux

- versionCode **72**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版（1.36.19-linux，2026-09-22）

### 用户反馈的P0 修复

1. **minis-dev-setup 巨无霸问题** - 原脚本强制安装 gcc/ffmpeg/openjdk/golang (~800MB)，蜂窝网络30分钟没下完
   - **修复**: 拆分为轻量版（~30秒，必需包）和完整版（10-20分钟，可选）
   - 轻量版自动运行：ca-certificates, curl, wget, python3, git, nodejs, psmisc, unzip
   - 完整版手动运行：`minis-dev-setup-full`

2. **apt运行时阻塞所有shell命令** - aptMutex 无限等待，后续 shell_execute 全部超时
   - **修复**: 添加 5 分钟超时，失败时抛出清晰错误
   - 非apt命令不再被阻塞

3. **脚本重试无法kill** - apt_try 8次重试，杀掉 apt-get 后自动拉起新进程
   - **修复**: SIGTERM 优雅退出（释放锁，exit 143）
   - 轻量版移除重试循环，使用 `set -e` 快速失败

### 其他改进

- **seedNetworkTools 优化**: 已存在包跳过安装，不重复下载
- **文档**: FIX-DEV-SETUP.md 详细技术说明

## 历史版本

### 1.36.18-linux (2026-09-22)

相对 1.36.17-linux：

- **CA 注入**：每次 overlay 后把 `AndroidCAStore` 写成客户机 `ca-certificates.crt`；Android 14+ 还会扫 conscrypt APEX（`/system/etc/security/cacerts` 为空时不算命中）。PRoot 里看不到主机 `/apex`，所以在主机侧拷贝，不在客户机里 `ln -s`。
- **镜像探测**：`minis-mirror auto` 不依赖 curl；bash `/dev/tcp` HEAD 快筛，再用 `Dir::Etc::sourceparts=-` 的临时源做 apt 实测；HTTPS 失败改 HTTP。
- **apt 锁**：主机 `SandboxResourceGate.aptMutex` 与客户机 `apt-lock.sh`（flock → 再清 dpkg 锁；flock 不可用则 mkdir）串行 `minis-mirror` / `minis-dev-setup` / 开机装包。
- **种子包**：开机安装 curl、wget、python3、git、psmisc；nodejs/npm 尝试一次。`minis-dev-setup` 先装这些再装完整工具链，遇到 dpkg 锁会重试。
- **打开链接**：PersistentShell 不是 TTY，以前 `minis-open` 会走 `android-open` 把界面切到 Chrome；现在默认 OSC 应用内预览，`--system` 才出系统浏览器。

---

# OpenMinis-Linux 1.36.17-linux

- versionCode **70**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.16-linux：

- **工作区收敛**：每次启动把已归档、但仍留在会话私有目录的共享文件搬进项目工作区。修 1.36.14 只写了 `folder_id`、没搬文件的半归档。
- **移出 / 解散分组**：共享文件拷回该会话私有目录（拷贝，不搬项目里其他人的）。拷回冲突或失败时不删项目树，避免把唯一副本扔掉。
- **自动归档也搬文件**：`setFolderIfUnfiled` 在写库的同时 `moveSessionIntoProject`。
- **会话列表 FAB**：右下角只留「新建文件夹」；新对话从文件夹卡片或长按选模型组进入，去掉会挡住主按钮的小 FAB。

---

# OpenMinis-Linux 1.36.16-linux

- versionCode **69**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.14-linux（含已合入 main、未单独打正式 tag 的 1.36.15）：

- **Termux 终端**：PTY 改用 `terminal-view:0.118.0`，去掉自研 emulator / canvas / `pty_bridge`。
- **提供商置顶 + 并行刷新**：常用分组；一键强制刷新全部服务商。Provider 库 v5（`pinned`）。
- **镜像自愈与包世界**：启动先 `minis-mirror auto`，再重试失败的 dpkg/pip；重置 Linux 先 dump `apt-mark showmanual` 与 pip extras，再只装缺失包。
- **时区**：相对 symlink + `/etc/timezone`，避免 PRoot 跟丢绝对路径。
- **一级设置无返回箭头**；Web 搜索列表无箭头、详情有。系统「选择文字」可分享进会话。
- **技能 requirements.json**：`env`/`tiers` 为 Map，优先 `apt`；环境变量页有平台集成卡片。
- **1.36.15**：遗留会话归档进工作区（分阶段拷贝、中断可恢复）；工具写文件原子化并回读校验；主 FAB 建文件夹、小 FAB 开新会话；子代理结束后从直播条移除；过程摘要在反向列表里放到内容后面。
- **仓库**：不再携带 `src/ios/`。

---

# OpenMinis-Linux 1.36.14-linux

- versionCode **67**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.13-linux：

- **项目工作区**：一个文件夹里可以有很多会话，共享 `workspace` / 附件 / 浏览器缓存；日记仍按会话隔离。删会话不清项目文件。
- **主页 FAB**：右下角改为「新建工作区会话」（当前展开的工作区，否则默认「工作区」）；长按仍选模型组。
- **升级**：1.36.13 之前、没有隔离文件的旧会话归入默认工作区；已隔离的 1.36.13 会话不强制合并。
- **日记**：`minis-global/memory/YYYY-MM-DD.md` 拷进各会话 memory（不覆盖已有文件；SOUL / GLOBAL / LEARNED 仍全局）。
- **AI 过程折叠**：折叠条只显示「AI过程 / 思考* / 工具*」，不再铺工具芯片；展开后列出思考和工具行。
- **HttpBody**：passthrough 支持 JSON / multipart / 原始字节；multipart 的 boundary 由 OkHttp 管；密钥不进沙箱。`minis-model-use` 支持 `body_kind=multipart`。
- **清理**：去掉无入口的 prompt-templates 字符串。

---

# OpenMinis-Linux 1.36.13-linux

- versionCode **66**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.12-linux：

- **一会话一工作区**：每个会话独立 `workspace` / `memory` / 附件 / 浏览器缓存；技能、共享目录、MCP 配置装在工作区外，所有会话都能调用。
- **删会话清工作区**：删除聊天会删除 `minis-sessions/<id>/` 整棵目录，包括记忆。
- **内置人格重写**：详细版 Minis Ultra（工作区边界、共享工具、记忆范围）。
- **本版覆盖一次 SOUL.md**：安装后不论用户是否改过，强制写成新内置人格，仅此一次。
- **移除人格扩展**：设置页、提示词模板、工作区规则入口和相关代码已去掉。

---

# OpenMinis-Linux 1.36.12-linux

- versionCode **65**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.11-linux：

- **检查更新不再卡死**：没给「安装未知应用」权限时先把请求写进磁盘再跳设置；从系统设置回来或进程被杀后继续原流程，不把确认按钮灰掉。
- **不再重复下载**：唤起安装器不再清掉 pending；磁盘上已有完整且大小匹配的 APK 直接安装。
- **生命周期**：设置页进出多次后，一次恢复不会再弹多个安装界面。

---

# OpenMinis-Linux 1.36.11-linux

- versionCode **64**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.10-linux：

- **已有会话人格纠偏**：系统提示写明更早的回复可能是旧人设；有人格时只在发给模型的最近一条用户文本前加 `<persona-binding>`（不入库）；压缩摘要注明以当前人格为准。新会话第一句不加。
- **AI 过程折叠改为增量收起**：思考或工具一完成就收进摘要，不必等整段回复结束；只有正在跑的工具还展开。折叠后摘要上可点子芯片打开工具详情（详情页不再跟悬浮条共用「只含进行中」列表）。开关仍默认关。
- **子代理**：顶栏芯片运行中可点看实时日志；批量 spawn 也会把各子代理日志写进工具块；结束后输出 `## Trace` + `## Report`。
- **人格设置一级页**：去掉保存按钮。提示词切换即保存；名称 / 风格 / 图标、恢复默认在返回时自动保存。二级编辑页仍保留保存。

---

# OpenMinis-Linux 1.36.10-linux

- versionCode **63**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.9-linux：

- **人格提示词不再撑长设置页**：主页只显示当前文件名，点击进入二级页查看或编辑正文。
- **导入**：设置页 + 号从手机选 `.md` / `.txt`，复制进应用私有目录 `minis-global/memory/personas/`，下拉框切换当前提示词。
- **按供应商绑定**：每个供应商可选用不同人格文件，未绑定时跟随默认。发消息时按当前模型所属供应商注入。
- **去掉语言 Tad**：Auto / 中文 / English 三选一已移除，磁盘上只保留单一 `lang: auto`。
- **自绘图标**：文件、导入加号、供应商、chevron 用 Canvas 描边，不走 Material 图标包。

---

# OpenMinis-Linux 1.36.9-linux

- versionCode **62**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.8-linux：

- **AI 过程折叠真正生效**：开启后回复结束把思考和工具收进一条摘要；底部浮动工具条只保留进行中的工具，不再挂着已完成卡片（这是 1.36.8 开关看起来没效果的原因）。偏好监听回到主线程，回到聊天页会重读。工具回合间隙（等待下一模型块）先不收起。
- **release `execute_code`**：ProGuard keep 整个 `org.mozilla.javascript` / `org.mozilla.classfile`，避免 R8 裁掉 VMBridge 反射实现导致 `Failed to create VMBridge instance`。
- **ModelsApi 共用 OkHttpClient**：五个目录拉取客户端共享连接池，不再各 new 一个。
- **WebViewHolder.destroy 幂等**，Compose 离开时释放；无障碍双击改为等无障碍事件而不是 `Thread.sleep(80)`。

---

# OpenMinis-Linux 1.36.8-linux

- versionCode **61**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.7-linux，工程与运行时收口（产品行为不变）：

- **看门狗单调时钟 + API 35 解冻重置**：心跳改 `elapsedRealtime`；UID unfreeze 立即重置。gap > 30s 冻结伪影仍落 stall 日志但不计入断路器。
- **无障碍 offload 等待**：滚动/稳定/抽取轮询改为事件 condition wait；双击 80ms sleep 保留。
- **流式刷新抽出 `StreamSessionController`**，附节流阶梯单测；5xx 用 companion Regex 识别。
- **`ChatSessionPort` / `ChatRuntime`**：headless RPC 与 UI 共用同一套会话端口，debug 层不再依赖 `ui.chat`。
- **`:core:model`**：18 个 `data.model` 类型独立 JVM 模块；ThinkingRule Room 映射迁到 `data.db`。
- **models.dev 目录 gzip**（Android 约 4.2MB → 424KB），失败回退明文；iOS 仍用明文。
- **Release 开 `shrinkResources`**，NDK 符号表，CI 跑单测并归档 mapping / native symbols；Gradle configuration/build cache。
- **RAW SSE** 仅 VERBOSE 日志级打印。429 分级与 1.36.7 相同。

---

# OpenMinis-Linux 1.36.7-linux

- versionCode **60**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.6-linux，一批小刀口的性能与健壮性收口（来源：两轮源码分析报告）：

- **`agentTools` 列表记忆化**：此前是裸计算属性，每次访问都全量重建工具定义 + JSON 参数 schema + 插件注册表扫描；实测访问点有三处（每次流式尝试、每次工具执行、每次工具预检），一个含 10 次工具调用的回合至少重建 21 次。现在按"视觉能力 / Vision Group / 记忆开关 / 多智能体开关 / 插件商店变更戳"组合键缓存，输入不变直接复用。
- **provider 实例记忆化**：`ProviderFactory.create` 此前每次都新建实例，而每个 provider 自带一个 OkHttpClient（Dispatcher 独立，仅连接池共享）——14 人分组一次回退链最多新建 14 个。现在按"实例 id + 实例全量状态 + 模型状态 + 密钥指纹"记忆化（上限 64，配置写入 / 密钥轮换时整体失效）。OAuth token provider 闭包随实例缓存，刷新令牌后仍可用。
- **会话列表 `updated_at` 索引**（Room 15→16，含降级迁移 16→15）：会话列表主查询 `ORDER BY updated_at DESC` 此前是全表扫描 + 排序，且挂在 Compose 观察流上、任一列更新都触发重查。
- **主线程看门狗冻结伪影不再计数**：心跳用墙钟，进程被 cached-app freezer / 深睡冻结的时间全部计入 gap；设备两日 6 起事件全部为解冻伪影（48s / 6.6min / 63min / 11.4min，均单样本无重采样、0.5s 后恢复 idle）。现在 gap > 30s 的事件照常落 `stall-*.log` 但不计入断路器（渲染降级 ≥2 / 强制首页 ≥3），并打印 `freeze artifact` 标记。
- **非流式调用整体 deadline**（900s）：标题生成、压缩、oneShotAsk、vision group、快速测试等 `sendMessage` 路径此前只受单次 read timeout 约束，无整体上限。超时以 `TransientError` 抛出，走既有重试/回退分类，不会被误判为用户取消。
- **`AlarmReceiver` 收紧为不可导出**：此前 `exported="true"` 且通知文案取自外部 intent 的 `EXTRA_ALARM_LABEL`——任意第三方应用可借 Minis 身份弹出内容可控的通知（钓鱼载体），并可借 `EXTRA_ALARM_ID` 清除任意 ONCE 闹钟记录。`BOOT_COMPLETED` 是受保护广播、闹钟触发走自家 PendingIntent，均不要求 exported。
- **429 永久容量标记分级**：强标记（`无可用渠道`/`no_available_providers`/`insufficient_quota`/`负载已饱和` 等）仍直接 `ProviderError` 不重试；弱标记（`无可用`/`余额`/`billing` 等，可能出现在瞬时限流文案里）在 provider 层保持 `RateLimited`（transient 族），由既有的"还有回退候选就换人、末位候选只重试 1 次"逻辑自然分级——不再对末位候选硬禁重试。
- **`Retry-After` 补全**：支持 RFC 7231 HTTP-date 形式；显式数值/日期上限从 120s 放宽到 3600s（退避阶梯自身仍封顶 120s）。此前 `Retry-After: 86400`（日配额）会被压成 2 分钟重锤。
- **429 摘要脱敏**：进入 UI 横幅的响应体摘要对 `sk-…`、`Bearer …`、`api_key=`/`token=` 值、32 位以上 hex 串打码，防止中转在错误体里回显密钥。
- **`SessionConcurrencyManager` 快路径并锁**：acquire 的 check-and-add 与 `@Synchronized` 的 release 统一监视器，消除并发 acquire 双双通过容量检查的竞态窗口。

---

# OpenMinis-Linux 1.36.6-linux

- versionCode **59**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.5-linux：

- **聊天生成图片**：Agent 工具 `generate_image` 接到真正的生图接口。会选用已配置且带图像输出的模型；PNG/JPEG 落到气泡 `minis://attachments/generated/`，以 Markdown 图片显示。
- **厂商专用协议**：自定义 Base URL 的 Host 决定走哪套接口，中继（OpenRouter 等）即使挂了 Seedance/CogView 也不会误打到官方原生路径。
  - **豆包 / 火山方舟**：生图 `POST /api/v3/images/generations`；生视频 `POST /api/v3/contents/generations/tasks` 后轮询，成功立即下载临时 `video_url`。`doubao-video-gen-01` 固定 5 秒 / 720p；Seedance 用官方 `content[]`。生产轮询间隔不少于 8 秒。
  - **智谱 / BigModel**：生图 `/api/paas/v4/images/generations`；生视频 `/videos/generations` + `GET /async-result/{id}`。
  - **通义 DashScope**：`qwen-image` 走 compatible-mode 生图；Wanx 走原生异步 `text2image` / `video-synthesis`，再查 `/api/v1/tasks/{id}`。
  - **MiniMax**：`/v1/image_generation`；视频 `/v1/video_generation` → query → files/retrieve。
  - **GPT / OpenAI / Gemini / Codex**：原 Images / Videos / 对话内联图 / Codex `gpt-image-2` 路径未改。
  - **Agnes**：按 OpenAI 兼容 `/images/generations`、`/videos*` 走，未编造原生协议。
  - **深度求索**：官方无生图/生视频 API，直接报错，不探测 OpenAI 媒体端点。
  - **混元**：`api.hunyuan.cloud.tencent.com` 走 OpenAI 兼容；`hunyuan.tencentcloudapi.com` 需要 TC3 SecretId/SecretKey，明确报不支持。
  - **可灵**：原生需要 AK/SK JWT，明确报不支持。
- **模型识别**：`seedream` / `cogview` / `hunyuan-image` / `glm-image` / `qwen-image` 等可被 `generate_image` 选中；`seedance` / `doubao-video` / `hunyuan-video` 等可被 `generate_video` 选中。视频模型不会被误当成生图模型。

---

# OpenMinis-Linux 1.36.5-linux

- versionCode **58**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.4-linux：

- **浏览器**：地址栏和 UA/设置移到底栏；关闭在左、全屏在右；默认高度 80%。
- **检查更新**：滚动包正文过短时从同版本 tag 补更新说明；选包按版本互比。
- **网页搜索**：自定义引擎（`{query}` / `{key}`）；API 与密钥改到各引擎二级页。

---

# OpenMinis-Linux 1.36.4-linux

- versionCode **57**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.3-linux：

- **聊天生成视频**：纯视频模型（Sora / Veo / Kling 等，或打开「视频输出」）发消息即按提示词出片；文本会话可通过 `generate_video` 工具调用已配置的视频模型。mp4 落到气泡 `minis://attachments/generated/` 播放。
- **设置**：模型详情增加视频输出开关，保存不再冲掉 catalog 的 `video`。
- **接口**：OpenAI 兼容 `POST /videos`（404 再试 `/video/generations`、`/videos/generations`），支持同步 url 与异步轮询 + `/content`。原生 Gemini Veo 未接。

---

# OpenMinis-Linux 1.36.3-linux

- versionCode **56**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.2-linux：

- **热修崩溃**：`streamMessage` 套限流闸门时 `flow { emit }` 不能从 `withContext` 后的协程发射，发消息会抛 `Flow invariant is violated`。改为 `channelFlow { send }`。429 分桶与模型组回退行为不变。

---

# OpenMinis-Linux 1.36.2-linux

- versionCode **55**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36.1-linux：

- **429 分类**：无可用渠道 / 额度不足 / 未知模型等不再当瞬时限流连打；普通 429 带正文摘要。
- **按桶限流**：host + 密钥 + 模型名；同桶排队，不同 key 的同名模型是不同桶。
- **模型组回退**：仅当会话选中「设置 → 模型组」时按组序换人。只选提供商下的某个模型时绝不自动换。
- **设置 → 模型组**：显示限流桶、重复桶警告；页脚说明不跳组、计价可能不同。
- **压缩 / 子代理 / 日志**：429 不再分裂压缩；子代理不打回同一死桶；SSE 不再淹没 429 日志。

---

# OpenMinis-Linux 1.36.1-linux

- versionCode **54**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.36-linux：

- **缺参模型默认值真正生效**：有 ID 但目录/接口没给参数时，不再因名字像 GPT/Claude 而跳过。缺项补 256k 上下文、128k 输出、开启思考（最高 max）、文本模态。目录和手改覆盖仍优先。
- **步进器点数字输入**：并发上限、重试次数、Shell 超时、file_read 字符/行数、子代理最大轮次，点 − / + 中间的数字可输入，确定时按 min/max 夹紧。

---

# OpenMinis-Linux 1.36-linux

- versionCode **53**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.35.1-linux：

- **models.dev 归一化匹配**：去厂商前缀、统一大小写和 `./_` → `-`，再按「自家供应商精确 ID → 归一化 ID → 全库多数票」补上下文 / 最大输出 / 思考档位 / 模态。
- **DataLearner 补充**：目录没有上下文或最大输出时，后台拉 DataLearner 详情页补洞，不覆盖已有字段。
- **脏名按字重合**：`GPT-6免费` / `免费GPT-6 Astra` 这类中转站名字按命中最多的字匹配，不误吃 Pro，品牌空壳不套型号。
- **未知 id 默认**：256k 上下文、128k 输出、开启思考（最高 max）、文本模态。

---

# OpenMinis-Linux 1.35.1-linux

- versionCode **52**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.35-linux：

- **人格字数上限真正移除**：1.35 只改了注释、计数逻辑仍在，编辑器依旧显示「已超出上限」。现在 `isOverLimit()` 恒为通过，设置页的红色告警与上限文案一并删除。
- **技能启动刷新**：每次启动重新扫描 `minis-global/skills/`，全部技能默认启用；只有用户手动关闭过的保持关闭。SKILL.md frontmatter 不完整也按目录名注册；系统提示可列出的技能数 20 → 300。
- **多智能体上限/重试次数**：去掉点击数字手动输入，只保留 +/-，当前值显示在行底部副标题。
- **权限模式 ALLOW_ALL 不再弹窗**：根因是未知工具默认判定为「不可逆」，旧逻辑在 ALLOW_ALL 下仍对危险/不可逆操作弹确认。

---

# OpenMinis-Linux 1.35-linux

- versionCode **51**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.34.1-linux：

- 审批卡片与聊天内横幅新增「**本会话全部允许**」：本会话后续敏感操作自动放行，会话结束自动复位。
- 提示词模板与工作区规则合并为设置页的「**人格扩展**」单一入口（旧 deep link 仍可达）。
- 工具限额四项并入**多智能体**设置页；设置页工具限额入口删除。
- 协作角色**不再写死**：可新增 / 编辑 / 删除，同名覆盖内置，提示词与工具白名单在派发时生效。
- 人格**不再限制字数**：保存、minis-config 写入、系统提示注入三处长度闸全部移除，正文逐字注入。
- 默认人格升级：覆盖安装时若 SOUL.md 仍是原始 starter（用户从没改过）则升级为内置人格；改过则永不触碰。

---

# OpenMinis-Linux 1.34.1-linux

- versionCode **50**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

相对 1.34-linux：

- 提示词模板和工作区规则保存时**不再**过滤注入/越狱措辞；删除 `PromptSafetyFilter`。
- 工作区规则**不再**写入任何会话的系统提示词（含「不能覆盖权限闸」那句）。规则仍作为设备上的 Markdown 文件库 + `state.json` 启用开关保存。
- 会话模板、工具限额、SecurityGate 拦截/审批徽标仍在。不升 Room。

---

# OpenMinis-Linux 1.34-linux

- versionCode **49**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

四项中性运行时能力（不升 Room、不替换 ChatViewModel / SubAgentRunner、不拆 SecurityGate）。提示词模板和工作区规则在保存时过滤注入类措辞。

### 会话提示词模板

- 设置 → 提示词模板：模板库 + 新会话默认。
- 对话 ⋮ → 提示词模板：只改当前会话，下一轮模型请求热切换。
- 每会话可独立选模板 / 「无」/ 跟随默认；「无」不被默认覆盖。
- SharedPreferences JSON。深链 `minis://settings/prompt-templates`。

### 工具限额

设置 → 工具限额（`minis://settings/tool-limits`）：

- Shell 超时默认 600s，范围 30–1800s（默认值即代理可请求的上限）。
- `file_read` 字符默认 80000，硬顶仍是 80KB。
- `file_read` 行数默认 0（不限制），或 100–20000。
- 子代理 maxTurns 默认 200，范围 10–200。

### 工作区规则

- 设置 → 工作区规则（`minis://settings/workspace-rules`）。
- `filesDir/workspace_rules/<id>.md` + `<id>.json` + `state.json`。
- 打开的规则插入所有会话系统提示词（身份段之后），并写明不能覆盖权限闸。

### 拦截 / 审批徽标

- SecurityGate 拒绝或用户否决：聊天顶部红条显示工具名和原因。
- ASK 待批：对话内允许/拒绝卡（1.33 闸规则不变）。

---

# OpenMinis-Linux 1.33-linux

- versionCode **48**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

对照 XINCODE/OSS 把 Agent 工具面和权限闸补齐，**不**用 AgentCore 替换 ChatViewModel，子代理仍走独立 `SubAgentRunner`。

- **SecurityGate**：默认 ASK；只读自动放行；写/高风险命令确认；`rm -rf /` 等 FATAL 直接拒绝；allow/deny 规则（deny 优先）；权威围栏（文件前缀 + 网络）；审计 sha256 链式哈希。设置 → 多智能体可改权限模式。
- **工具**：`list_dir` / `glob` / `grep` / `web_fetch` / `multi_edit`；`shell_exec` / `env_exec` 等同 `shell_execute`；`su_exec` 走客户机 `android-su`；`dispatch_agents`（内置探索者/审查员/编码员/研究员，独立工具/技能白名单，SharedPreferences 不升 Room）；`wolfpack_run`；`agent_plan`；`execute_code`（Rhino 1.7.14，仅只读工具）；`invoke_skill` / `skill_manage`；`ask_reasoning`；`describe_image` 等同 `read_image`。`generate_image` / `transcribe_audio` 在未配置时如实失败。
- 路径仍走 PRootKernel，不直接碰主机 File。记忆仍用 MemoryRecallEngine。

---

# OpenMinis-Linux 1.32.1-linux

- versionCode **47**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`

## 本版

热修 1.31/1.32 二次启动被「数据来自更新的版本」拦住的问题。

1.31 把 `AppDatabase` 升到 **14**（`code_symbols`/`code_edges`、`kanban_tasks`），启动前的 `DatabaseVersionGuard.CODE_DB_VERSION` 仍写 12。第一次打开 Room 把 `user_version` 写成 14，第二次守卫认为磁盘比本机构建新，拒绝打开。数据没有删，只是打不开。

1.32.1 把守卫改成 14，并加测试锁 `@Database(version)` 与守卫常量一致。装上即可继续用原来的会话。

---

# OpenMinis-Linux 1.32-linux

- versionCode **46**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

对照 XINCODE 补上 1.31 仍缺的三块：定时任务、插件市场、协作角色。记忆召回、`grep_source`、写文件审批闸、代码图保持 1.31 实现。

1. **cronjob**
   Agent 工具 `cronjob`：create / list / remove。日程 `30m`/`2h`/`1d` 或 `every 30m`/`every 2h`/`every 1d`。底层 `ScheduledTask` + AlarmManager，新增 `INTERVAL`、`intervalMinutes`、`fireAtMs`。explore/plan/子代理禁用，避免嵌套调度。提示词要求优先 `cronjob` 而不是 crontab/at。

2. **插件市场**
   设置 → 插件市场（`minis://settings/plugins`）。MCP 预设（Microsoft Learn / Context7 / DeepWiki）一键写入 MCP 集成。远程 OpenAPI 目录安装后暴露 `online_<id>__<op>`。出站 SSRF 校验（`FetchUrlGuard`）；API Key 加密存储且不进模型上下文。不移植 GitHub Token 连接器。

3. **协作角色**
   `spawn_agent` 的 `role` 可填：秘书助理、产品经理、架构师、工程师、前端设计师、测试工程师、侦察兵、拆解工、分析员。注入「盯着 / 不管 / 闭嘴 / 该找谁」并按角色收工具（含 `grep_source`）。设置 → 多智能体列出角色卡片。非目录名仍只当标签。

4. **边界**
   不覆盖 `MemoryRecallEngine`；不把 `grep_source` 换成主会话 grep 工具；不改 SOUL.md/GLOBAL.md。改编来源 XINCODE-Public（GPL-3.0-or-later），见 `THIRD_PARTY_LICENSES.md`。

---

# OpenMinis-Linux 1.30.2-linux

- versionCode **44**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **浏览器横条拖拽**
   内嵌浏览器顶部横条支持跟手拖拽：上拖展开全屏；下拖松手位置不低于默认高度则弹回收起，拖过则按原判定下拉关闭。全程弹簧动画过渡，触控区加大。

2. **进化开关状态可见**
   设置页“进化”描述从“从纠正中学习偏好（默认关闭）”改为动态显示当前状态：“从纠正中学习偏好（当前开启/当前关闭）”。

---

# OpenMinis-Linux 1.30.1-linux

- versionCode **43**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

检查更新下载图补强（小版本修复）。

1. **镜像加速兑底**
   点下载时并行探测 github.com 直连 + ghproxy.com + gh-proxy.com + mirror.ghproxy.com 四个节点的首字节延迟，选最快健康节点下载，中途某镜像挂了其他兑底。

2. **提示文案**
   下载前显示“正在检测下载节点，优选最快的…”，下载中显示当前节点；避免用户以为卡顿。

3. **后台下载 + 断点续传**
   下载在进程级作用域运行，退出页面/切后台不断；中断后 `.part` 文件留存，下次从断点续下（Range），并保留 sha256 验证 + pending 记录。

4. **旧包清理**
   每次进入检查更新页面自动删除私有目录中的旧版本/已安装的安装包（当前正在下载的保留）。

---

# OpenMinis-Linux 1.30-linux

- versionCode **42**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

多智能体设置与提问卡片的 UI 修复。

1. **步进器可直接输入数值**
   并发数、重试次数两个步进器的数字本身可点击，弹出数字键盘输入框；越界/非数字时“确定”置灰。+/- 仍保留。

2. **轮次上限项降级为说明文案**
   原“子代理轮次上限”独立设置行本就不可调，删掉；说明并入 section 脚注——由协调者按任务分配，固定 200 轮失控保护。少占一行，信息不丢。

3. **提问卡片按钮不再被遮挡**
   原整卡不可滚动，问题多/选项长时把底部“提交/跳过”挤出屏幕且无法滑动。现问题区加 `heightIn(max=420dp)` + 可滚动，按钮固定底部始终可见可点。

---

# OpenMinis-Linux 1.29-linux

- versionCode **41**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

修复 1.28 发现的一个真缺陷。

1. **explore/plan 解锁 grep_source**
   `grep_source`（1.27 新增的源码检索工具）正是为只读侦察类子代理设计，但 1.28 里它没进只读白名单 `READ_ONLY_ALLOW`，导致 explore/plan 被 `filterTools` 屏蔽、反而用不了。本版把 `GrepSourceTool.NAME` 加入白名单，只读子代理恢复可用。

---

# OpenMinis-Linux 1.28-linux

- versionCode **40**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

子代理失败重试韧性（与 1.27 轮次预算合并）。在 1.27 “单个子代理跑几轮”之外，补上“失败重试几次”。

1. **有界重试循环**
   `runOneSubAgent` 改为有界重试：端点临时错误（429 / 截断流 / EOF）按次数重试，退避 2s→5s 带随机抖动，尊重 `RateLimited.retryAfterSeconds`。`CancellationException` 绝不重试。

2. **重试轮换池内端点**
   新增 `pickRetryEntry`：重试时换到**不同 provider 实例** 的池内 entry，避开被限流的中转，不连续打同一端点。新增 `isUpstreamTruncation` 识别上游截断。

3. **重试次数可调**
   设置 → 多智能体新增“子代理重试次数”步进器（`subagent_max_attempts`，1–5，默认 3，1 = 关闭重试）。

4. **失败不连坐兄弟**
   最终失败用 return 不 throw，避免异常逃逸裫 fan-out 的 `awaitAll` 连带取消同批其他 lane；重试成功结果带 `(recovered on attempt N/M via X)` 前缀。

---

# OpenMinis-Linux 1.27-linux

- versionCode **39**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

子代理轮次预算与工具优化。

1. **轮次解钳**
   设置页的「最大轮数」不再硬钳到 60，改为由协调者按任务复杂度分配；`SubAgentRunner` 保留 200 轮绝对上限作为失控保险丝。协调者分配的 `max_turns` 直接生效。

2. **预算预警 + 迫使交稿**
   子代理跑到预算 80% 时注入 `<budget_warning>`；95% 时注入 `force=true` 强令立即交付已有结果。循环结束返回累积的部分报告，不再只留一句「撞上限」。

3. **历史滑窗压缩**
   新增 `SubAgentHistoryCompactor`：发送前对超 120k 预算的对话做滑动窗口压缩，除最新 4 条外，超 12k 的 ToolResult 截为头 1500 + 尾 500，避免长任务把上下文撑爆。

4. **子代理专属 grep_source 工具**
   新增 `GrepSourceTool`（仅子代理可见，explore/plan 只读 kind 也可用）：匹配行±上下文 / 单文件 / 目录递归 / 正则，一次调用替代多轮 file_read 翻页。

---

# OpenMinis-Linux 1.26-linux

- versionCode **38**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

对照拾忆 `spawn_agent`，把原先偏粗的 `run_subagent` 调度收成一次调用、四种角色、执行层隔离。

1. **工具改为 spawn_agent**  
   协调者工具列表只暴露 `spawn_agent`。旧会话里的 `run_subagent` 仍可执行，子代理一律禁止再嵌套派出。

2. **一次 tasks[] 并行**  
   协调者按复杂度决定派出几个队友，放进同一个 `tasks` 数组。它们共享并发上限（1–8，默认 3），一个失败不取消兄弟任务。结果按「子代理 i/N」汇总。顶层 `prompt` 仍可作为单任务写法。

3. **四种 kind**  
   - `explore`：只读侦察（file_read / web_search / 会话检索等白名单）  
   - `plan`：只读设计  
   - `worker`：可写；同一波多个 worker 必须给出互不重叠的 `write_paths`  
   - `general-purpose`：兜底，仍禁止嵌套派出  

4. **动态轮次**  
   未指定 `max_turns` 时按任务推断：简单约 10，中等 20，复杂 40–60。设置 → 多智能体的数字是硬上限（默认/最大 60）。

5. **进度条**  
   聊天顶栏芯片为「子代理 i/N · kind · run · turn x/y · 当前工具」。

6. **schema**  
   `tasks` 在 Anthropic / OpenAI / Gemini 工具定义里是 array of object，避免模型把多队友拆成多次独立调用才并行。

---

# OpenMinis-Linux 1.25-linux

- versionCode **37**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

原生 Kotlin **进化层**（对照 [metano](https://github.com/qqzijin/metano) 的 Observe→提案→审批闭环，**不 vendor** 其 Python 运行时 / FastAPI / 消息网关）。入口在设置 → 进化，**默认关闭**。打开后也只生成待审提案；用户批准前不改系统提示。永远不写 `SOUL.md` / `GLOBAL.md`。设计见 [METANO-EVOLUTION.md](METANO-EVOLUTION.md)。

### P1 提案脊柱

1. **骨架**  
   `Proposal`（学习规则 / 技能补丁 / 撤回）+ 设置页批准 / 拒绝 / 推迟 / 回滚。批准的规则写入 `minis-global/memory/LEARNED.md` 标记区（`<!-- LEARNED-PREFS-START/END -->`），注入系统提示，上限 12 条 / 2KB。回滚恢复标记区快照。

2. **Be-ACTIVE**  
   会话正常结束时扫描最近用户句：`不对` / `错了` / `不要再` / `必须` / `记住` / `don't` / `never` / `remember` 等。命中则生成 **1 条**待审规则，证据带原句。不当场改 prompt。

3. **闲时收割**  
   充电（或电量状态未知）且距上次收割 ≥30 分钟，最多扫 12 个会话、处理 3 个。同一信念至少命中 2 次才升级成提案。输入截断，禁止全文 Matcher。含「任务 / 调研 / 继续 / TODO」等任务日记用词的用户句跳过，避免把待办当成偏好。

4. **技能补丁**  
   同一 skill 路径连续工具失败 3 次才提案，补丁是 SKILL.md 追加而不是整份重写。内置 bundled 技能不改原文件，改写落到 LEARNED（「使用该技能时：…」）。

LLM 提炼日额度 8 次；连续失败 3 次熔断，改用启发式原文。

### P2 信念、周反思、场景

5. **信念生命周期**  
   `draft → established → core`。近义摘要合并（token 重叠）。21 天未命中变陈旧，42 天衰减。Core 只在用户批准「撤回」后降级。注入超额时先留 `[core]`，再留较新条目。

6. **周反思**  
   闲时收割顺带，最多每周一次。对照 LEARNED 与后来用户句：打脸则提案 **撤回**；能抽出不同规则则再提案 **收紧**。42 天未命中的已批准规则提案「撤回闲置」。启发式为空且当日额度未满时，才打一次 LLM 复核。全部待审，不自动落地。

7. **场景 tag**  
   子弹可带 `[backend]` / `[workflow]` / `[writing]`；无标签规则始终注入。场景由会话分类（如 `code`→backend、`productivity`→workflow）、标题和最近用户原文判定，**不占用** `session.category` 存储字段。设置页预览展示全部场景。

---

# OpenMinis-Linux 1.24-linux

- versionCode **36**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **超长文本不再送进 ICU Matcher**  
   2026-09-18 子代理压力测试闪退：`DefaultDispatcher` 上 `Regex` → `Matcher.reset` → `utext_openUChars`，Scudo `internal map failure (Out of memory)`。设备 RAM 充足，是进程 native 地址空间被整段 markdown/日志撑爆。ContentDiag 只扫描头尾 8k 窗口；markdown 解析硬顶 32k；超长行当纯段落；代码高亮只正则前 16k。

2. **冷启动 prewarm 不再吞下整段超大碎片**  
   原先「先加入再看 96k 预算」，一条 5MB fence 仍会被送去 DefaultDispatcher 解析。现在跳过超过 32k 的碎片。

3. **日日志封顶**  
   `minis-yyyy-MM-dd.log` 8MB 后停写；单行 4k；`readLog` / 调试 RPC / 分享兜底不再 `file.readText()` 整文件进堆。

---

# OpenMinis-Linux 1.23-linux

- versionCode **35**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **子代理真并行**  
   每个 `run_subagent` 注入 `SubAgentLane`，`shell_execute` 派到独立 PersistentShell。父会话 Mutex 不再把队友命令排成队。绑定挂载仍指向父会话 `minis-sessions/<id>`，取消/结束时关掉 lane。

2. **团队模型按槽位**  
   并发上限 N 就生成 N 行「子代理 1…N」，可重复选同一模型或留空用主会话。同一回合第 N 个并行子代理用第 N 槽。

3. **WebApp 钉到主屏**  
   打开 `WEBAPP_PIN_ENTRY_ENABLED`；聊天 HTML 附件长按、文件浏览器、Web 预览「…」菜单恢复添加主屏幕。

4. **BrowserUse SameSite**  
   `SameSite=None`（含 `no_restriction`）强制 `Secure`；`CookieManager.setCookie` 用 cookie 自己的域名 URL。

5. **其它**  
   `HostEventHooks.persist` 同步 `commit()`；ChatViewModel 拆出计划讨论 / `run_subagent` / 工具标题与参数。

---

# OpenMinis-Linux 1.22-linux

- versionCode **34**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **子代理轮次可配置**  
   设置 → 多智能体增加步进器，默认 12 轮，范围 1–48。未传 `max_turns` 时用该值；传入则夹在 1…上限。

2. **Android 14 广播注册**  
   `HostEventBridge` / `MinisApp` 改用 `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`，避免 targetSdk 35 启动崩溃。粘性 `registerReceiver(null, …)` 未改。

3. **机内自构建**  
   `build_apk_aarch64.sh` / `prepare_android_sandbox.sh` / `deps/build_proot.sh`：`TMPDIR` 无效则落到 `/tmp`。`minis-android-sdk-setup` 与 `RootfsManager` 用替换而不是只追加 `android.aapt2FromMavenOverride`。

4. **沙箱代理与主机事件**  
   netlog 超 5MB 轮转；先 bind 再 `running=true`；CONNECT 隧道等双向结束再关 socket。电池 ≤15% 进 low、≥20% 才 ok。通知 ID / requestCode 用原子序号。`HostEventBridge.stop()` 注销 receiver；rootfs reset 时调用。`HostEventHooks` 读写同一把锁。

5. **检查更新**  
   同 versionName / versionCode 仅刷新时间戳不再提示升级；`pickUpgrade` 主键为 versionName。

---

# OpenMinis-Linux 1.21-linux

- versionCode **33**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **结构化子 Agent 任务书**  
   协调者 `run_subagent` 的 prompt 运行时包成 `## Task / Expected result / Constraints / Workflow / Collaboration`。任何 kind 都去掉并拦截嵌套 `run_subagent`。

2. **计划讨论 AUTO + 可见白板**  
   设置 → 多智能体：关闭 / 自动（跳过闲聊） / 每条消息。自动模式不跑短回复。完整轮次写入聊天 markdown，主会话按 Synthesis 执行。

3. **会话装饰可关**  
   设置 → 外观：浮动工具栏、工具预览、已完成工具卡（默认关）、子代理芯片、计划讨论横幅。进行中的工具仍显示。

4. **修复 1.20-linux CI**  
   `libminis_crash_handler.so` 曾链到 NDK 主机 `linux-x86_64/lib/libunwind.so`（与 aarch64 不兼容）。现固定 `ndkVersion = 28.0.13004108`，CMake 只按绝对路径链接 sysroot 里的 aarch64 `libunwind.a`，并用 `-Wl,--no-dependent-libraries` 忽略 LLVM 写入的 `pthread` 依赖（Bionic 无独立 libpthread）。

1.20-linux 标签仍在，但该次 GitHub Actions 没有产出 APK。请改下 **1.21-linux**。

---

# OpenMinis-Linux 1.20-linux

- versionCode **32**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版（对照 Operit / 拾忆 / OmniBot / Eta 的首批补齐）

1. **跨会话检索工具**  
   模型可直接调用 `search_sessions` / `read_session`（底层仍是原有会话库，不必再绕 `minis-sessions-cli`）。默认不包含当前会话；每条消息 600 字截断。

2. **子 Agent 种类与写路径**  
   `run_subagent` 增加 `kind=worker|explore|plan`、`write_paths`、`max_turns`。explore/plan 只读（无 file_write / file_edit / shell_execute）；worker 的 `write_paths` 限制文件工具前缀。

3. **默认助手入口 + 桌面小组件**  
   可在系统设置里把 Minis Ultra 设为助手（`ACTION_ASSIST`，无 LSPosed）。主屏小组件一点进入新建对话。

4. **browser_use / 内置浏览器内核**  
   目标 Chrome/151，实际跟系统 WebView APK；低于/高于 151 均可运行。聊天与文件里的 HTML 走 BrowserSheet，不伪装 UA。

5. **crash_handler 链接 libunwind**  
   `scripts/build_libunwind_aarch64.sh` 交叉编译 `libunwind.a`，CI 在 assemble 前安装进 NDK sysroot，`_Unwind_Backtrace` 可链接。

未做（下一版或你拍板）：局域网 WebChat、角色卡、本地 MNN/llama、MCP 市场、Operit2 多设备 Space、Eta 的 LSPosed/厂商助手接管。

---

# OpenMinis-Linux 1.19-linux

- versionCode **31**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a；有 `MINIS_UPLOAD_*` 则用上传证书，否则仍为 debug-signed）
- 签名说明：[docs/SIGNING.md](SIGNING.md)；一键编译：`scripts/build_apk_aarch64.sh`

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。debug 签名无法覆盖不同证书的已装版本。

## 本版

1. **主机反向事件通道**  
   电池低电 / 恢复、Doze 进出、网络丢失 / 恢复写入 `/run/android-events.jsonl`，并刷新 `/run/minis-host-status.json`（心跳 60s）。`minis-on-event register battery_low /var/minis/hooks/pause.sh` 注册客户机钩子；命令须通过与通知按钮相同的路径 sanitizer。

2. **动态 minis-notify 按钮**  
   `minis-notify post --title --body --action-label --action-command`。Intent extra 只有 token，命令存在 SharedPreferences。路径必须在 `/var/minis` 或 `/usr/local/bin/minis-*`，拒绝 `;|&$\`()。

3. **任务级能力路由**  
   `CapabilityRouter.neededForTask` 根据文本/附件推断识图或音频，改道原因写到 Live Update / overlay 状态。

4. **沙箱长任务保活**  
   命令开始时拉起 FGS + overlay；Stop 仍取消当前作业。进程被杀后下次启动写 `/run/minis-last-sessions.json`，shell 在下一条命令时重建。

5. **沙箱 http_proxy（无 VpnService）**  
   `minis-firewall log|cut|netlog`：环回 CONNECT 代理记流量到 `/run/minis-netlog.jsonl`，一键切断返回 403。主机 LLM OkHttp 不走该代理。

6. **新设备 WebDAV 恢复向导**  
   备份 → 恢复页顶部三步：选服务器、勾选会话/记忆/技能、口令恢复。

7. **机内自编译入口（实验性）**  
   关于页「沙箱内自编译」需确认；调用 `minis-self-build`。占用磁盘大，产物不能覆盖不同签名安装。

8. **aarch64 一键脚本 + libunwind 资产**  
   `scripts/build_apk_aarch64.sh`。CI 在 NDK 中找到 `libunwind.so` 时作为 release 附件上传。

# OpenMinis-Linux 1.18-linux

- versionCode **30**
- applicationId `com.openminis.linux`
- 启动器名称：**Minis Ultra**
- GitHub：[`tall-1997/OpenMinis-Linux`](https://github.com/tall-1997/OpenMinis-Linux)
- APK：`minis-ultra-com.openminis.linux.apk`（arm64-v8a，debug-signed）

安装：允许「安装未知应用」后打开 APK。可与官方 OpenMinis 并排安装。

## 本版

1. **多智能体团队模型勾选失效**  
   删除服务商后，池子里残留的 UUID 仍计入并发上限，系统提示还会把这些 UUID 打成 Team models。现已：丢掉不存在的条目、并发按仍活着的勾选计算、Checkbox 不再和整行各 toggle 一次。设置页会提示已清除的失效项。

2. **沙箱防火墙 / Doze / procfs（应用内，无 LSPosed）**  
   - `minis-firewall status|set allow|wifi-only|deny`：查询网络与策略。`--strict wifi-only` 会把**整进程**绑到 Wi-Fi（含 LLM）。不会自动对 uid 做 iptables DROP。  
   - `minis-doze status|request|oem`：Doze / 省电 / 忽略电池优化；`request` 弹出系统对话框。  
   - `minis-ps` 与 `/run/minis-proc.json`：只列出本应用能读的 `/proc`（Android hidepid 会藏其他 UID）。  
   - `/run/minis-host-status.json` 增加 firewall / doze / proc 摘要。

## 1.17-linux

1. **关于页 / 检查更新指向本 fork**  
   `ProjectRepo` 改为 `tall-1997/OpenMinis-Linux`。滚动标签 `android-latest` 不再按字符串和 `1.16` 比大小；用 release body 的 `versionName` / `versionCode`，以及 APK 资源 `updated_at` 对比本机 `lastUpdateTime`。

2. **模型组能力路由**  
   当前绑定的是模型组、本轮带了图片、而选中的成员没有视觉时，自动改用组内有 `image` / `image_input` 的成员。组里没人能看图则保持原选择，Vision Group 的 `read_image` 路径不变。

3. **任务完成通知：重试 / 备份 / 清理**  
   后台任务完成通知带三个按钮，点击后由 `ExecutionCoordinator` 在对应会话沙箱执行预设命令（重试上次 shell、打包 workspace、清 `/tmp`）。Intent 只带 action key，不带自由命令。

4. **沙箱控制手机（第一档）**  
   - `minis-toast <text>`：弹出 Android Toast  
   - `minis-clipboard`：等同 `android-clipboard`  
   - `minis-open --system <url>`，以及无 TTY（cron）时的 http(s)：走 `android-open`

5. **沙箱状态文件**  
   客户机 `/run/minis-host-status.json` 约 30 秒刷新：电池温度、剩余空间、应用前台/后台、wakelock、已注册 offload 名。只用 StatFs，不递归扫描 `ubuntu-rootfs`。

## 1.16 已有能力（仍在）

- 子 Agent 工具详情流式步骤；计划讨论横幅 + 同一轮执行；存储页不阻塞扫描完整 rootfs。
