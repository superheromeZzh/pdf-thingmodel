package com.example.pdftm.mapper;

import com.example.pdftm.domain.ThingModel;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL 全部在 src/main/resources/mapper/ThingModelMapper.xml。
 */
public interface ThingModelMapper {

    /** 取某 chunk 当前物模型；未生成时返回 null */
    ThingModel findByChunkId(@Param("chunkId") Long chunkId);

    /**
     * 写入或覆盖：每 chunk 只保留一行。
     * 没有版本/审计/乐观锁——并发写最后写者胜。
     */
    int upsert(@Param("chunkId") Long chunkId,
               @Param("model") JsonNode model);

    /**
     * 跨 chunk 反查：找出当前所有物模型里包含某 JSON 片段的物模型。
     * 例 fragmentJson = '{"fields":[{"name":"temperature"}]}'
     */
    List<ThingModel> findContainingFragment(@Param("documentId") Long documentId,
                                            @Param("fragmentJson") String fragmentJson);
}
