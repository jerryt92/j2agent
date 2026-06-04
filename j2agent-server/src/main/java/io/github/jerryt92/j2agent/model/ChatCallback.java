package io.github.jerryt92.j2agent.model;

import java.util.function.Consumer;

public class ChatCallback<T> {
    public final String subscriptionId;
    public Consumer<T> responseCall;
    public Runnable completeCall;
    public Consumer<Throwable> errorCall;
    public Runnable timeoutCall;
    public Runnable onWebsocketClose;

    public ChatCallback(String subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public ChatCallback(String subscriptionId, Consumer<T> responseCall, Runnable completeCall, Consumer<Throwable> errorCall, Runnable timeoutCall) {
        this.subscriptionId = subscriptionId;
        this.responseCall = responseCall;
        this.completeCall = completeCall;
        this.errorCall = errorCall;
        this.timeoutCall = timeoutCall;
    }
}
