# 1.36.26-linux

versionCode **79**。安装包 `minis-ultra-com.openminis.linux.apk`。包名 `com.openminis.linux`，可与官方 OpenMinis 并排安装。

相对 1.36.25-linux。这一版补的是聊天客户端里缺的角色设定和搜索广度，并带上 1.36.25 已有的文件列表根目录与权限硬拒绝。

## 搜索

- Tavily、博查、Exa、Brave、Jina、智谱做成一等后端，各自有密钥槽。Bing、SearXNG、自定义 URL 仍在。
- 没手动选过引擎、但已经填了密钥时，不再默认只走 DuckDuckGo。失败回退会先试其他已配置的密钥后端，最后才是 DuckDuckGo。

## 世界书

- 设置里可以加关键词条目。只有最近几条消息命中关键词时才注入系统提示，常驻人格文件不再被整份设定撑满。

## 显示正则

- 规则只改聊天气泡。存档和发给模型的原文不变。

## 本机能力

- `ocr_image`：用设备上的中文文字识别读图片里的字，结果送回对话。
- `get_screen_time`：读各应用前台时长。没有「使用情况访问」权限时会打开系统授权页。
- 设置里有独立翻译页，用第一个已启用且有密钥的模型翻译，结果不写入会话。
- 窗口默认请求这块屏幕的最高刷新率，可在同一设置页关掉。

## 拆分

- 搜索、OCR、屏幕时间的分发从 `ChatViewModel` 抽到 `ChatViewModelHostToolsExt`。世界书命中文本也在那里。
- `:core:model` 本来就是独立模块。`:provider` 和 `:sandbox` 仍留在 `:app`：它们引用界面、权限闸门和 PRoot，拆成 Gradle 模块会成环，这一版不拆，以免安装包编不出来。
