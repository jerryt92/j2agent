package io.github.jerryt92.j2agent.service.llm.reasoning;

import org.springframework.util.StringUtils;

/**
 * 将 Provider 流式 metadata 中的<strong>累积 reasoning 快照</strong>转为<strong>增量 delta</strong>。
 *
 * <p>部分 ChatModel（如 Ollama）每个 chunk 的 {@code Generation.metadata.thinking} 携带截至当前的
 * 全量思考文本，而非单 chunk 增量。本类维护上一快照，输出前缀差分作为本次 {@code reasoningDelta}。</p>
 *
 * <p>每轮流式对话应新建一个实例；不可跨 turn 复用。</p>
 */
public class ReasoningSnapshotTracker {

    /**
     * 上一 chunk 见到的累积 reasoning 快照
     */
    private String lastSnapshot;

    /**
     * 将本次累积快照转为相对上次的增量。
     *
     * @param cumulativeSnapshot 当前 chunk metadata 中的全量 reasoning 文本
     * @return 增量 delta；无变化或与上次相同时返回 {@code null}
     */
    public String toDelta(String cumulativeSnapshot) {
        if (!StringUtils.hasText(cumulativeSnapshot)) {
            return null;
        }
        if (lastSnapshot == null) {
            lastSnapshot = cumulativeSnapshot;
            return cumulativeSnapshot;
        }
        if (cumulativeSnapshot.equals(lastSnapshot)) {
            return null;
        }
        if (cumulativeSnapshot.startsWith(lastSnapshot)) {
            String delta = cumulativeSnapshot.substring(lastSnapshot.length());
            lastSnapshot = cumulativeSnapshot;
            return delta.isEmpty() ? null : delta;
        }
        // 非前缀延续（Provider 重置或异常）：整段替换并输出全量
        lastSnapshot = cumulativeSnapshot;
        return cumulativeSnapshot;
    }
}
