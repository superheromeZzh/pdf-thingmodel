# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & run

Maven-based Spring Boot 3.2 / Java 17 project. Persistence layer is plain MyBatis (`mybatis-spring-boot-starter:3.0.3`) — **not** MyBatis-Plus. There is no MP `BaseMapper`, no `@TableName` / `@TableField` / `@TableId` on domain classes, no `QueryWrapper`, and no `@Version`. **All SQL lives in XML mapper files** under `src/main/resources/mapper/*.xml` — the Java mapper interfaces are bare method signatures with `@Param` only. Don't reintroduce `@Select` / `@Insert` / `@Results` / `@ResultMap` annotations on the interfaces; new queries must be added to the matching XML.

| Task | Command |
|---|---|
| Compile | `mvn compile` |
| Build jar | `mvn clean package` (Lombok is excluded from the boot jar by config) |
| Run app | `mvn spring-boot:run` |
| Run tests | `mvn test` (no tests written yet — `src/test` is empty) |
| Run a single test | `mvn -Dtest=ClassName test` or `mvn -Dtest=ClassName#method test` |

The app **requires a running database to start** — Hikari connects on boot. Set `DB_PASSWORD` env var; default JDBC URL is `jdbc:opengauss://127.0.0.1:5432/pdftm` (see `src/main/resources/application.yml`). Apply `src/main/resources/schema.sql` manually before first run; there is no Flyway/Liquibase.

The driver is **openGauss-jdbc** (`org.opengauss.Driver`). The schema is PostgreSQL-compatible (JSONB, GIN indexes, `ON CONFLICT`), so swapping in `org.postgresql:postgresql` works for local dev — the alternate dependency is commented in `pom.xml`.

> **Heads-up: pre-existing build issue.** `JsonbTypeHandler` imports `org.postgresql.util.PGobject`, but `pom.xml` ships with the `org.postgresql:postgresql` dependency commented out. With only `opengauss-jdbc` on the classpath you'll get `程序包org.postgresql.util不存在` at compile time. Either uncomment the postgresql dep in `pom.xml`, or change the import to `org.opengauss.util.PGobject` (the openGauss driver also exports a PGobject under its own package).

There is **no HTTP layer** yet: the project pulls `spring-boot-starter` (not `-web`), so there are no controllers and no port is opened. All entry points are Java services. Adding controllers later means adding `spring-boot-starter-web`.

## Architecture

> ⚠️ **`docs/design.md` is now historical.** It documents an earlier, much richer schema with multi-version `thing_models` (audit trail, `is_current` flag, `derived_from` chain, RFC 6902 patches stored on every row), upload-dedupe via `content_hash`, parse-state machine on chunks, and field→source-excerpt mapping. The current schema deliberately strips all of that. Use design.md for *intent and prompt-design rationale* (skeleton-as-anchor, LLM safety contract, JSONB choice, etc.) but **not** for the concrete table shape — read `src/main/resources/schema.sql` for ground truth.

### Three tables, deliberately

`documents` → `document_chunks` → `thing_models` in a 1:N:1 relationship. Schema is minimal:

| Table | Columns |
|---|---|
| `documents` | `document_id`, `document_name`, `page_count`, `skeleton_json` (JSONB) |
| `document_chunks` | `chunk_id`, `document_id`, `chunk_name`, `page_start`, `page_end`, `raw_text`, `summary` |
| `thing_models` | `chunk_id` (PK = FK), `model` (JSONB) |

`thing_models.chunk_id` is both primary key and foreign key — **one model per chunk, no history**. Don't reintroduce `version` / `is_current` / `derived_from` / `patch` / `change_type` / `created_by` columns, or a separate `modifications` table; the deliberate trade-off here is "last-writer-wins" with the audit trail dropped. `glossary` lives inside `documents.skeleton_json` (a JSONB blob with `documentMeta / summary / conventions / outline / sharedSchemas / apiIndex / glossary`) — don't promote it to a table.

### Single write path

All writes to `thing_models` go through `ModificationService.upsertModel(chunkId, model)`, which calls `ThingModelMapper.upsert(...)` — a single `INSERT ... ON CONFLICT (chunk_id) DO UPDATE SET model = EXCLUDED.model`. There is **no optimistic locking**: concurrent writers are last-writer-wins. If a use case needs collaborative editing safety, add it at this seam (e.g., row-level lock or hash-of-current as a precondition).

### LLM safety contract

In `LlmEditService.edit`, every LLM response must pass three gates before `upsertModel`:

1. **Parseable JSON** with both `patch` (array) and `newModel` (object) fields.
2. **`newModel` validates** against the JSON Schema passed in by the caller (networknt `json-schema-validator`, draft 2020-12).
3. **`apply(patch, currentModel) == newModel`** byte-for-byte (`zjsonpatch.JsonPatch.apply` + `JsonNode.equals`). This catches "model said it changed A but actually changed B" — the most important defense, even though we no longer persist the patch.

Failure → up to `MAX_ATTEMPTS = 3` retries with the error message appended to the user request as feedback. **The LLM call (`llmClient.generate`) is outside any `@Transactional` boundary** — a multi-second LLM call holding a DB connection + row lock will starve the pool. Only `upsertModel` is transactional.

### Skeleton travels in every prompt

`documents.skeleton_json` (~1.5KB, ~1500–2500 tokens) is sent **on every single-chunk edit**. `PromptBuilder.buildEditPrompt` lays out the prompt in fixed order: document background → outline → conventions → glossary → chunk meta → JSON schema → current model → raw text → user request. Total prompt size is ~6K–12K tokens, **independent of PDF length** — that's the whole point of the chunk-plus-skeleton design. Don't change the section order without also updating the LLM expectations in the system prompt.

(There used to be a "recent variations history" section sourced from past `thing_models` versions; with versioning gone, that section is removed.)

### What's implemented vs designed

The storage core and edit/query loops are real. The ingestion pipeline is **paper-only**:

| Component | State |
|---|---|
| Domain + mappers + JSONB type handler | done |
| `ModificationService.upsertModel` | done |
| `LlmEditService` (parse, schema-validate, patch-verify, retry) | done |
| `PromptBuilder`, `ChunkContextService`, `ModelDiffService` | done |
| `DocumentQueryService` (overview / listChunks / inspectChunk) | done |
| `LlmClient` interface | done — **no implementation yet**; needs DashScope/Anthropic/OpenAI adapter |
| Ingestion (upload, skeleton extraction, chunk split, chunk parse worker) | not started |
| HTTP controllers | none |
| Tests | none |

When adding the LLM client, follow the contract in `LlmClient`'s javadoc: handle retry/timeout/auth at that layer; do **not** parse the response there — that's `LlmEditService`'s job.

## Conventions specific to this repo

- **JSONB columns are wired through `JsonbTypeHandler`** in two ways:
  1. Globally registered via `mybatis.type-handlers-package=com.example.pdftm.handler` plus `@MappedTypes(JsonNode.class)` + `@MappedJdbcTypes(JdbcType.OTHER, includeNullJdbcType=true)` on the handler.
  2. Explicitly referenced in XML — every JSONB-bearing `<resultMap>` uses `<result column="..." property="..." typeHandler="com.example.pdftm.handler.JsonbTypeHandler"/>`, and every JSONB-writing `#{...}` uses `#{model,jdbcType=OTHER,typeHandler=com.example.pdftm.handler.JsonbTypeHandler}`.

  Don't rely solely on the global registration — the explicit form is the convention here, because it makes JSONB columns obvious in the SQL. Each XML defines a reusable named `<resultMap>` (`DocumentResult`, `DocumentChunkResult`, `ThingModelResult`, `ChunkListItemResult`, `DocumentOverviewResult`) that all queries on that table reuse via `resultMap="..."`.
- **`MapperScan`** in `PdfThingModelApplication` scans `com.example.pdftm.mapper`. New mappers go there as bare Java interfaces; all their SQL goes in a matching `src/main/resources/mapper/<MapperName>.xml` with `<mapper namespace="com.example.pdftm.mapper.MapperName">`. The `mybatis.mapper-locations: classpath*:mapper/**/*.xml` config in `application.yml` picks them up.
- **PostgreSQL `@>` containment in XML.** Wrap any SQL containing `@>` in `<![CDATA[ ... ]]>` (see `findContainingFragment` in `ThingModelMapper.xml`) — XML element content treats `<` as markup, and CDATA also makes it visually obvious that the block is "raw SQL, hands off."
- **No `BaseMapper`-style inheritance.** Each mapper declares only the methods it actually needs. If you need `selectById` somewhere new, write it explicitly — see the existing `selectById` in each mapper.
- **`ModelDiff` rawPatch uses `OMIT_MOVE_OPERATION + OMIT_COPY_OPERATION`** so diffs only contain add/remove/replace. This is intentional — the frontend renderer expects three change kinds (`added` / `removed` / `modified`). Don't widen.
- **Comments and doc strings in this codebase are in Chinese.** Match the existing style when editing — don't translate existing comments to English.
