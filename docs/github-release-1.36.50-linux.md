# Minis Ultra 1.36.50-linux

versionCode **103**。包名 `com.openminis.linux`。产品名仍是 Minis Ultra。

## 本版

- 目录没给模态时，id / 名称像 Sora、Veo、Seedance、Seedream 的模型会先按视频或生图推断，再补缺省文本。以前缺省文本写在推断前面，刷新列表后这些模型被当成普通聊天模型，既不生成，工具也选不中。已经存成「256k / 128k / 纯文本」这组空目录缺省值、且用户没有改过输出模态的，打开时会重新推断。目录自己写了模态，或上下文不是这组缺省值的，仍不覆盖。
- 流式 RAW SSE 只在 `adb shell setprop log.tag.ToolChain[Provider] VERBOSE` 时打印。正文里出现 `"error"` 不再默认打进 logcat。
- 会话列表「复制」会把当前挂载的工作区、附件、浏览器、offloads 和该会话记忆拷到新会话的私有目录。已归档会话拷的是项目里正在用的那份，不改项目原件；副本不进同一分组，避免和原会话共用同一棵树。
