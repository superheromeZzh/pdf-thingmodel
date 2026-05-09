package com.example.pdftm.service;

import com.example.pdftm.domain.ChunkModel;
import com.example.pdftm.mapper.ThingModelMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * thing_models 写入服务。
 *
 * 精简版只剩一个动作：upsert(chunkId, model)。
 * 没有版本/审计/乐观锁——并发写最后写者胜，调用方需自己控制冲突。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModificationService {

    private final ThingModelMapper thingModelMapper;

    /**
     * 写入或覆盖某 chunk 的物模型并返回最终生效对象。
     *
     * @param chunkId 目标 chunk 主键
     * @param model   完整的新物模型 JSON
     * @return 写入后的 ChunkModel（chunkId + thingModel）
     */
    @Transactional(rollbackFor = Exception.class)
    public ChunkModel upsertModel(Long chunkId, JsonNode model) {
        if (chunkId == null) throw new IllegalArgumentException("chunkId required");
        if (model == null)   throw new IllegalArgumentException("model required");

        thingModelMapper.upsert(chunkId, model);
        log.info("upsertModel: chunkId={} modelKeys={}", chunkId,
                model.isObject() ? model.size() : -1);

        ChunkModel saved = new ChunkModel();
        saved.setChunkId(chunkId);
        saved.setThingModel(model);
        return saved;
    }
}
