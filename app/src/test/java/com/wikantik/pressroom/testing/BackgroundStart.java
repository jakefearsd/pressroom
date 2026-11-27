package com.wikantik.pressroom.testing;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * A shared test resource started once: in the background when {@link #begin()}
 * is called early, or on first {@link #get()}, which waits for it either way. A
 * failed start fails every {@code get()} with its cause, so a broken Docker setup
 * still fails the tests that need it, loudly.
 */
public final class BackgroundStart<T> {

    private final String name;
    private final Supplier<T> starter;
    private CompletableFuture<T> started;

    public BackgroundStart(String name, Supplier<T> starter) {
        this.name = name;
        this.starter = starter;
    }

    /** Starts it on a daemon thread, unless it is already starting or started. */
    public synchronized void begin() {
        if (started == null) {
            started = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try {
                    started.complete(starter.get());
                } catch (RuntimeException | Error e) {
                    started.completeExceptionally(e);
                }
            }, "background-start-" + name);
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** The started resource, waiting for the start to finish (and starting it if nothing has). */
    public T get() {
        begin();
        try {
            return started.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
