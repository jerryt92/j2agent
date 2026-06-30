package io.github.jerryt92.j2agent.logging.llm;

/**
 * Agent 单轮运行日志事件类型。
 */
public enum AgentRunEventType {
    TURN_START,
    TURN_END,
    CHAT,
    TOOL_START,
    TOOL_SUCCESS,
    TOOL_FAILURE,
    TOOL_ERROR_RETURN,
    SKILL_LOAD,
    RAG_SKIP,
    RAG_TRANSFORM,
    RAG_RETRIEVE,
    RAG_SOURCE,
    LLM_RETRY,
    /** 通用助手编排：开放意图召回 LLM */
    INTENT_RECALL,
    ERROR
}
