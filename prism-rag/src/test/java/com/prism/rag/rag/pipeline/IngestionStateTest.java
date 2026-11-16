package com.prism.rag.rag.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入库状态机测试。
 *
 * <p>状态机是入库流水线的"交通规则"，
 * 合法流转必须通、非法流转必须炸——这两条都要测。</p>
 */
class IngestionStateTest {

    @Test
    @DisplayName("正常主流程可以逐级流转")
    void shouldAllowHappyPath() {
        assertTrue(IngestionState.PENDING.canTransitionTo(IngestionState.PARSING));
        assertTrue(IngestionState.PARSING.canTransitionTo(IngestionState.CHUNKING));
        assertTrue(IngestionState.CHUNKING.canTransitionTo(IngestionState.EMBEDDING));
        assertTrue(IngestionState.EMBEDDING.canTransitionTo(IngestionState.PERSISTING));
        assertTrue(IngestionState.PERSISTING.canTransitionTo(IngestionState.COMPLETED));
    }

    @Test
    @DisplayName("任意中间态都可以转 FAILED / CANCELLED")
    void shouldAllowFailureFromAnyMiddleState() {
        for (IngestionState state : new IngestionState[]{
                IngestionState.PENDING, IngestionState.PARSING, IngestionState.CHUNKING,
                IngestionState.EMBEDDING, IngestionState.PERSISTING}) {
            assertTrue(state.canTransitionTo(IngestionState.FAILED), state + " 应可转 FAILED");
            assertTrue(state.canTransitionTo(IngestionState.CANCELLED), state + " 应可转 CANCELLED");
        }
    }

    @Test
    @DisplayName("终态只能通过重试回到 PENDING")
    void shouldAllowRetryFromTerminalState() {
        assertTrue(IngestionState.FAILED.canTransitionTo(IngestionState.PENDING));
        assertTrue(IngestionState.CANCELLED.canTransitionTo(IngestionState.PENDING));
        assertTrue(IngestionState.COMPLETED.canTransitionTo(IngestionState.PENDING));
    }

    @Test
    @DisplayName("跳步流转必须被拒绝")
    void shouldRejectIllegalTransition() {
        // 不允许从 PENDING 直接跳到 CHUNKING
        assertFalse(IngestionState.PENDING.canTransitionTo(IngestionState.CHUNKING));
        assertThrows(IllegalStateException.class,
                () -> IngestionState.PENDING.assertTransitionTo(IngestionState.CHUNKING));
    }

    @Test
    @DisplayName("COMPLETED 不能直接回到 PARSING")
    void shouldRejectCompletedToParsing() {
        assertFalse(IngestionState.COMPLETED.canTransitionTo(IngestionState.PARSING));
        assertThrows(IllegalStateException.class,
                () -> IngestionState.COMPLETED.assertTransitionTo(IngestionState.PARSING));
    }

    @Test
    @DisplayName("终态判定正确")
    void shouldDetectTerminalStates() {
        assertTrue(IngestionState.COMPLETED.isTerminal());
        assertTrue(IngestionState.FAILED.isTerminal());
        assertTrue(IngestionState.CANCELLED.isTerminal());
        assertFalse(IngestionState.PARSING.isTerminal());
    }
}
