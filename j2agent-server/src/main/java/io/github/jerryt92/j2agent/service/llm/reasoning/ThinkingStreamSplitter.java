package io.github.jerryt92.j2agent.service.llm.reasoning;

import org.springframework.util.StringUtils;

/**
 * 将 raw 流式 chunk 中嵌入的 XML 思考标签切分为 reasoning / answer 两路增量。
 *
 * <p>当 Alibaba Graph 的 {@code StreamingOutput.chunk()} 未包装为 {@code AssistantMessage} 时，
 * 部分模型会在正文中内嵌 {@code <thinking>} 或 {@code <think>} 标签；本类按标签边界
 * 状态机切分，并支持标签跨 chunk 截断时的 carry 缓冲。</p>
 *
 * <p>每轮流式对话应新建一个实例；不可跨 turn 复用。</p>
 */
public class ThinkingStreamSplitter {

    private static final String OPEN_THINKING = "<thinking>";
    private static final String CLOSE_THINKING = "</thinking>";
    private static final String OPEN_REDACTED = "<think>";
    private static final String CLOSE_REDACTED = "</think>";

    /**
     * 解析状态：标签外 / thinking 块内 / redacted_thinking 块内
     */
    private enum Phase {
        OUTSIDE,
        IN_THINKING,
        IN_REDACTED
    }

    /**
     * 单次 append 产出的双通道增量。
     *
     * @param reasoningDelta 深度思考增量
     * @param answerDelta    最终回答增量
     */
    public record ChunkSplit(String reasoningDelta, String answerDelta) {
    }

    private Phase phase = Phase.OUTSIDE;
    /**
     * 上一 chunk 末尾未完成的标签片段，拼接到下一 chunk 继续解析
     */
    private String carry = "";

    /**
     * 追加一段 raw chunk 并返回本次识别出的 reasoning / answer 增量。
     */
    public ChunkSplit append(String chunk) {
        if (!StringUtils.hasText(chunk)) {
            return emptySplit();
        }
        String input = carry + chunk;
        carry = "";
        StringBuilder reasoning = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            if (phase == Phase.OUTSIDE) {
                int next = findNextOpenTag(input, i);
                if (next < 0) {
                    appendWithCarry(input.substring(i), answer, openTagPrefixes());
                    break;
                }
                if (next > i) {
                    answer.append(input, i, next);
                }
                if (input.startsWith(OPEN_THINKING, next)) {
                    phase = Phase.IN_THINKING;
                    i = next + OPEN_THINKING.length();
                } else {
                    phase = Phase.IN_REDACTED;
                    i = next + OPEN_REDACTED.length();
                }
            } else {
                String closeTag = phase == Phase.IN_THINKING ? CLOSE_THINKING : CLOSE_REDACTED;
                int closeIdx = input.indexOf(closeTag, i);
                if (closeIdx < 0) {
                    appendWithCarry(input.substring(i), reasoning, closeTagPrefixes(phase));
                    break;
                }
                if (closeIdx > i) {
                    reasoning.append(input, i, closeIdx);
                }
                phase = Phase.OUTSIDE;
                i = closeIdx + closeTag.length();
            }
        }
        return toSplit(reasoning, answer);
    }

    private static int findNextOpenTag(String input, int from) {
        int thinking = input.indexOf(OPEN_THINKING, from);
        int redacted = input.indexOf(OPEN_REDACTED, from);
        if (thinking < 0) {
            return redacted;
        }
        if (redacted < 0) {
            return thinking;
        }
        return Math.min(thinking, redacted);
    }

    /**
     * 将 remainder 写入 target；末尾若可能是未闭合标签则存入 carry 等待下 chunk。
     */
    private void appendWithCarry(String remainder, StringBuilder target, String[] knownPrefixes) {
        int partialIdx = findPartialPrefixStart(remainder, knownPrefixes);
        if (partialIdx >= 0) {
            target.append(remainder, 0, partialIdx);
            carry = remainder.substring(partialIdx);
        } else {
            target.append(remainder);
        }
    }

    /**
     * 查找 remainder 末尾是否存在某已知标签的前缀（跨 chunk 截断检测）。
     */
    private static int findPartialPrefixStart(String text, String[] prefixes) {
        int lastLt = text.lastIndexOf('<');
        if (lastLt < 0) {
            return -1;
        }
        String tail = text.substring(lastLt);
        for (String prefix : prefixes) {
            if (prefix.startsWith(tail) && !prefix.equals(tail)) {
                return lastLt;
            }
        }
        return -1;
    }

    private static String[] openTagPrefixes() {
        return new String[]{OPEN_THINKING, OPEN_REDACTED};
    }

    private static String[] closeTagPrefixes(Phase phase) {
        return phase == Phase.IN_THINKING
                ? new String[]{CLOSE_THINKING}
                : new String[]{CLOSE_REDACTED};
    }

    private static ChunkSplit toSplit(StringBuilder reasoning, StringBuilder answer) {
        String r = reasoning.length() > 0 ? reasoning.toString() : null;
        String a = answer.length() > 0 ? answer.toString() : null;
        if (r == null && a == null) {
            return emptySplit();
        }
        return new ChunkSplit(r, a);
    }

    private static ChunkSplit emptySplit() {
        return new ChunkSplit(null, null);
    }
}
