package com.prism.rag.rag.repository;

import com.prism.rag.rag.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 会话仓储。
 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    List<Conversation> findTop50ByOrderByUpdatedAtDesc();
}
