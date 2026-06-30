package io.github.jerryt92.j2agent.service.llm.chat;

import reactor.core.Disposable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 按 turnId 协作式取消：WebSocket 关闭时标记并 dispose 已注册的流式订阅（主流与子智能体流）。
 */
public final class ChatTurnCancellationRegistry {

    private static final Set<String> CANCELLED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, CopyOnWriteArraySet<Disposable>> DISPOSABLES =
            new ConcurrentHashMap<>();

    private ChatTurnCancellationRegistry() {
    }

    public static void clear(String turnId) {
        if (turnId == null) {
            return;
        }
        CANCELLED.remove(turnId);
        DISPOSABLES.remove(turnId);
    }

    /** 仅移除 disposable 登记，保留取消标记供仍在执行的协作式检查使用。 */
    public static void clearDisposables(String turnId) {
        if (turnId == null) {
            return;
        }
        DISPOSABLES.remove(turnId);
    }

    public static boolean isCancelled(String turnId) {
        return turnId != null && CANCELLED.contains(turnId);
    }

    public static void registerDisposable(String turnId, Disposable disposable) {
        if (turnId == null || disposable == null) {
            return;
        }
        if (isCancelled(turnId)) {
            if (!disposable.isDisposed()) {
                disposable.dispose();
            }
            return;
        }
        DISPOSABLES.computeIfAbsent(turnId, ignored -> new CopyOnWriteArraySet<>()).add(disposable);
    }

    public static void cancel(String turnId) {
        if (turnId == null) {
            return;
        }
        CANCELLED.add(turnId);
        CopyOnWriteArraySet<Disposable> disposables = DISPOSABLES.remove(turnId);
        if (disposables == null) {
            return;
        }
        for (Disposable disposable : disposables) {
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
        }
    }
}
