package com.prism.rag.rag.repository;

import com.prism.rag.rag.domain.KnowledgeDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 文档仓储。
 */
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findByKbIdOrderByIdDesc(Long kbId);

    long countByKbId(Long kbId);

    void deleteByKbId(Long kbId);
}
