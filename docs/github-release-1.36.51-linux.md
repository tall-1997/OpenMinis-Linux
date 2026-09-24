# Minis Ultra 1.36.51-linux

versionCode **104**。包名 `com.openminis.linux`。产品名仍是 Minis Ultra。

## 本版

- 基础包已经装好时，开机种子不再跳过 Node。1.36.23 要求锁或网络失败下次启动重试；以前 curl、git 等齐了就直接返回，失败的 Node 安装不会再试。仓库里确实没有这个包时，仍在尝试结束后才写 `node-seed.attempted`。
- 轻量 `minis-dev-setup` 只装 ca-certificates、curl、wget、python3、git、nodejs、npm、psmisc、unzip。不再顺手装 python3-pip 和 git-lfs。gcc、ffmpeg、jdk、golang 仍在 `minis-dev-setup-full`。
