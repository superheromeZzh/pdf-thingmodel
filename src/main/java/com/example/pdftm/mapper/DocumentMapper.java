package com.example.pdftm.mapper;

import com.example.pdftm.domain.Document;
import com.example.pdftm.dto.DocumentOverview;
import org.apache.ibatis.annotations.Param;

/**
 * SQL 全部在 src/main/resources/mapper/DocumentMapper.xml。
 */
public interface DocumentMapper {

    Document selectById(@Param("documentId") Long documentId);

    DocumentOverview findOverview(@Param("documentId") Long documentId);
}
