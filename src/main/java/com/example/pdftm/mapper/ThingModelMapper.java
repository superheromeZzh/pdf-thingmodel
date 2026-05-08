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
     * 按 chunkId 取当前生效物模型；每 chunk 至多一行（chunk_id 即主键）。
     *
     * @param chunkId chunk 主键
     * @return 物模型对象；尚未生成时返回 null
     */
    ThingModel findByChunkId(@Param("chunkId") Long chunkId);

    /**
     * 写入或覆盖某 chunk 的物模型；最后写者胜，无版本/审计/乐观锁。
     *
     * @param chunkId 目标 chunk 主键
     * @param model   完整的新物模型 JSON
     * @return 受影响行数
     */
    int upsert(@Param("chunkId") Long chunkId,
               @Param("model") JsonNode model);

    /**
     * 跨 chunk 反查：找出某文档下所有物模型里包含给定 JSON 片段的物模型。
     *
     * @param documentId   限定在哪份文档下查
     * @param fragmentJson 要包含的 JSON 片段（字符串形式，由 SQL CAST 成 jsonb）
     * @return 命中的物模型列表；无命中返回空列表
     */
    List<ThingModel> findContainingFragment(@Param("documentId") Long documentId,
                                            @Param("fragmentJson") String fragmentJson);
}
