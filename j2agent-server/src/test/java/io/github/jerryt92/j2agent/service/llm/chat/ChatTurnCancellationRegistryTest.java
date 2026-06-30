package io.github.jerryt92.j2agent.service.llm.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTurnCancellationRegistryTest {

    @AfterEach
    void tearDown() {
        ChatTurnCancellationRegistry.clear("turn-1");
    }

    @Test
    void cancelMarksTurnAndDisposesRegisteredSubscriptions() {
        AtomicBoolean disposed = new AtomicBoolean(false);
        Disposable disposable = new Disposable() {
            @Override
            public void dispose() {
                disposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };
        ChatTurnCancellationRegistry.registerDisposable("turn-1", disposable);

        ChatTurnCancellationRegistry.cancel("turn-1");

        assertTrue(ChatTurnCancellationRegistry.isCancelled("turn-1"));
        assertTrue(disposed.get());
    }

    @Test
    void clearRemovesCancelledFlagAndDisposables() {
        ChatTurnCancellationRegistry.cancel("turn-1");
        ChatTurnCancellationRegistry.clear("turn-1");

        assertFalse(ChatTurnCancellationRegistry.isCancelled("turn-1"));
    }

    @Test
    void clearDisposablesKeepsCancelledFlag() {
        AtomicBoolean disposed = new AtomicBoolean(false);
        Disposable disposable = new Disposable() {
            @Override
            public void dispose() {
                disposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };
        ChatTurnCancellationRegistry.registerDisposable("turn-1", disposable);
        ChatTurnCancellationRegistry.cancel("turn-1");

        assertTrue(ChatTurnCancellationRegistry.isCancelled("turn-1"));
        ChatTurnCancellationRegistry.clearDisposables("turn-1");
        assertTrue(ChatTurnCancellationRegistry.isCancelled("turn-1"));
    }

    @Test
    void cancelIsIdempotent() {
        ChatTurnCancellationRegistry.cancel("turn-1");
        ChatTurnCancellationRegistry.cancel("turn-1");
        assertTrue(ChatTurnCancellationRegistry.isCancelled("turn-1"));
    }

    @Test
    void registerDisposableAfterCancelDisposesImmediately() {
        ChatTurnCancellationRegistry.cancel("turn-1");
        AtomicBoolean disposed = new AtomicBoolean(false);
        Disposable disposable = new Disposable() {
            @Override
            public void dispose() {
                disposed.set(true);
            }

            @Override
            public boolean isDisposed() {
                return disposed.get();
            }
        };
        ChatTurnCancellationRegistry.registerDisposable("turn-1", disposable);
        assertTrue(disposed.get());
    }
}
