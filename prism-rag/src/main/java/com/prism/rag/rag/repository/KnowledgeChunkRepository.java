package com.prism.rag.rag.repository;

import com.prism.rag.rag.domain.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 分块仓储。
 */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    List<KnowledgeChunk> findByDocIdOrderBySeqAsc(Long docId);

    List<KnowledgeChunk> findByKbId(Long kbId);

    long countByKbId(Long kbId);

    void deleteByDocId(Long docId);

    List<KnowledgeChunk> findByIdIn(Collection<Long> ids);
}
