package com.prism.rag.rag.repository;

import com.prism.rag.rag.domain.IngestionTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 入库任务仓储。
 */
public interface IngestionTaskRepository extends JpaRepository<IngestionTask, Long> {

    List<IngestionTask> findByDocIdOrderByIdDesc(Long docId);

    List<IngestionTask> findTop50ByOrderByIdDesc();

    List<IngestionTask> findByStateOrderByIdAsc(String state);
}
