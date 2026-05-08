package com.example.pdftm.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * SkeletonExtractor 的输出。skeletonJson 直接写到 documents.skeleton_json；
 * detectedChunks 用于驱动阶段 2 的 chunk INSERT，不持久化。
 */
@Data
@Builder
public class ExtractedSkeleton {

    /**
     * 全局骨架 JSON：documentMeta / summary / conventions / outline /
     * sharedSchemas / apiIndex / glossary（结构契约见 Document.java）。
     */
    private JsonNode skeletonJson;

    /** 检测到的 chunk 列表，按 pageStart 升序 */
    private List<DetectedChunk> detectedChunks;
}
