# a2a 原生化：内置网关从 PRoot 宾客进程改为宿主进程

日期：2026-10-28 · 状态：已实现 · 看板第 4 条

## 起因

a2a（Agent2API）此前作为**宾客插件**落地（PR #167）：8.2 MB 的 a2a-runtime.zip
装进 PRoot 沙盒，由 install.sh 铺设、nohup 拉起。但这个 Rust 二进制是自包含的
（无 Node、无脚本依赖），跑在沙盒里纯属兼容层开销：PRoot 翻译每一层 syscall，
启动慢、日志绕、进程管理靠 pidfile 文本协议。上游桌面版（Tauri）本来就是把它
当宿主进程跑的——Android 侧照搬宾客形态没有架构收益，只有翻译税。

原生化后：CI 用 NDK 把上游 agent2api-server 交叉编译成 bionic aarch64 二进制，
以 `liba2a.so` 之名进 APK 的 nativeLibraryDir（Android 只解包 `lib*.so`，故扩展名
是 .so），app 直接 ProcessBuilder 拉起。宾客安装路径对 a2a 整体退役。

## 方案

### 目录（BuiltinPluginCatalog.Descriptor 扩展）

- `nativeBinary: String?` — nativeLibraryDir 里的二进制名（`liba2a.so`）；非空即原生态
- `nativeEnvPrefix: String?` — 上游运行时 env 前缀（`AGENT2API`）
- `isNative = nativeBinary != null` — 宾客/原生态路由的唯一开关
- 宾客字段（installScript/envPrefix/launcher）对原生态插件全 null，反之亦然

### 宿主侧布局（app 私有，非 shared）

```
<filesDir>/builtin-plugins/a2a/
  ui/    面板 bundle（a2a-ui.zip 解出：index.html + login.html + web-login.js …）
  data/  网关状态（SQLite、日志、生成的 Key）
  data/tmp/  TMPDIR —— bionic 无 /tmp，SQLite 临时文件需要
```

私有而非 shared：原生态进程就是 app 自己的 uid，不需要共享卷；app 私有目录把
网关凭据挡在其它沙盒会话的可达范围之外。

### 运行时 env（nativeEnv，对上游 paths.rs / agent2api-server.rs 逐项核对）

| 变量 | 值 | 依据 |
|---|---|---|
| AGENT2API_HOST | 127.0.0.1 | 上游 headless 默认 0.0.0.0，必须显式绑回环 |
| AGENT2API_PROXY_PORT | 18901 | 与 lane 契约一致 |
| AGENT2API_PROXY_HOME | …/data | 配置/数据目录 |
| AGENT2API_UI_DIR | …/ui | 面板静态根 |
| AGENT2API_ALLOW_NO_KEY | 1 | 回环独占 + app 自用；同时关掉面板注册闸门 |
| HOME / TMPDIR | …/data, …/data/tmp | bionic 无 /tmp；HOME 空会坏 SQLite 临时文件 |

### 进程管理（NativeGatewayService，与 GatewayServiceManager 同形）

- **start**：健康探测先行——端口已应答即收养（崩溃遗留的孤儿网关），不重复
  拉起；否则 ProcessBuilder 裸跑（argv 恰一个元素，上游不解析 CLI 参数），
  20 s 健康轮询，超时 destroy 防崩溃循环占位
- **stop**：TERM 本进程 + /proc 扫描 argv[0] 精确匹配的同 uid 孤儿 → 5 s 宽限
  等端口停答 → KILL 幸存者。无 pidfile——端口即所有权
- 状态流/开关/互斥与宾客管理器同形，市场卡片代码两条路径完全一致

### CI（native-deps.yml）

- `A2A_TAG=v3.0.8` + `A2A_SHA` 钉死 tag 指向的 commit——tag 被上游移动则构建
  大声失败，不静默换二进制
- rustup 装 `RUST_VERSION=1.88.0`（上游 Cargo.toml 声明的 MSRV，最可复现的钉子）
- NDK r29 的 `aarch64-linux-android26-clang` 包装器做 linker/CC（minSdk 26），
  `cargo build --release --locked -p agent2api-server`
- 产物 `agent2api-server` 复制为 `jniLibs/arm64-v8a/liba2a.so`，`file` 断言
  `ARM aarch64`——上游 Dockerfile 曾静默产出 x86_64，错架构的 .so 只会在
  设备首启时爆炸，CI 必须当场抓住
- cargo registry 缓存 sources only；target 目录刻意不缓存（数 GB，配额已紧）

### 宾客路径的防误入闸门

- `BuiltinPluginInstaller.install/installAsync/installCommand/verifyCommand`
  对 isNative 一律 `require(!d.isNative)` 抛 IllegalArgumentException
- `GatewayServiceManager.toggle/startCommand/stopCommand` 同样拒绝
- UI（BuiltinPluginsSection）按 isNative 路由到 NativeGatewayInstaller /
  NativeGatewayService；lane 层（LoopbackGatewayLane）不感知差别——对 app
  来说两者都只是自己拥有的回环端口

## 语义不变

- lane 契约：id=a2a、端口 18901、/health、面板路径 /、seedOnBootstrap=false
- 免费通道行为（LoopbackGatewayLane 全部反转语义）原样
- ofm 宾客插件路径零改动（仅文档措辞更新）
- 市场卡片状态机不变（BuiltinPluginStore 同一状态流）

## 验收

- 本地四道静态门禁全绿：architecture policy（1031 文件）/ string refs /
  announced tools / CJK ratchet
- sim_native_deploy.py（NativeGatewayInstaller 的 python 镜像，对真实
  a2a-ui.zip 实跑）T0–T10 全绿：部署、index/login/web-login/autoclaw-oauth
  落位、data/tmp 可写、staging 清理、zip-slip 三向守卫
- 编译与单测交 CI（本地不做编译验证，按会话规则）
- 设备侧首验：装 APK → 市场卡片装 a2a → 开关拉起 → /health 200 → 面板
  打开 → 加上游账户 → lane 出现在模型选择器
