# pdf-thingmodel 设计

## 0. 背景

### 0.1 我们要解决什么

**用户上传一份几十到几百页的 PDF API/设备手册，agent 自动把每个 API 解析成物模型。用户基于这些物模型做两件事：浏览/对照查看、对不满意的物模型进行修改。**

核心约束：

- **上下文容量小**：模型窗口往往只有 128K，整本 PDF 塞不进去；解析必须分块。
- **用户视角是物模型本身**：用户不记 API 名也不输入关键词，是从列表里挑一个物模型，看着对不对，不对就改。
- **必须可审计、可回滚**：物模型直接给设备/产线下发指令，错了能查到"谁、何时、改了什么"，能一键回到上一版。
- **修改前后要能对比**：用户改完得马上看到差异，不能让用户手动 diff JSON。
- **多模型可切换**：qwen-max / Sonnet / 本地推理之间切换不动核心代码。

### 0.2 直接的方案为什么不行

| 朴素方案 | 失败模式 |
|---|---|
| 整本 PDF 一次性塞进上下文 | 30+ 页就溢出；50 页以上的手册根本不可行 |
| 按固定页数切分 | 跨页 API 被腰斩，物模型残缺；同名字段被分别"自由发挥"，schema 不统一 |
| LLM 输出 → 直接覆盖式 UPDATE | 没有版本链就回不去；模型偶尔会"嘴上说改 A 实际改了 B"，覆盖式入库后审计断链 |
| 改了不展示差异，让用户自己看新 JSON | 用户视野太窄，发现不了模型悄悄动了别处；信任迅速崩塌 |
| 每次都重新解析 | 解析一次几十秒 + 强模型烧钱；同一份 PDF 重传判不出来 |

### 0.3 问题 → 设计选择 映射

| 问题 | 设计选择 | 体现在 |
|---|---|---|
| 上下文撞死 | 按 API 维度切分；每次只送相关 chunk + 全局骨架 | §1 `api_chunks`、§4.5 上下文组装 |
| 切块丢全局视野 | `documents.skeleton_json` 始终随 prompt 一起送（meta + summary + conventions + outline + glossary） | §1.5、§2.2、§4.5 |
| 模型不知道在改哪本书 | `skeleton.documentMeta` + `skeleton.summary.abstract` 作为"文档背景"段进 prompt | §1.5、§4.5 |
| 模型乱猜默认单位/格式 | `skeleton.conventions` 显式声明 `defaultUnits/naming/errorCodeFormat` | §1.5、§4.5 |
| 修改可审计回滚 | 物模型多版本（`is_current` + `derived_from`）+ 版本自带 `user_intent/patch/change_type/created_by` | §3.1 |
| 并发改同一物模型 | 业务级乐观锁（条件 UPDATE `is_current=TRUE`） | §3.2 |
| 模型嘴上说改 A 实际改了 B | LLM 同时输出 `patch + newModel`，后端校验 `apply(patch, current) == newModel` | §3.3 |
| 修改前后对比 | `ModelDiffService` 用 `JsonDiff` 算字段级 ChangeItem 列表 | §4.6、§4.7 |
| 字段定位回原文 | `api_chunks.source_excerpts` 字段→原文位置映射 | §1、§4.4 |
| 全局变更（跨 N 个 chunk） | `GIN(jsonb_path_ops)` 反查受影响 chunk + 串行各自跑修改 | §3.4、§4.8 |
| 同份 PDF 重传 | `documents.content_hash` 唯一约束 dedup | §1、§2.1 |
| schema 演进 | 物模型用 JSONB 不展开成关系表 | §1.3 |
| 多模型可切换 | `LlmClient` 抽象 + `ChunkContext` 数据结构与具体模型无关 | (代码层) |

剩下四节按 **表 → 提取 → 存储 → 检索** 的顺序展开。

---

## 1. 数据库表

三张表，角色单一，互不重叠。

### 1.1 表清单

| 表 | 一行代表 | 关键字段 | 唯一约束 |
|---|---|---|---|
| `documents` | 一份 PDF | `skeleton_json`（见 §1.5 完整结构） | `content_hash` |
| `api_chunks` | PDF 里一个 API 段 | `page_start/end`、`raw_text`、`source_excerpts` | `(document_id, api_name)` |
| `thing_models` | 物模型一个版本（自带审计） | `model`、`is_current`、`patch`、`user_intent`、`change_type` | `(chunk_id, version)` |

```
documents (1) ─┬─ (N) api_chunks (1) ─ (N) thing_models
               │                              │
               │                              └─ derived_from 自引用形成版本链
               │
               └─ skeleton_json 字段内含 outline + glossary（不再单独建表）
```

### 1.2 关键索引

| 索引 | 用途 |
|---|---|
| `documents.content_hash` UNIQUE | 上传去重 O(1) |
| `thing_models(chunk_id) WHERE is_current=TRUE` 部分索引 | 取当前版本 O(1) |
| `thing_models USING GIN(model jsonb_path_ops)` | 跨 chunk JSONB 反查"哪些物模型包含字段 X" |
| `thing_models(created_by, created_at DESC)` | 用户视角运营查询 |

### 1.3 为什么物模型用 JSONB 不展开成关系表

- 物模型 schema 会演进；关系表演进每次都要 DDL，痛苦。
- 主流查询是"按 chunk 取整个物模型"，JSONB 一次 SELECT 就够。
- 反查通过 `GIN(jsonb_path_ops)` + `@>`，毫秒级。

### 1.4 为什么不再单独建 entities / glossary / modifications

- **glossary**：本身是文档级元信息，并入 `documents.skeleton_json` 后用 `jsonb` 操作符就能取，**单独一张表是过度规范化**。
- **entities**（倒排索引）：用户从列表挑物模型，不输入关键词搜索，**用不到**。需要时再加。
- **modifications**：每次修改本来就生成一条 `thing_models` 新行；把 `user_intent/patch/change_type/created_by` 几列合并进 `thing_models`，**审计链路完整且查询更简单**——版本即变更，一目了然。

### 1.5 `documents.skeleton_json` 完整结构

阶段 1 便宜 LLM 一次性产出。整体在每次单 chunk 修改时随 prompt 送给 LLM。

```json
{
  "documentMeta": {
    "docType":     "device_api_spec",
    "product":     "XX 智能温控器",
    "version":     "v3.2",
    "publisher":   "XX 公司",
    "publishDate": "2026-03-10",
    "language":    "zh"
  },

  "summary": {
    "headline":   "XX 智能温控器 v3.2 REST API 手册（30 个 API）",
    "abstract":   "定义 XX 智能温控器 v3.2 版本的云端控制 API，共 30 个接口...",
    "highlights": ["30 个 REST API", "Bearer Token 鉴权", "v3.2 新增 reportInterval", ...],
    "scope": {
      "covers":   ["设备状态", "温度采集", "阈值控制"],
      "excludes": ["OTA 升级", "本地配网"],
      "audience": "后端开发、产线集成工程师"
    }
  },

  "conventions": {
    "defaultUnits":    { "time": "second", "temperature": "celsius" },
    "naming":          "camelCase",
    "responseFormat":  "JSON",
    "errorCodeFormat": "string",
    "timestampFormat": "ISO-8601",
    "notes":           ["所有 API 均要求 Bearer Token 鉴权"]
  },

  "outline": [
    { "title":"概述", "pageStart":1, "pageEnd":3, "level":1, "type":"overview" },
    { "title":"设备类 API", "pageStart":7, "pageEnd":30, "level":1, "type":"section_group",
      "children":[
        { "title":"温度查询", "pageStart":11, "pageEnd":13, "level":2, "type":"api_definition" }
      ]
    }
  ],

  "sharedSchemas": [
    { "name":"Pagination", "definedAt":{ "page":5 } }
  ],

  "apiIndex": [
    { "apiName":"GET /devices/{id}/temperature", "page":11, "summary":"查询温度" }
  ],

  "glossary": [
    { "term":"上报间隔", "definition":"设备主动向云端推送数据的时间周期", "pageRef":2 }
  ]
}
```

每个字段的作用：

| 字段 | 必备性 | 用在哪 |
|---|---|---|
| `documentMeta` | 强烈推荐 | 文档列表、PromptBuilder 一行 anchor、跨文档识别 |
| `summary.headline` | 必备 | 文档列表、tab 标题、分享卡片 |
| `summary.abstract` | 必备 | 概览页头部、PromptBuilder "文档背景"段 |
| `summary.highlights` | 推荐 | 详情页"快速概览"区，前端展示用 |
| `summary.scope` | 推荐 | scope.excludes 进 PromptBuilder，**显式告诉模型哪些事不在本文档**避免编造 |
| `conventions` | 推荐 | PromptBuilder "全局约定"段，让 LLM 默认遵守单位/命名规则 |
| `outline` | 必备 | PromptBuilder "文档骨架"段、前端目录树 |
| `sharedSchemas` | 可选 | 阶段 3 解析时定位公共结构定义页 |
| `apiIndex` | 可选 | 前端命令面板/全局搜索 |
| `glossary` | 必备 | PromptBuilder "全局术语表"段 |

**整体 ~1.5KB JSON，转 markdown 后约 1500-2500 token**，永远在 prompt 里。

---

## 2. 数据提取

### 2.1 阶段 0：接收 + 去重（同步）

```
POST /documents (multipart) →
   1. 流式落对象存储，得 source_path
   2. 边上传边算 SHA256 → content_hash
   3. SELECT * FROM documents WHERE content_hash=? LIMIT 1
        命中 → 返回已有 documentId
        未命中 → INSERT documents (skeleton_json=NULL)
   4. 推 IngestionJob 进队列
```

### 2.2 阶段 1：骨架抽取（同步在 worker 里）

```
worker:
   1. 拉 PDF → pdftotext → PDFBox 取 bookmarks
   2. 一次便宜 LLM 调用：
        输入: bookmarks + 第 1-5 页 + 最后 5 页
        输出: {
          documentMeta, summary, conventions,
          outline, sharedSchemas, apiIndex, glossary,
          detected_api_chunks                    // 不进 skeleton_json
        }
   3. UPDATE documents SET skeleton_json = <前 7 项打包成 JSONB>
   4. detected_api_chunks 直接传给阶段 2 用来 INSERT api_chunks
```

**产出**：`documents.skeleton_json` 一次拿齐文档元信息、摘要、全文约定、骨架、共享结构索引、API 索引、术语表（结构契约见 §1.5）。下游所有 prompt 和前端展示都从这里取。

**为什么是一次 LLM 调用而不是分多次**：输入文本是同一份（前 5 页 + 后 5 页 + bookmarks），分多次浪费成本。让模型一次性按 schema 输出 7 个顶层字段，便宜模型（qwen-turbo / Haiku）就能搞定，~5K token 输入、~3K token 输出。

### 2.3 阶段 2：API 切块（同步）

```
对每个 detected api:
   1. pdftotext -f pageStart -l pageEnd 抽 raw_text
   2. INSERT api_chunks (status='pending')
   3. 推 ChunkParseJob {chunkId} 进队列
```

### 2.4 阶段 3：物模型解析（并发异步）

```
ChunkParseJob worker:
   1. UPDATE api_chunks SET status='parsing' WHERE id=? AND status='pending'
        受影响=0 → 别人抢了，退出（幂等）
   2. ChunkContextService.loadByChunkId 加载 skeleton + chunk + raw_text
   3. 必要时 pdftoppm 切页带原图
   4. 强 LLM 调用，要求输出:
        { model, source_excerpts }
        (source_excerpts 是字段→原文位置映射，前端展示用)
   5. schema 校验失败 → 重试一次 → 仍失败 → status='parse_failed'
   6. 事务内:
        UPDATE api_chunks SET source_excerpts=?, summary=?, status='parsed'
        ModificationService.applyInitialParse(chunkId, schemaVersion, model, "system")
          → 内部 INSERT thing_models (version=1, change_type='initial_parse')
```

**产出**：`thing_models` 一行（v1），`api_chunks.source_excerpts` 填上。

### 2.5 数据来源汇总

| 字段/表 | 来源 |
|---|---|
| `documents.skeleton_json.outline` | 阶段 1 LLM（基础来自 PDFBox bookmarks） |
| `documents.skeleton_json.glossary` | 阶段 1 LLM |
| `api_chunks.raw_text` | `pdftotext -f -l` 段抽 |
| `api_chunks.source_excerpts` | 阶段 3 LLM 顺手输出 |
| `thing_models.model` | 阶段 3 LLM |
| `thing_models.user_intent / patch / change_type` | 用户后续编辑 |

幂等性贯穿全流程：
- 阶段 0 靠 `content_hash` 唯一约束
- 阶段 1 靠 "skeleton_json IS NULL 才执行"
- 阶段 2 靠 `(document_id, api_name)` 唯一
- 阶段 3 靠 status 条件 UPDATE 抢锁

---

## 3. 数据存储

### 3.1 物模型版本控制

每次修改不是原地 UPDATE，而是**插入新行 + 切换 `is_current` 标志**。

```sql
SELECT * FROM thing_models
WHERE chunk_id = ? AND is_current = TRUE;
```

版本链通过 `derived_from` 自引用形成单链表：

```
v3 (is_current=TRUE,  derived_from=v2.id, change_type='llm_edit')
   ↓
v2 (is_current=FALSE, derived_from=v1.id, change_type='manual_edit')
   ↓
v1 (is_current=FALSE, derived_from=NULL,  change_type='initial_parse')
```

回滚到 v1 = 再插一行 v4，`model = v1.model`，`change_type='revert'`。**不删任何行**，历史完整保留。

### 3.2 业务级乐观锁

```sql
UPDATE thing_models
SET is_current = FALSE
WHERE id = #{expectedCurrentId}
  AND is_current = TRUE;

-- 受影响行 = 1 → 抢到了，继续 INSERT 新版本
-- 受影响行 = 0 → 被并发抢先，抛 OptimisticLockingFailureException
```

不用 MyBatis-Plus `@Version`：那个方案是原地 UPDATE 的乐观锁，覆盖不到我们"下线旧行 + 插入新行"两步操作。

### 3.3 写新版本是统一动作

不论 LLM 改、手动改、重新解析、回滚——**全都走 `ModificationService.applyChange`**：

```sql
-- 事务内：
-- 1) 下线当前
UPDATE thing_models SET is_current=FALSE
WHERE id=#{currentId} AND is_current=TRUE;

-- 2) 插新版本（version+1, is_current=TRUE）
INSERT INTO thing_models (
    chunk_id, version, schema_version, model, derived_from, is_current,
    user_intent, patch, change_type, created_by
) VALUES (?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?);
```

`patch` 是 RFC 6902 JSON Patch。LLM 路径下，后端入库前做 `apply(patch, current) == newModel` 严格校验——这是防"模型嘴上说改 A 实际改了 B"的最后一道防线。manual_edit 路径下 patch 由前端构造，同样过校验。

### 3.4 全局变更串行化

用户说"把所有 temperature 字段改成华氏"——

```sql
-- 1) GIN(jsonb_path_ops) 反查受影响 chunk
SELECT * FROM thing_models
WHERE is_current=TRUE
  AND chunk_id IN (SELECT id FROM api_chunks WHERE document_id=?)
  AND model @> '{"fields":[{"name":"temperature"}]}'::jsonb;

-- 2) 对每个 chunk 串行跑一次完整 applyChange
--    (每个独立事务 + 独立版本 + 独立 patch)
```

不用一个大事务批量改 N 个 chunk——中间任何一个失败时"该回滚还是保留已成功的"两难，串行做每个版本史都是清晰的、可单独回滚的。

---

## 4. 数据检索（核心：两个展示场景）

### 4.1 用户路径

```
┌─ 场景 1: 浏览生成的物模型 ────────────────────────────────────┐
│  上传完进文档页 → 看 chunk 列表 → 点开某个看对照 → 看版本时间线 │
└────────────────────────────────────────────────────────────────┘

┌─ 场景 2: 修改某物模型并对比前后 ──────────────────────────────┐
│  对照页里说"把 X 改成 Y" → LLM 落库新版本 → 返回前后对照视图    │
│  或在对照页里直接点字段改值 → 前端构造 patch → 落库 → 前后对照  │
└────────────────────────────────────────────────────────────────┘
```

| 用户操作 | Service 调用 | SQL 数 |
|---|---|---|
| 进文档首页 | `DocumentQueryService.getOverview` | 1（聚合） |
| 浏览 chunk 列表 | `DocumentQueryService.listChunks` | 2（数据 + count） |
| 点开 chunk 对照 | `DocumentQueryService.inspectChunk` | 3 |
| 比较两版差异 | `DocumentQueryService.compareVersions` | 2（取两版） |
| LLM 自然语言修改 | `LlmEditService.edit` | 4 读 + 写新版本 |
| 直接编辑某字段 | `ModificationService.applyChange` | 写新版本 |
| 回到旧版本 | `ModificationService.revertTo` | 写新版本 |

### 4.2 文档概览（一次聚合）

```sql
SELECT d.*,
       COUNT(c.id)                                          AS total_chunks,
       COUNT(c.id) FILTER (WHERE c.status = 'parsed')       AS parsed_chunks,
       COUNT(c.id) FILTER (WHERE c.status = 'parse_failed') AS failed_chunks
FROM documents d
LEFT JOIN api_chunks c ON c.document_id = d.id
WHERE d.id = #{documentId}
GROUP BY d.id;
```

### 4.3 chunk 列表（轻量，分页）

```sql
SELECT c.id, c.api_name, c.page_start, c.page_end, c.status, c.summary,
       tm.version       AS current_version,
       tm.change_type   AS last_change_type,
       tm.created_at    AS last_modified_at
FROM api_chunks c
LEFT JOIN thing_models tm
       ON tm.chunk_id = c.id AND tm.is_current = TRUE
WHERE c.document_id = #{documentId}
  AND (#{status} IS NULL OR c.status = #{status})
ORDER BY c.page_start
LIMIT #{size} OFFSET #{offset};
```

**故意不返回 `tm.model`**——列表场景用不上，JSONB 走网络很贵。前端列表行展示：API 名、页码、当前版本号、最近变更类型（"agent 解析"、"用户修改"…）、最近修改时间。

### 4.4 chunk 对照视图（场景 1 的核心展示）

`DocumentQueryService.inspectChunk(chunkId)` 用 3 条 SQL 拼出展示数据：

```sql
-- a) chunk 元信息 + 原文 + source_excerpts（一次拿齐）
SELECT * FROM api_chunks WHERE id = #{chunkId};

-- b) 当前生效物模型（含 model 主体）
SELECT * FROM thing_models WHERE chunk_id = #{chunkId} AND is_current = TRUE;

-- c) 全部版本时间线（不含 model 主体，省网络）
SELECT id, version, schema_version, derived_from, is_current,
       change_type, user_intent, created_by, created_at
FROM thing_models WHERE chunk_id = #{chunkId}
ORDER BY version DESC;
```

返回结构：

```
ChunkInspectView {
    chunk:              { rawText, pageStart/end, sourceExcerpts, ... },
    currentThingModel:  { version, model: {...} },
    sourceExcerpts:     {...},      // 字段→原文位置映射
    versions: [                     // 时间线
      { version: 3, changeType: "llm_edit",      userIntent: "把上报间隔改成 30",  createdBy, createdAt },
      { version: 2, changeType: "manual_edit",   userIntent: "改单位",            createdBy, createdAt },
      { version: 1, changeType: "initial_parse", userIntent: null,                createdBy: "system" }
    ]
}
```

**前端典型布局**：

```
┌────────────────────────────┬─────────────────────────────┐
│ 原文 (p.11-13)             │  当前物模型 v3              │
│ ## 3.4 温度查询 API        │  {                          │
│ 请求: GET /devices/{id}/   │    "fields": [              │
│                            │      { "name": "unit", ... },│
│ - reportInterval: 1-3600   │  ◀ │ "name":"reportInterval",│ ← 鼠标移过来时
│   单位秒，默认 30          │      "default": 30,         │   左侧自动滚到
│ ...                        │      ... },                 │   p.12 对应行高亮
│                            │    ]                        │
│                            │  }                          │
└────────────────────────────┴─────────────────────────────┘

时间线:
  v3  llm_edit       2026-05-07  user-42   "把上报间隔改成 30"
  v2  manual_edit    2026-05-06  user-42   "改单位"
  v1  initial_parse  2026-05-05  system
```

`source_excerpts` 让"鼠标悬停字段→原文高亮"这种交互**无需额外请求**。

### 4.5 喂给 LLM 的修改上下文（4 条 SQL）

`ChunkContextService.loadByChunkId(chunkId)`：

```sql
SELECT * FROM api_chunks WHERE id = ?;            -- chunk 本身
SELECT * FROM documents  WHERE id = ?;            -- skeleton_json 在这（§1.5 所有字段）
SELECT * FROM thing_models WHERE chunk_id=? AND is_current=TRUE;  -- 当前版本
-- LlmEditService 另外取最近 3 版做"变更历史"段：
SELECT * FROM thing_models WHERE chunk_id=? AND is_current=FALSE
ORDER BY version DESC LIMIT 3;
```

`PromptBuilder` 把这些数据组织成下面九段（每段一个 markdown 标题），拼成 user message：

| # | 段落 | 数据来源 | 体量 |
|---|---|---|---|
| 1 | `# 文档背景` | `skeleton.documentMeta` + `skeleton.summary.abstract` + `summary.scope.excludes` | ~300-500 tok |
| 2 | `# 文档骨架` | `skeleton.outline` | ~500-1000 tok |
| 3 | `# 全局约定` | `skeleton.conventions`（仅当存在） | ~200-400 tok |
| 4 | `# 全局术语表` | `skeleton.glossary`（截顶 40 条） | ~500-1500 tok |
| 5 | `# 当前修改对象` | `chunk` 元信息 | ~100 tok |
| 6 | `# 物模型 JSON Schema` | 调用方传入 | ~1000-2000 tok |
| 7 | `# 当前物模型` | `currentThingModel.model` | ~500-2000 tok |
| 8 | `# 最近变更历史` | 最近 3 个非当前版本 | ~200-500 tok |
| 9 | `# 原文片段` | `chunk.rawText`（截顶 12K 字符） | ~2000-4000 tok |

**总量 6K-12K token**——与 PDF 总页数无关。前 4 段是"全局视野"（所有 chunk 共享），中间 4 段是"当前任务"，最后 1 段是用户原话。

### 4.6 修改前后对比（场景 2 的核心展示）

LLM 编辑链路结尾，`LlmEditService.edit` **不返回单一新版本**，返回完整的 `EditPreview`：

```
EditPreview {
    chunkId, changeType: "llm_edit", attempts: 1,
    beforeVersion: 3, beforeModel: { ... },
    afterVersion:  4, afterModel:  { ... },
    diff: ModelDiff {
        changes: [
            { type: "modified", path: "fields[1].default",
              before: 60, after: 30,
              description: "fields[1].default: 60 → 30" }
        ],
        summary:  "fields[1].default: 60 → 30",
        rawPatch: [{op:"replace", path:"/fields/1/default", value:30, fromValue:60}]
    },
    explanation: "依据 p.12 原文'1-3600，默认 30'调整默认值",
    warnings: []
}
```

`ModelDiff` 由 `ModelDiffService` 用 `JsonDiff.asJson(before, after)` 计算（`OMIT_MOVE_OPERATION + OMIT_COPY_OPERATION` 让差异只剩 add/remove/modified 三种基础类型，前端展示更直观）。

**前端典型布局**：

```
┌──────────────┬──────────────┐
│  Before v3   │  After  v4   │
│  {model JSON}│  {model JSON}│
└──────────────┴──────────────┘
变更摘要: fields[1].default: 60 → 30
LLM 解释: 依据 p.12 原文"1-3600，默认 30"调整默认值
[✓ 接受]  [↶ 回滚到 v3]
```

manual_edit 路径下也走同一个 `EditPreview` 结构——只是 `explanation/warnings` 一般为空、`changeType="manual_edit"`、`attempts=1`。**前端展示组件不用区分两种修改源**。

### 4.7 比较任意两版

用户想看历史上 v2 → v3 改了什么：

```sql
SELECT * FROM thing_models WHERE chunk_id=? AND version=?;  -- v2
SELECT * FROM thing_models WHERE chunk_id=? AND version=?;  -- v3
```

然后 `ModelDiffService.diff(v2.model, v3.model)` 输出同样的 `ModelDiff` 结构。前端可以复用 §4.6 的 diff 渲染组件。

### 4.8 跨 chunk 反查

"找出所有用了 temperature 字段的物模型"：

```sql
SELECT * FROM thing_models
WHERE is_current = TRUE
  AND chunk_id IN (SELECT id FROM api_chunks WHERE document_id = ?)
  AND model @> CAST(? AS jsonb);
```

`fragmentJson` 例如 `{"fields":[{"name":"temperature"}]}`——走 `GIN(jsonb_path_ops)` 索引，毫秒级。

---

## 附：关键 SQL 速查

```sql
-- 上传去重
SELECT id FROM documents WHERE content_hash = ? LIMIT 1;

-- 取当前生效物模型
SELECT * FROM thing_models WHERE chunk_id = ? AND is_current = TRUE;

-- 业务级乐观锁
UPDATE thing_models SET is_current = FALSE
WHERE id = ? AND is_current = TRUE;   -- 受影响行必须 = 1

-- 写新版本（统一动作，所有 change_type 都走它）
INSERT INTO thing_models (
    chunk_id, version, schema_version, model, derived_from, is_current,
    user_intent, patch, change_type, created_by
) VALUES (?, ?, ?, ?, ?, TRUE, ?, ?, ?, ?);

-- 文档概览
SELECT d.*, COUNT(c.*) total,
       COUNT(c.*) FILTER (WHERE c.status='parsed') parsed,
       COUNT(c.*) FILTER (WHERE c.status='parse_failed') failed
FROM documents d LEFT JOIN api_chunks c ON c.document_id = d.id
WHERE d.id = ? GROUP BY d.id;

-- chunk 对照视图（核心展示）
SELECT * FROM api_chunks WHERE id = ?;
SELECT * FROM thing_models WHERE chunk_id = ? AND is_current = TRUE;
SELECT id, version, change_type, user_intent, created_by, created_at
FROM thing_models WHERE chunk_id = ? ORDER BY version DESC;

-- 反查包含某字段的物模型
SELECT * FROM thing_models
WHERE is_current = TRUE
  AND model @> CAST(? AS jsonb);
```
