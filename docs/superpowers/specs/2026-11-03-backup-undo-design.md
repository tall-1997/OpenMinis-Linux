# 备份撤销（Restore Undo）设计

**状态：** 已批准（M5-P2 第一块）
**日期：** 2026-11-03
**关联：** `BackupImporter` / `BackupViewModel` / `BackupAndRestoreScreen`

## 1. 问题

`startRestore()` 直接把一个 `.minisbak` 包写进本机：`BackupImporter.import()`
覆盖聊天、provider 配置、MCP 服务、技能、记忆、共享文件、环境变量。写之前
**没有任何快照**，写完之后**没有任何回滚路径**。用户点错包、选错类别、或者
包本身是旧版本，只能靠"再找一个更早的包"补救 —— 而那个包不一定存在。

可备份类别里唯一没有撤销能力的就是恢复本身。这是产品上最不对称的一处：
导出有历史（`BackupHistory`）、有目的地记录、有中断对账，恢复只有一句
"Restored N items"。

## 2. 方案概览

**恢复前做文件级快照，撤销 = 把快照文件放回去 + 重启进程。**

不选"再导入一份旧包"的语义撤销：`BackupImporter` 是 upsert 语义（按 id
合并、`skipped` 已有凭据），再导入旧包**删不掉本次恢复新增的记录** ——
那不是撤销，是"再叠一层"。要真正回到恢复前，只有文件级还原能做到。

不做 journal 级逐记录回滚：那需要在 importer 的每个写入点埋钩子
（1166 行、7 个类别、DB + 文件树 + prefs 三套存储），成本远高于收益，
而文件级快照的正确性不依赖 importer 的内部语义，importer 以后怎么改都不用跟着改。

## 3. 落点表（源码事实，不是推测）

| 类别 | 落点（相对 `filesDir`） | 出处 |
|---|---|---|
| CHATS | `databases/`、`bodies/`、`minis-sessions/` | `BackupImporter.importChats:316,617,328` |
| SHARED_FILES | `minis-global/shared` | `importSharedFiles:640` |
| SKILLS | `minis-global/skills` | `importSkills:655` |
| MEMORY | `minis-global/memory` | `importMemory:683` |
| MCP_SERVERS | `minis-global/mcp-servers` | `importMcpServers:708` |
| PROVIDERS | `shared_prefs/` | `importProviders:733` → `ProviderRepository` |
| ENVIRONMENT_VARIABLES | `shared_prefs/` | `importEnvironmentVariables:803` → `EnvVarRepository` |

`ROOTFS` / `CACHE` 不在 `BackupCategory.backupable` 里（`BackupFormat.kt:175`），
恢复界面勾不上，因此也不进撤销范围。

`databases/` 覆盖 `minis.db` 及其 `-wal` / `-shm` 伴随文件。恢复前必须
`PRAGMA wal_checkpoint(FULL)`，否则 `-wal` 里的已提交事务留在快照之外，
撤销后回退到更早的状态且不可察觉。

`shared_prefs/` 覆盖 `providers`、`mcp_servers`、`env_vars` 等全部 prefs 文件。
凭据值若由 Android Keystore 加密，快照里是密文，同设备同 app 的 Keystore
密钥不变，还原后可正常解密 —— 这一点不引入额外复杂度。

## 4. 接口

新文件 `backup/BackupUndoStore.kt`（纯 JVM，可注入根目录，无 Android 依赖）：

```kotlin
class BackupUndoStore(
    private val filesDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class UndoPoint(
        val id: String,                    // "undo-<epochMs>"
        val createdAtMs: Long,
        val categories: Set<BackupCategory>,
        val paths: List<String>,           // 相对 filesDir，实际被快照的
        val bytes: Long,
        val partial: Boolean,              // 有子树因体积上限被跳过
        val skipped: List<String>,         // 被跳过的相对路径
    )

    data class CaptureResult(val point: UndoPoint?, val error: String?)
    data class RestoreResult(val ok: Boolean, val bytes: Long, val error: String?)

    fun capture(categories: Set<BackupCategory>, maxBytes: Long = DEFAULT_MAX_BYTES): CaptureResult
    fun list(): List<UndoPoint>            // 新 → 旧
    fun newest(): UndoPoint?
    fun restore(point: UndoPoint): RestoreResult
    fun delete(id: String): Boolean
    fun prune(keep: Int = DEFAULT_KEEP, maxAgeMs: Long = DEFAULT_MAX_AGE_MS)
}
```

常量：

```kotlin
const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024   // 2 GB
const val DEFAULT_KEEP = 3
const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 天
const val DIR_NAME = "backup-undo"                       // filesDir/backup-undo/<id>/
```

`capture` 契约：
- 按第 3 节把类别展开成路径集合（去重、保持稳定顺序）
- 逐路径复制进 `backup-undo/<id>/tree/<相对路径>`；源不存在的路径**不写**，
  并记进 manifest 的 `paths` 之外（见下）
- 累计字节数超过 `maxBytes` 时**不中断**：跳过当前子树余下的大文件，
  `partial = true`，`skipped` 记下落点根（例：`minis-sessions`）
- 成功后写 `backup-undo/<id>/manifest.json`（手工拼 JSON，不引入新依赖）
- 失败（IO/磁盘满）返回 `CaptureResult(null, message)`，**不抛异常** ——
  调用方据此继续恢复但告知用户"本次没有撤销点"

`restore` 契约：
- 逐路径：先 `deleteRecursively()` 目标，再从 `tree/<path>` 复制回来；
  快照里没有该路径 → 只删不还原（这正是"撤销"要的效果：恢复新增的
  目录必须消失）
- 返回实际复制字节数
- **调用方必须先关闭 Room**：`restore` 不碰数据库句柄，只碰文件

`list()` 排序：`createdAtMs` 降序；解析失败的目录跳过并删掉（半截图无法撤销）。

## 5. 接线

`ui/settings/backup/BackupUndoBridge.kt`（新，internal 扩展函数）承担
ViewModel 侧的编排，`BackupViewModel` 只留调用点（该文件 1066 行已经在
架构基线上限，只能净 0 增长）：

```kotlin
internal suspend fun BackupViewModel.captureUndoBeforeRestore(
    categories: Set<BackupCategory>,
): BackupUndoStore.UndoPoint?

internal fun BackupViewModel.undoRestore(point: BackupUndoStore.UndoPoint)
internal fun BackupViewModel.refreshUndoPoints()
```

`captureUndoBeforeRestore` 顺序：
1. `db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()`
2. `BackupUndoStore(filesDir).prune()`
3. `capture(categories)`
4. 失败返回 null（恢复继续，报告里标注"无撤销点"）

`undoRestore` 顺序：
1. `AppDatabase.closeForRestore()`（新增：`INSTANCE?.close(); INSTANCE = null`）
2. `store.restore(point)`
3. `AppRestart.restart(context)` —— 进程重启，让所有 repo/prefs 重新读盘

`AppRestart.kt`（新，`util` 包）：`AlarmManager` + `PendingIntent` 300ms 后
拉起 `MainActivity`，再 `killProcess`。不引入 ProcessPhoenix 依赖。

`BackupViewModel.startRestore` 里在 `BackupImporter.import()` 之前插入
`captureUndoBeforeRestore(cats)`，把结果存进 `_undoPoint`，`RestoreReport`
读到它渲染"撤销本次恢复"按钮。

UI（新文件 `ui/settings/backup/BackupUndoUi.kt`，Screen 加一行调用）：
- 恢复完成报告底部：`Undo this restore`（destructive 样式）+ 二次确认
- 备份页恢复区：最近一份撤销点的说明行 + `Discard` 操作
- 撤销中：进度文案，完成后 Toast + 自动重启

## 6. 边界与取舍

| 情况 | 行为 |
|---|---|
| 快照写失败（磁盘满/IO） | 恢复照常进行，报告标注"本次恢复没有撤销点"，不阻塞 |
| 快照 > 2 GB | 部分快照，`partial=true`，UI 明确写"附件类数据未纳入撤销" |
| 撤销时 Room 已关 | 正常 |
| 撤销时 Room 关不掉（有活跃写） | `close()` 后仍有异常 → 返回错误，不重启；撤销点保留可重试 |
| 撤销点被手动删除 | `restore` 返回错误，UI 提示"撤销点已不可用" |
| 撤销后再次恢复同一包 | 正常，会生成新的撤销点 |
| 撤销点数量 | 最多 3 份、7 天；`prune` 在每次 capture 前跑 |

YAGNI：不做云端撤销点同步、不做逐记录 journal、不做撤销点加密
（它在 app 私有目录，且不含比原数据更多的东西）。

## 7. 测试（纯 JVM，tmpdir）

`BackupUndoStoreTest`：
1. `capture` 只复制选定类别的落点，别的目录不进快照
2. manifest 往返：`capture` → `list()` 拿回同 id / 类别 / 字节数
3. `restore` 后：新增文件消失、被覆盖文件回到旧内容、快照里没有的落点被删除
4. `partial`：`maxBytes` 很小时跳过大文件、仍生成可用快照、`skipped` 有记录
5. `prune` 保留最近 N 份、按时间清理
6. `delete` 后 `list` 不含它
7. 损坏的 undo 目录（缺 manifest）在 `list` 时被跳过

`BackupUndoPathsTest`：
8. 七类 → 落点映射与第 3 节表逐条一致（防止以后 importer 改落点而撤销表漂移）
9. 未知/不可备份类别不进结果

## 8. 不做

- 不改 `BackupImporter` 的写入语义
- 不改备份包格式（撤销点是本地私有目录，不进 `.minisbak`）
- 不做跨设备撤销点迁移
