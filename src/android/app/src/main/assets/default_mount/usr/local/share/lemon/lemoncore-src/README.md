# LemonCore · 原生 MCP 内核（主线已迁移 8788）

> v1.3 · 2026-09-27 · 小柠檬本体 ｜ 授权：lemon-user
> 二进制 48KB / 纯 C / clang 21.1.8 (NDK r30) / aarch64 android24

---

## 一、当前生产拓扑（2026-09-27 已迁移完成）

```
                 ┌─────────────────────────────────────────┐
   App / 客户端 ──>│ 8788  LemonCore（原生 C · 主线）          │
                 │  · 本地注册表 463 个 JSON 薄壳 → 直接 sh  │
                 │  · 上游桥：本地没有的工具转发 ↓           │
                 └──────────────┬──────────────────────────┘
                                ↓
                 ┌─────────────────────────────────────────┐
                 │ 8790  Java 内核 lemon-kernel（卫星）      │
                 │  · 供 432 个 Java 编译内建工具            │
                 └─────────────────────────────────────────┘
   合计 895 工具，与迁移前完全一致
```

| 端口 | 角色 | 进程名 |
|---|---|---|
| **8788** | **主链路（App 直连）** | `lemoncore` |
| 8790 | Java 卫星（上游桥） | `lemon-kernel` |
| 8791 | 灰度（备用） | — |

## 二、迁移前后对比

| 指标 | 迁移前（Java 内核） | 迁移后（LemonCore） |
|---|---|---|
| 主链路实现 | Java/dex on ART | **原生 C ELF** |
| 启动 | 秒级 | **17 ms** |
| 内存 RSS | 158 MB | **3.9 MB** |
| 崩溃隔离 | 单进程，崩则全崩 | **fork-per-connection** |
| 过载保护 | 线程池+闸门 | 128 子进程上限 + **503 快速失败** |
| 工具数 | 895 | 895（不变） |

**切换中断时间：约 2 秒**（10:05:30 停旧 → 10:05:32 新实例接管 8788）

## 三、运维命令

```sh
sh /data/adb/lemon_core/lemoncore.sh start|stop|status|restart|test   # 控制
sh /data/adb/lemon_core/lemoncore.sh conf                             # 看配置
sh /data/adb/lemon_core/migrate_8788.sh status                        # 看拓扑
sh /data/adb/lemon_core/migrate_8788.sh rollback                      # ★一键回滚
```

配置：`/data/adb/lemon_core/conf/lemoncore.conf`（PORT / UPSTREAM_PORT / UPSTREAM）
Java 端口：`/data/adb/lemon_kernel/conf/java_port`（=8790）

## 四、安全网（三重）

1. **开机自启 + 守护**：`/data/adb/service.d/lemon_lemoncore_boot.sh`
   等系统就绪 → 起 lemoncore → 常驻轮询（15s），端口消失即拉起
   （`.paused` 存在时跳过；`mcp_switch.sh off` 会创建它）
2. **Java 卫星守护**：`/data/adb/service.d/lemon_kernel_guard.sh`（盯 8790）
3. **一键回滚**：`migrate_8788.sh rollback` —— 停 lemoncore → 恢复脚本 → Java 回 8788

> 实测记录：8788 被 kill -9 后，守护在 **~25 秒内自动拉起**（10:16:52 日志可查）

## 五、v1.3 关键修复（全面查 bug 阶段产出）

| # | 问题 | 后果 | 修复 |
|---|---|---|---|
| 1 | `SIGCHLD=SIG_IGN` 与 `waitpid` 计数递减冲突 | 计数只增不减 → 累到 64 后**永久拒绝 accept，服务假死** | 改用 SIGCHLD handler，每轮 waitpid 正常回收 |
| 2 | `protocolVersion` 从顶层读（Java 从 `params` 读） | 协议协商恒为"匹配" | 改从 `params` 读 + 顶层兜底 |
| 3 | keep-alive，而 Java 内核是 `Connection: close` | 按 EOF 读响应的客户端**会挂死** | 改为每响应即关 |
| 4 | `/healthz` 返纯文本 + 缺 `/metrics` `/version` `OPTIONS` CORS | 网页桥接/CORS 被拒 | 全部补齐并对齐 Java 格式 |
| 5 | 无 `SO_RCVTIMEO` | 客户端连上不发数据 → 子进程泄漏 | 30s 读超时（对齐 Java） |
| 6 | socket 无 CLOEXEC | shell 子进程继承连接 fd | 全部加 FD_CLOEXEC |
| 7 | 超时只杀 sh 不杀进程组 | `sh -c "sleep 300"` 留孤儿 | `setpgid` + 整组 kill |
| 8 | 上游 `isError` 被丢弃 | 上游报错当成功返回 | 传播 isError（rc=2） |
| 9 | 错误码保真度（-32700/-32601/通知 202） | 与 Java 不一致 | 全部对齐 |
| 10 | 上游超时被我砍到 30s（硬化过头） | **所有 >30s 的 Java 长工具被截断** | 放宽到 180s，靠过载阀兜并发 |

## 六、验证证据

- **101 项全量测试套件**：`101/101 全绿`（HTTP 协议 / JSON-RPC / 注入安全 / 稳定性 / 与 Java 一致性）
- **持续压测**：650 请求全成功，**RSS 增长 0 KB**，fd 无泄漏，0 僵尸
- **迁移后产线回归**：8788 上 `101/101 全绿`（v11 与 v13 各跑一遍）
- 测试脚本留在 `/data/local/tmp/lemon_core/test100.sh`（8792 版）与 `test_8788.sh`（产线版）

## 七、编译

```sh
clang LemonCore.c -O2 -o lemoncore
```
工具链获取见学习中心 `02-技术经验/20260927-无编译器设备装clang工具链.md`