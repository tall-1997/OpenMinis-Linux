# 内置免费模型网关（Builtin OFM Gateway）规格

日期：2026-10-28
上游：github.com/Ebony-Vinyl/dsh-our-free-model v2.3.0（MIT，内置移植 payload 9.3MB / 130 文件）

## 起因

用户拿到「Our Free Model」插件 payload（本地 OpenAI 兼容网关，20+ 免费前沿模型，免注册免 Key），
希望 OpenMinis-Linux 原生集成同能力。现状缺口：

1. OpenMinis 没有任何「内置插件」机制——dev-suite 是 apt/脚本配方，不承载自包含二进制运行集；
2. 免费通道只有 Zen / Kilo 两条远端 lane（BuiltinFreeLanes.ALL），没有本地网关 lane；
3. 沙箱 Node 是 apt 18.19，而网关运行集内嵌 undici 依赖 Node 22+（worker_threads.markAsUncloneable）；
4. 网关是前台阻塞进程（宿主托管），OpenMinis 的 ExecutionCoordinator 命令有墙钟上限，不能前台持有。

## 方案

### 1. 通用内置插件层（普适机制，非一次性补丁）

新增 `plugins/builtin/` 包，四个文件各司其职：

- **BuiltinPluginCatalog**：内置插件描述符。字段：id、nameRes、summaryRes、version、payloadAsset
  （APK assets 内 zip 路径）、installScript（payload 内相对路径）、env（注入安装脚本的 OFM_* 契约）、
  nodeMinMajor、servicePort、serviceHealthPath、laneId。加第二个内置插件 = 加一个条目。
- **BuiltinPluginInstaller**：assets zip 解压到 `/var/minis/shared/builtin-plugins/<id>/payload/`，
  Node 版本门（候选路径扫主版本，全 < 22 时走 nodesource 22 安装，apt 锁协议与 DevSuiteInstaller 相同），
  然后在专用沙箱会话里执行 install 脚本（env 契约：OFM_PLUGIN_PAYLOAD / OFM_TOOL_DIR / OFM_TOOL_DATA
  全部指向 `/var/minis/shared/builtin-plugins/<id>/` 下的子目录）。安装脚本本身是上游 payload 的一部分，
  原样保留。
- **BuiltinPluginStore**：SharedPreferences 状态机（NOT_INSTALLED / INSTALLING / INSTALLED / FAILED +
  版本号 + 失败诊断行），模式对齐 DevSuiteStore。
- **GatewayServiceManager**：服务生命周期。start = 在专用会话
  `ExecutionCoordinator.execute("builtin-ofm-gw", "nohup <launcher> > <data>/service.log 2>&1 & echo $!")`，
  前台阻塞问题由 nohup+& 化解；stop = `pkill -f` launcher 路径；isRunning / modelCount = Android 侧
  OkHttp GET `http://127.0.0.1:18900/v1/models`（PRoot 不隔离网络栈，guest 127.0.0.1 即 host loopback；
  网关默认只绑 127.0.0.1，无局域网暴露）。

### 2. Provider lane（第三条 BuiltinFreeLane）

- **LocalGatewayFreeLane**（新 FreeLane 实现）：id `ofm`；owns = baseURL host 127.0.0.1:18900 且 key 为空；
  declaredFree 恒 true（网关只服务免费模型）；discoversUndeclaredIds = false（无需探测学习）；
  classify：连接拒绝类错误 → UNKNOWN（绝不把「服务没启动」学习成 PAID/DEAD 污染 FreeLaneHealth）。
- **BuiltinFreeLanes.OFM**：instanceId `builtin-ofm-gateway`、label「免费模型网关 (本地)」、
  baseURL `http://127.0.0.1:18900/v1`、apiKey 空。
- **seedOnBootstrap = false（实现时对规格的修正）**：FreeLaneBootstrap.ensureSeeded 跳过 OFM，
  行由插件安装完成后的 restore() 显式种入。理由：首启时网关未安装，自动种子会得到一个每次刷新都
  失败的死行；插件市场卡片才是正确的发现面。显式通道（add-provider 预设、恢复行、安装后 restore）
  照常种入。
- offlineSeedIds：空。理由：网关需要网络才能服务任何模型（它代理上游免费通道），离线种子是对上游
  id 的猜测，会在用户首次发送时失败；首次成功 fetch 前的空选择器是诚实状态。

### 3. 服务拉起的三层入口

1. **手动**：插件市场卡片「启动 / 停止」开关。
2. **自启**：安装完成即置 enabled 标记，App 冷启时 `ensureRunningAsync()`（PRoot 随 App 进程死，
   网关随之死，冷启自拉是对称补偿）。
3. **懒启**：`ProviderRepository.refreshModels` 入口——instance 属 OFM lane 且已安装未运行时
   先 ensureRunning（30s 上限）再 fetch。单一调用点，不动传输层。

### 4. UI（Apple 风格约束）

PluginMarketScreen 顶部新增 `BuiltinPluginsSection`（SettingsSection + SettingsRow / SettingsSwitchRow，
与 DevSuiteSection 同语言）：标题、摘要、状态行（未安装 / 安装中 / 已安装·未运行 / 运行中·N 模型）、
安装按钮（确认弹窗：体积 + 网络说明）、启停开关。无新增图标（复用现有 chevron / 状态色语义），
文案全部走 strings.xml（CJK 门禁零新增）。

## 语义不变 / 兼容

- Zen / Kilo lane、FreeLaneBootstrap、InstanceRemoval 墓碑语义全部原样；OFM lane 只是 ALL 里多了一项。
- 用户删除 OFM provider 行 → 墓碑 → 不再自动重种（既有 InstanceRemoval 语义，免费通道恢复入口照用）。
- dev-suite nodejs-suite（apt node 18）与 nodesource node 22 共存：nodesource 覆盖 /usr/bin/node，
  nodejs-suite 探针 `command -v node && command -v npm` 依旧通过，无破坏。
- 网关运行需要外网（上游免费通道匿名铸号）；离线时 /v1/models 空列表 → 选择器空态而非报错。

## 验收

- 单测：LocalGatewayFreeLane（owns / declaredFree / classify 连接拒绝 → UNKNOWN）、
  BuiltinPluginCatalog（契约字段完备性）、GatewayServiceManager 纯函数（命令构造、状态推导）。
- Python 镜像：安装状态机（node 门 → 解压 → install → probe → 状态迁移 + 失败分支）与
  服务生命周期（start 幂等、stop 后冷启自拉、refreshModels 懒启时序）。
- 本地四道门禁全绿（architecture policy / string refs / announced tools / CJK 零新增）；
  编译与单测执行交 CI（本地不编译，会话既定约束）。
- PR 流程：分支 → 提交 → push → PR → CI 绿 → squash 合并 → 验证增量非零 → 删分支。

## 风险与边界

- **APK 体积**：payload zip 约 3MB（原 9.3MB），与 jieba 2.1MB 同量级，可接受。
- **端口冲突**：18900 被非网关进程占用 → 健康检查不匹配 → 启动失败并给出明确诊断行。
- **Node 22 安装失败**（无网）：安装器给出失败诊断 + 离线提示（沿用 DevSuiteOfflineHint 模式）。
- **上游模型列表为空**（上游通道全挂）：网关 200 + 空 data → 选择器空态，不误报 PAID。
