package io.github.jerryt92.j2agent.service.llm.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.service.llm.LlmProviderErrorFormatter;
import io.github.jerryt92.j2agent.service.llm.reasoning.AssistantMessageReasoningExtractor;
import io.github.jerryt92.j2agent.service.llm.reasoning.ReasoningSnapshotTracker;
import io.github.jerryt92.j2agent.service.llm.reasoning.ThinkingStreamSplitter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * 插件 Agent 统一流式运行时：{@code aiAgent.stream} + 重试 + 正文/推理增量解析。
 */
@Service
public class AgentStreamSession {

    public Flux<StreamingTextParts> stream(AgentStreamOptions options) {
        ReasoningSnapshotTracker reasoningTracker = new ReasoningSnapshotTracker();
        ThinkingStreamSplitter thinkingStreamSplitter = new ThinkingStreamSplitter();
        return Flux.defer(() -> {
                    options.streamStartedAtMs().set(System.currentTimeMillis());
                    try {
                        return options.aiAgent().stream(options.agentRunContext());
                    } catch (GraphRunnerException e) {
                        throw new RuntimeException(e);
                    }
                })
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(3))
                        .filter(t -> (AgentStreamRetrySupport.isConnectionResetByPeer(t)
                                || LlmProviderErrorFormatter.isEmptyStreamFailure(t))
                                && AgentStreamRetrySupport.isStillThinkingAndEmpty(
                                options.streamedContent(),
                                options.streamedReasoning(),
                                options.stateMachine(),
                                options.streamedTextLock(),
                                options.turnLock()))
                        .doBeforeRetry(rs -> {
                            int bufferedLen;
                            synchronized (options.streamedTextLock()) {
                                bufferedLen = options.streamedContent().length();
                            }
                            String currentState;
                            synchronized (options.turnLock()) {
                                currentState = options.stateMachine().getState() == null
                                        ? ""
                                        : options.stateMachine().getState().name();
                            }
                            int nextRetryNo = options.retryNo().incrementAndGet();
                            AgentRunLogger.warn(options.runLogSnapshot(), AgentRunEventType.LLM_RETRY,
                                    AgentRunLogger.kv(
                                            "retryNo", nextRetryNo,
                                            "state", currentState,
                                            "elapsedMs", System.currentTimeMillis()
                                                    - options.streamStartedAtMs().get(),
                                            "bufferedLen", bufferedLen,
                                            "errorType", rs.failure() == null ? ""
                                                    : rs.failure().getClass().getSimpleName()),
                                    LlmProviderErrorFormatter.formatForLog(rs.failure()));
                        }))
                .mapNotNull(nodeOutput -> extractStreamingParts(
                        nodeOutput, reasoningTracker, thinkingStreamSplitter))
                .doFinally(signal -> {
                    if (options.onStreamFinally() != null) {
                        options.onStreamFinally().run();
                    }
                });
    }

    static StreamingTextParts extractStreamingParts(NodeOutput nodeOutput,
                                                      ReasoningSnapshotTracker reasoningTracker,
                                                      ThinkingStreamSplitter thinkingStreamSplitter) {
        if (!(nodeOutput instanceof StreamingOutput<?> streamingOutput)) {
            return null;
        }
        if (streamingOutput.getOutputType() != OutputType.AGENT_MODEL_STREAMING) {
            return null;
        }
        Message message = streamingOutput.message();
        if (message instanceof AssistantMessage assistantMessage) {
            AssistantMessageReasoningExtractor.TextParts parts =
                    AssistantMessageReasoningExtractor.splitStreamingDelta(
                            assistantMessage, null, reasoningTracker);
            if (parts == null) {
                return null;
            }
            return new StreamingTextParts(parts.answerDelta(), parts.reasoningDelta());
        }
        String chunk = streamingOutput.chunk();
        AssistantMessageReasoningExtractor.TextParts parts =
                AssistantMessageReasoningExtractor.splitRawChunk(chunk, thinkingStreamSplitter);
        if (parts == null) {
            return null;
        }
        return new StreamingTextParts(parts.answerDelta(), parts.reasoningDelta());
    }
}
