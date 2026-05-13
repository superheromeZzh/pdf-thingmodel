package com.example.pdftm.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * SkeletonExtractor 的输出。summary 直接写到 documents.summary；
 * detectedChunks 用于驱动阶段 2 的 chunk INSERT，不持久化。
 */
@Data
@Builder
public class ExtractedSkeleton {

    /** 文档级摘要（150-300 字叙述） */
    private String summary;

    /** 检测到的 chunk 列表，按 pageStart 升序 */
    private List<DetectedChunk> detectedChunks;
}
