package com.example.pdftm.mapper;

import com.example.pdftm.domain.DocumentChunk;
import com.example.pdftm.dto.ChunkListItem;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * SQL 全部在 src/main/resources/mapper/DocumentChunkMapper.xml。
 */
public interface DocumentChunkMapper {

    DocumentChunk selectById(@Param("chunkId") Long chunkId);

    List<DocumentChunk> listByDocument(@Param("documentId") Long documentId);

    List<ChunkListItem> listChunkSummaries(@Param("documentId") Long documentId,
                                           @Param("offset") int offset,
                                           @Param("size") int size);

    long countChunkSummaries(@Param("documentId") Long documentId);
}
