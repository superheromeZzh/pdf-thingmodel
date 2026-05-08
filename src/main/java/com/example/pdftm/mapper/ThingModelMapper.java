package com.example.pdftm.mapper;

import com.example.pdftm.domain.ThingModel;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * thing_models 表 mapper。SQL 全部在 src/main/resources/mapper/ThingModelMapper.xml。
 */
public interface ThingModelMapper {

    /**
     * 按 chunkId 取当前生效物模型。每 chunk 至多一行（chunk_id 即主键）。
     *
     * @param chunkId chunk 主键
     * @return 物模型对象；尚未生成时返回 null
     */
    ThingModel findByChunkId(@Param("chunkId") Long chunkId);

    /**
     * 写入或覆盖：每 chunk 只保留一行。底层走 PostgreSQL/openGauss 的
     * {@code INSERT ... ON CONFLICT (chunk_id) DO UPDATE}。
     *
     * <p>没有版本/审计/乐观锁——并发写最后写者胜。
     * updated_at 由触发器 trg_set_updated_at 自动维护，不需要调用方设置。
     *
     * @param chunkId 目标 chunk 主键
     * @param model   完整的新物模型 JSON（不能为 null）
     * @return 受影响行数（INSERT 走 1，ON CONFLICT 走 1）
     */
    int upsert(@Param("chunkId") Long chunkId,
               @Param("model") JsonNode model);

    /**
     * 跨 chunk 反查：找出某文档下所有物模型里包含给定 JSON 片段的物模型。
     * 走 {@code GIN(jsonb_path_ops)} 索引 + jsonb 包含运算符 {@code @>}。
     *
     * <p>典型场景："找所有用了 temperature 字段的物模型"——
     * fragmentJson 例如 {@code {"fields":[{"name":"temperature"}]}}。
     *
     * @param documentId   限定在哪份文档下查
     * @param fragmentJson 要包含的 JSON 片段（字符串形式，由 SQL CAST 成 jsonb）
     * @return 命中的物模型列表；无命中返回空列表
     */
    List<ThingModel> findContainingFragment(@Param("documentId") Long documentId,
                                            @Param("fragmentJson") String fragmentJson);
}
