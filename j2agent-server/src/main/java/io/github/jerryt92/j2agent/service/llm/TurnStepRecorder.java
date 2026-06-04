package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ToolCallEventPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 录制单轮 Agent UI 事件的状态轨迹，逻辑与前端 {@code useAgentEventDispatcher} 对齐。
 */
public final class TurnStepRecorder {

    private static final AgentState[] TOOL_TRAIL_STATES = {
            AgentState.CALLING_TOOL, AgentState.LOAD_SKILL
    };

    private final List<TurnStepItem> steps = new ArrayList<>();

    public void record(AgentUiEventEnvelope event) {
        if (event == null) {
            return;
        }
        String trailName = resolveTrailDisplayName(event);
        Long ts = event.getTs();
        if (event.getTransition() != null) {
            if (!shouldSkipTransitionFromOnExit(event)) {
                appendStep(
                        event.getTransition().getFrom(),
                        suffixNameForState(event.getTransition().getFrom(), trailName),
                        ts);
            }
            appendStep(
                    event.getTransition().getTo(),
                    suffixNameForState(event.getTransition().getTo(), trailName),
                    ts);
            return;
        }
        appendStep(
                event.getState(),
                suffixNameForState(event.getState(), trailName),
                ts);
    }

    private static String suffixNameForState(AgentState state, String trailName) {
        if (state == null) {
            return null;
        }
        for (AgentState toolState : TOOL_TRAIL_STATES) {
            if (toolState == state) {
                return trailName;
            }
        }
        return null;
    }

    private static boolean shouldSkipTransitionFromOnExit(AgentUiEventEnvelope event) {
        if (event.getTransition() == null || event.getTransition().getTo() != AgentState.THINKING) {
            return false;
        }
        if (event.getEventType() != AgentEventType.TOOL) {
            return false;
        }
        if (event.getPhase() != AgentEventPhase.COMPLETE && event.getPhase() != AgentEventPhase.ERROR) {
            return false;
        }
        AgentState from = event.getTransition().getFrom();
        return from == AgentState.LOAD_SKILL || from == AgentState.CALLING_TOOL;
    }

    private void appendStep(AgentState state, String toolName, Long ts) {
        if (state == null) {
            return;
        }
        TurnStepItem last = steps.isEmpty() ? null : steps.get(steps.size() - 1);
        if (last != null && last.state() == state) {
            if (toolName != null && (last.toolName() == null || last.toolName().isBlank())) {
                steps.set(steps.size() - 1, new TurnStepItem(state, toolName, ts != null ? ts : last.ts()));
                return;
            }
            if (toolName != null && !toolName.equals(last.toolName())) {
                steps.add(new TurnStepItem(state, toolName, ts));
            }
            return;
        }
        steps.add(new TurnStepItem(state, toolName, ts));
    }

    private static String resolveTrailDisplayName(AgentUiEventEnvelope event) {
        if (event.getEventType() != AgentEventType.TOOL) {
            return resolveToolName(event);
        }
        ToolPayloadView payload = extractToolPayload(event);
        if (payload == null) {
            return null;
        }
        boolean inLoadSkill = event.getState() == AgentState.LOAD_SKILL
                || (event.getTransition() != null && event.getTransition().getTo() == AgentState.LOAD_SKILL)
                || (event.getTransition() != null && event.getTransition().getFrom() == AgentState.LOAD_SKILL);
        if (inLoadSkill) {
            String skillName = payload.skillName();
            if (skillName != null && !skillName.isBlank()) {
                String skillId = skillName.trim();
                String relativePath = parseRelativePathFromArguments(payload.arguments());
                return relativePath != null ? skillId + "/" + relativePath : skillId;
            }
        }
        return payload.toolName();
    }

    private static ToolPayloadView extractToolPayload(AgentUiEventEnvelope event) {
        Object payload = event.getPayload();
        if (payload instanceof ToolCallEventPayload tcp) {
            return new ToolPayloadView(
                    blankToNull(tcp.getToolName()),
                    blankToNull(tcp.getSkillName()),
                    tcp.getArguments());
        }
        if (payload instanceof Map<?, ?> map) {
            return new ToolPayloadView(
                    stringFromMap(map, "toolName"),
                    stringFromMap(map, "skillName"),
                    map.get("arguments"));
        }
        return null;
    }

    private static String resolveToolName(AgentUiEventEnvelope event) {
        ToolPayloadView payload = extractToolPayload(event);
        return payload != null ? payload.toolName() : null;
    }

    private static String stringFromMap(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String parseRelativePathFromArguments(Object argumentsJson) {
        if (!(argumentsJson instanceof String s) || s.isBlank()) {
            return null;
        }
        try {
            com.alibaba.fastjson2.JSONObject parsed = com.alibaba.fastjson2.JSON.parseObject(s);
            Object relative = parsed.get("relative_path");
            if (relative == null) {
                relative = parsed.get("relativePath");
            }
            if (relative instanceof String rs && !rs.isBlank()) {
                return rs.trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public List<TurnStepItem> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }

    private record ToolPayloadView(String toolName, String skillName, Object arguments) {
    }
}
