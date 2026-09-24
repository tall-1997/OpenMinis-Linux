# Minis Ultra 1.36.53-linux

versionCode **106**。包名 `com.openminis.linux`。产品名仍是 Minis Ultra。

相对 1.36.52-linux。这一版来自对 1.36.23–1.36.27 发布说明的逐行复核，修的是复核中证实的三个缺口。

## 会话隔离收口

- `minis-sessions-cli list` 保持开放的前提是「只返回 id 与标题」。实现里它一直把每会话首条用户消息的前 60 字作为 `preview` 发出去，未授权的调用方由此读到别的会话的正文开头。现在 `list` 不再输出 `preview`，正文读取仍走需要 `session_read` 授权的 `search` / `messages`。
- 同理，`list --keywords` 此前按消息正文做 LIKE 匹配，等于把「别的会话是否提过某个词」当成了免授权的是否预言机。现在 `list` 的关键词只匹配标题；按正文搜索仍属于需要授权的 `search`。应用内 `search_sessions` 工具已自带 `scope=current_session` 收窄，不受影响。

## 删除确认补齐

- 1.36.27 说明「世界书和显示正则点按删除前要确认」。角色附加页和 Persona 页的世界书条目都有确认框，但 Persona 页的显示正则条目点按即删。现在它与另两处一致：点按先弹确认，确认后才删除。

## 测试

- 回包文件 TTL 在 1.36.25 已从 10 分钟收到 10 秒，`OffloadReplySweepTest` 里的镜像常量没有跟着改，本版同步为 10 秒并注明来源。

## 安装

侧载前允许「安装未知应用」。debug 签名不能覆盖用另一张证书装上的版本，见 [docs/SIGNING.md](docs/SIGNING.md)。完整说明见 [docs/RELEASE-NOTES.zh.md](docs/RELEASE-NOTES.zh.md)。
