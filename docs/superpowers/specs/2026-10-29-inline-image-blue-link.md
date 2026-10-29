# T-inline-image-blue-link — 行内图片渲染成蓝色链接

## 起因
用户实测反馈：模型回复里嵌在文字中间的图片（`看这个 ![图](minis://...) 好看吗`）
渲染成蓝色 `[图]` 链接，不显示图片本体。整行独占的图片正常显示。

## 根因
`splitParagraphOnInlineMedia`（StreamingMarkdownText.kt）只把**视频/音频**扩展名
的 `![alt](url)` 从段落里切出来升格为独立媒体块；图片被故意留在段落内——注释
原文："Image extensions stay inline (rendered as `[alt]` link)"。理由是 Compose 行内
文本没有位图插槽。但独立 `MdBlock.Image` 渲染路径是完整的（Coil 异步解码、点击
进画廊、断图占位、解码尺寸上限），只是没人把行内图片升格过去。行内解析器
（StreamingMarkdownParseInline.kt:34）对 `![alt](url)` 的处理就是渲染成蓝色
`[alt]` 链接——于是混排在文字里的图片永远变蓝字。

## 修法（根因，非补丁）
`splitParagraphOnInlineMedia` 删掉 `hasMediaExt` 预检门（该门只放行视频/音频），
所有 `![alt](url)` 匹配一律切分升格：`mediaBlockFrom` 本来就按扩展名路由——
视频→Video、音频→Audio、**其余（含无扩展名）→Image**。图片与视频/音频走同一条
升格路径，行为统一。行内解析器的 `[alt]` 链接兜底保留，只兜「超长段落守卫
（>MAX_ICU_INPUT_CHARS）拒绝切分」的极端情况。

## 语义不变项
- 整行独占图片：`standaloneImageLineRegex` 分支先行命中，仍单块输出
- 视频/音频切分行为逐字不变（删的只是图片的排除门）
- 超长段落（>16384 字符）不切分，行内 `[alt]` 链接兜底
- 纯链接 `[text](url)`（无感叹号）不升格
- 增量解析 `fastAppendParagraph` 的后缀守卫（`![]()` 字符触发全量重解析）已覆盖

## 验收
- 新增 `InlineImagePromotionTest.kt` 7 例：混排图片升格、无扩展名 URL 升格、
  多图、视频+图片混排、独占行单块、纯链接不升格、超长段落守卫
- Python 镜像 `sim_inline_image_promotion.py`（会话产物，不入库）12 断言全过
- 本地四道静态门禁全绿（architecture 1031 文件 / string refs / CJK 813 /
  announced tools 21）；StreamingMarkdownText.kt 1415→1402 行，基线收紧提交
- 编译与单测交 CI
