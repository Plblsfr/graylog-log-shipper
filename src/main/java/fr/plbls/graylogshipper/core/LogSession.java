package fr.plbls.graylogshipper.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Console output of one running process.
 * <p>
 * Receives raw text chunks (which may cut lines anywhere), rebuilds full lines per stream,
 * groups plain-text continuation lines (stack traces) with their head line, and hands each
 * finished record to the sink as a GELF message.
 */
public final class LogSession {

    /** Lines that continue the previous plain-text record (Java/Python/JS stack traces). */
    private static final Pattern CONTINUATION = Pattern.compile(
            "^(\\s+\\S.*|\\s*at\\s.*|Caused by:.*|Suppressed:.*|\\.\\.\\. \\d+ (more|common frames omitted).*|\\s*\\^.*)$");

    private static final int MAX_RECORD_LINES = 500;
    private static final int MAX_PARTIAL_CHARS = 1_000_000;

    private final Map<String, Object> baseFields;
    private final Supplier<GelfConfig> config;
    private final Consumer<Map<String, Object>> sink;
    private final long idleFlushMillis;

    private final Map<String, StringBuilder> partial = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private boolean closed;

    private static final class Pending {
        final List<String> lines = new ArrayList<>();
        final double arrival;
        long lastUpdate;

        Pending(String head, long now) {
            lines.add(head);
            arrival = now / 1000.0;
            lastUpdate = now;
        }
    }

    public LogSession(Map<String, Object> baseFields, Supplier<GelfConfig> config,
                      Consumer<Map<String, Object>> sink, long idleFlushMillis) {
        this.baseFields = Map.copyOf(baseFields);
        this.config = config;
        this.sink = sink;
        this.idleFlushMillis = idleFlushMillis;
    }

    public Map<String, Object> baseFields() {
        return baseFields;
    }

    /** Feeds a chunk of console text for the given stream ("stdout", "stderr", "system"). */
    public synchronized void onText(String text, String stream) {
        if (closed || text == null || text.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        StringBuilder buf = partial.computeIfAbsent(stream, k -> new StringBuilder());
        buf.append(text);
        int nl;
        while ((nl = buf.indexOf("\n")) >= 0) {
            String line = buf.substring(0, nl);
            buf.delete(0, nl + 1);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            onLine(line, stream, now);
        }
        if (buf.length() > MAX_PARTIAL_CHARS) { // runaway line without newline
            String line = buf.toString();
            buf.setLength(0);
            onLine(line, stream, now);
        }
    }

    private void onLine(String rawLine, String stream, long now) {
        GelfConfig cfg = config.get();
        String line = GelfMessageFactory.stripAnsi(rawLine);
        Pending p = pending.get(stream);

        boolean json = cfg.parseJson() && GelfMessageFactory.looksLikeJson(line);
        boolean continuation = !json && cfg.mergeMultiline() && p != null
                && p.lines.size() < MAX_RECORD_LINES
                && CONTINUATION.matcher(line).matches();
        if (continuation) {
            p.lines.add(line);
            p.lastUpdate = now;
            return;
        }
        if (p != null) {
            emit(p, stream, cfg);
            pending.remove(stream);
        }
        if (line.isBlank()) {
            return;
        }
        Pending next = new Pending(line, now);
        if (json || !cfg.mergeMultiline()) {
            emit(next, stream, cfg); // nothing can be appended to it
        } else {
            pending.put(stream, next);
        }
    }

    /** Emits records that have not received continuation lines for a while. */
    public synchronized void flushIdle(long now) {
        if (pending.isEmpty()) {
            return;
        }
        GelfConfig cfg = config.get();
        pending.entrySet().removeIf(e -> {
            if (now - e.getValue().lastUpdate >= idleFlushMillis) {
                emit(e.getValue(), e.getKey(), cfg);
                return true;
            }
            return false;
        });
    }

    /** Flushes everything (incomplete last lines included). Further text is ignored. */
    public synchronized void close() {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, StringBuilder> e : partial.entrySet()) {
            if (e.getValue().length() > 0) {
                String line = e.getValue().toString();
                e.getValue().setLength(0);
                onLine(line, e.getKey(), now);
            }
        }
        GelfConfig cfg = config.get();
        pending.forEach((stream, p) -> emit(p, stream, cfg));
        pending.clear();
        closed = true;
    }

    /** Sends an extra, synthetic event for this session (e.g. "process terminated"). */
    public void emitEvent(String text, int level, Map<String, Object> extra) {
        sink.accept(GelfMessageFactory.event(text, level, config.get(), baseFields, extra));
    }

    private void emit(Pending p, String stream, GelfConfig cfg) {
        Map<String, Object> msg = GelfMessageFactory.build(p.lines, stream, p.arrival, baseFields, cfg);
        if (msg != null) {
            sink.accept(msg);
        }
    }
}
