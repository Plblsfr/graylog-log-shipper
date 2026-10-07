package fr.plbls.graylogshipper.core;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Background sender: a bounded queue drained by one daemon thread.
 * <p>
 * Never blocks the caller (the IDE's process-output thread). When Graylog is unreachable,
 * messages are discarded and a new connection is attempted every {@link #RETRY_MILLIS} ms,
 * so a stopped Graylog never slows the IDE down nor replays stale logs later.
 */
public final class GelfDispatcher {

    static final long RETRY_MILLIS = 2000;

    public record Stats(long sent, long failed, long dropped, int queued, String lastError) {
    }

    private final BlockingQueue<Map<String, Object>> queue;
    private final Consumer<String> onError;
    private final Runnable onRecovered;

    private volatile GelfConfig config;
    private volatile boolean running;
    private volatile String lastError;
    private Thread worker;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    // worker-thread state
    private GelfTransport transport;
    private String transportKey;
    private boolean inError;
    private long nextRetry;

    /**
     * @param onError     called (on the worker thread) when sending starts failing
     * @param onRecovered called (on the worker thread) when sending works again after a failure
     */
    public GelfDispatcher(GelfConfig config, int capacity, Consumer<String> onError, Runnable onRecovered) {
        this.config = config;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.onError = onError;
        this.onRecovered = onRecovered;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::loop, "Graylog Log Shipper");
        worker.setDaemon(true);
        worker.start();
    }

    public GelfConfig config() {
        return config;
    }

    public void updateConfig(GelfConfig newConfig) {
        this.config = newConfig;
        this.inError = false;
        this.nextRetry = 0;
    }

    /** Queues a message; never blocks. */
    public void offer(Map<String, Object> message) {
        if (!running || !queue.offer(message)) {
            dropped.incrementAndGet();
        }
    }

    public Stats stats() {
        return new Stats(sent.get(), failed.get(), dropped.get(), queue.size(), lastError);
    }

    private void loop() {
        while (running || !queue.isEmpty()) {
            Map<String, Object> message;
            try {
                message = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (!running) {
                    break;
                }
                continue;
            }
            GelfConfig cfg = config;
            if (transport != null && !cfg.endpointKey().equals(transportKey)) {
                closeTransport();
            }
            if (message == null) {
                continue;
            }
            long now = System.currentTimeMillis();
            if (inError && now < nextRetry) {
                failed.incrementAndGet();
                continue;
            }
            try {
                if (transport == null) {
                    transport = GelfTransport.create(cfg);
                    transportKey = cfg.endpointKey();
                }
                transport.send(message);
                sent.incrementAndGet();
                if (inError) {
                    inError = false;
                    lastError = null;
                    safeRun(onRecovered);
                }
            } catch (IOException | RuntimeException e) {
                failed.incrementAndGet();
                closeTransport();
                String error = describe(cfg, e);
                lastError = error;
                nextRetry = now + RETRY_MILLIS;
                if (!inError) {
                    inError = true;
                    try {
                        onError.accept(error);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        closeTransport();
    }

    private void closeTransport() {
        if (transport != null) {
            transport.close();
        }
        transport = null;
        transportKey = null;
    }

    private static void safeRun(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
        }
    }

    static String describe(GelfConfig cfg, Exception e) {
        String target = cfg.protocol() == GelfConfig.Protocol.HTTP
                ? cfg.httpUrl()
                : cfg.protocol() + " " + cfg.host() + ":" + cfg.port();
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return target + " — " + reason;
    }

    /** Stops the worker after giving it up to {@code waitMillis} to drain the queue. */
    public void shutdown(long waitMillis) {
        Thread w;
        synchronized (this) {
            running = false;
            w = worker;
        }
        if (w == null) {
            return;
        }
        try {
            w.join(waitMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (w.isAlive()) {
            queue.clear();
            w.interrupt();
        }
    }

    /**
     * Synchronously sends one message with a throw-away transport (used by "Test connection").
     *
     * @return {@code null} on success, otherwise a human readable error
     */
    public static String sendOnce(GelfConfig cfg, Map<String, Object> message) {
        try (GelfTransport t = GelfTransport.create(cfg)) {
            t.send(message);
            return null;
        } catch (IOException | RuntimeException e) {
            return describe(cfg, e);
        }
    }
}
