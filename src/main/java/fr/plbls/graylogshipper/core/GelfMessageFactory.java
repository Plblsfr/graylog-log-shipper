package fr.plbls.graylogshipper.core;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns console log records (one JSON line, or one or more plain-text lines) into GELF 1.1 messages.
 *
 * <h2>JSON lines</h2>
 * Nested objects are flattened into separate fields ({@code {"http":{"status":200}}} gives
 * {@code _http_status = 200}), so every sub-object is indexed. String values are sent as they are,
 * even when they contain JSON text: Graylog stores them as plain strings and never expands them.
 * Arrays are sent as their compact JSON text.
 */
public final class GelfMessageFactory {

    private static final List<String> MESSAGE_KEYS = List.of("message", "msg", "@message", "log", "text");
    private static final List<String> TIMESTAMP_KEYS = List.of("@timestamp", "timestamp", "time", "ts", "@t", "date");
    private static final List<String> LEVEL_KEYS = List.of("level", "log.level", "severity", "levelname", "lvl", "@l", "loglevel");
    private static final List<String> STACK_KEYS = List.of("stack_trace", "stacktrace", "stack", "exception", "@x", "error.stack_trace");

    /** Graylog / GELF names that an additional field must not take. */
    private static final Set<String> RESERVED = Set.of(
            "id", "message", "short_message", "full_message", "timestamp", "source", "host",
            "level", "facility", "version", "streams", "file", "line");

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007");
    private static final Pattern LEVEL_IN_TEXT = Pattern.compile(
            "(?<![A-Za-z])(TRACE|DEBUG|FINE|INFO|NOTICE|WARN|WARNING|ERROR|SEVERE|FATAL|CRITICAL)(?![A-Za-z])");
    private static final Pattern BAD_FIELD_CHARS = Pattern.compile("[^A-Za-z0-9_.\\-]");
    private static final int MAX_DEPTH = 10;

    private static final DateTimeFormatter LOCAL_SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSSSSS][.SSSSSS][.SSS][,SSS]", Locale.ROOT);

    private GelfMessageFactory() {
    }

    public static String stripAnsi(String s) {
        return s.indexOf('\u001B') < 0 ? s : ANSI.matcher(s).replaceAll("");
    }

    /** True when the (ANSI-stripped) line is a JSON object log line. */
    public static boolean looksLikeJson(String line) {
        String t = line.strip();
        return t.endsWith("}") && t.indexOf('{') >= 0;
    }

    /**
     * Builds a GELF message.
     *
     * @param lines        the record: one line, or a head line followed by continuation lines
     * @param stream       "stdout", "stderr" or "system"
     * @param arrivalEpoch arrival time (seconds), used when the log has no timestamp of its own
     * @param baseFields   per-session fields, keys WITH the leading underscore
     * @return the GELF message, or {@code null} if there is nothing to send
     */
    public static Map<String, Object> build(List<String> lines, String stream, double arrivalEpoch,
                                            Map<String, Object> baseFields, GelfConfig cfg) {
        if (lines.isEmpty()) {
            return null;
        }
        lines = lines.stream().map(GelfMessageFactory::stripAnsi).toList();
        String head = lines.get(0);
        if (head.isBlank() && lines.size() == 1) {
            return null;
        }

        Map<String, Object> gelf = new LinkedHashMap<>();
        gelf.put("version", "1.1");
        gelf.put("host", cfg.sourceName() == null || cfg.sourceName().isBlank() ? "intellij" : cfg.sourceName());
        gelf.put("timestamp", arrivalEpoch);

        boolean json = false;
        if (cfg.parseJson() && lines.size() == 1 && looksLikeJson(head)) {
            json = fillFromJson(head, gelf, cfg);
        }
        if (!json) {
            fillFromText(lines, stream, gelf);
        }

        for (Map.Entry<String, String> e : cfg.staticFields().entrySet()) {
            putField(gelf, fieldName(e.getKey()), e.getValue(), cfg);
        }
        gelf.putAll(baseFields);
        gelf.put("_ij_stream", stream);
        gelf.put("_ij_format", json ? "json" : "text");

        Object sm = gelf.get("short_message");
        if (!(sm instanceof String s) || s.isBlank()) {
            gelf.put("short_message", "(empty)");
        }
        return gelf;
    }

    /** A synthetic message (process started/finished, connection test…). */
    public static Map<String, Object> event(String text, int level, GelfConfig cfg,
                                            Map<String, Object> baseFields, Map<String, Object> extra) {
        Map<String, Object> gelf = new LinkedHashMap<>();
        gelf.put("version", "1.1");
        gelf.put("host", cfg.sourceName() == null || cfg.sourceName().isBlank() ? "intellij" : cfg.sourceName());
        gelf.put("short_message", text);
        gelf.put("timestamp", System.currentTimeMillis() / 1000.0);
        gelf.put("level", level);
        gelf.put("_level_name", levelName(level));
        for (Map.Entry<String, String> e : cfg.staticFields().entrySet()) {
            putField(gelf, fieldName(e.getKey()), e.getValue(), cfg);
        }
        gelf.putAll(baseFields);
        gelf.putAll(extra);
        gelf.put("_ij_format", "event");
        return gelf;
    }

    // ------------------------------------------------------------------ plain text

    private static void fillFromText(List<String> lines, String stream, Map<String, Object> gelf) {
        String head = lines.get(0).stripTrailing();
        gelf.put("short_message", head.isBlank() ? lines.get(0) : head);
        if (lines.size() > 1) {
            gelf.put("full_message", String.join("\n", lines));
        }
        Integer level = levelFromText(head);
        if (level == null) {
            level = "stderr".equals(stream) ? 3 : 6;
        } else {
            gelf.put("_level_name", levelName(level));
        }
        gelf.put("level", level);
    }

    static Integer levelFromText(String line) {
        String probe = line.length() > 200 ? line.substring(0, 200) : line;
        Matcher m = LEVEL_IN_TEXT.matcher(probe);
        return m.find() ? syslogLevel(m.group(1)) : null;
    }

    // ------------------------------------------------------------------ JSON

    private static boolean fillFromJson(String line, Map<String, Object> gelf, GelfConfig cfg) {
        String t = line.strip();
        int brace = t.indexOf('{');
        Object parsed;
        try {
            parsed = Json.parse(t.substring(brace));
        } catch (Json.ParseException e) {
            return false;
        }
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rawMap;

        // Well-known properties: message, timestamp, level, stack trace.
        Object msg = takeFirst(root, MESSAGE_KEYS, true);
        Object ts = takeFirst(root, TIMESTAMP_KEYS, false);
        Object lvl = takeFirst(root, LEVEL_KEYS, false);
        Object stack = takeFirst(root, STACK_KEYS, true);

        gelf.put("short_message", msg instanceof String s && !s.isBlank() ? s : t);
        if (stack instanceof String s && !s.isBlank()) {
            gelf.put("full_message", s);
        }
        if (ts != null) {
            Double epoch = parseTimestamp(ts, cfg.naiveZone() == null ? ZoneOffset.UTC : cfg.naiveZone());
            if (epoch != null) {
                gelf.put("timestamp", epoch);
            } else {
                root.put("log_timestamp", ts);
            }
        }
        Integer level = null;
        if (lvl instanceof String s) {
            level = syslogLevel(s);
            root.put("level_name", s);
        } else if (lvl instanceof Json.JsonNumber n) {
            level = numericLevel(n);
            root.put("level_name", level == null ? n.raw() : levelName(level));
        }
        gelf.put("level", level == null ? 6 : level);
        if (brace > 0) {
            root.put("line_prefix", t.substring(0, brace).strip());
        }

        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", root, flat, cfg.separator() == null ? "_" : cfg.separator(), 0);

        int count = 0;
        int dropped = 0;
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            if (count >= cfg.maxFields()) {
                dropped++;
                continue;
            }
            if (putField(gelf, fieldName(e.getKey()), e.getValue(), cfg)) {
                count++;
            }
        }
        if (dropped > 0) {
            gelf.put("_ij_dropped_fields", dropped);
        }
        return true;
    }

    /** Removes and returns the first matching top-level key (or dotted path, e.g. {@code log.level}). */
    private static Object takeFirst(Map<String, Object> root, List<String> keys, boolean stringsOnly) {
        for (String k : keys) {
            Object v = root.get(k);
            if (v != null && v != Json.NULL && (!stringsOnly || v instanceof String)) {
                root.remove(k);
                return v;
            }
            int dot = k.indexOf('.');
            if (dot > 0 && root.get(k.substring(0, dot)) instanceof Map<?, ?> sub) {
                Object nested = sub.get(k.substring(dot + 1));
                if (nested != null && nested != Json.NULL && (!stringsOnly || nested instanceof String)) {
                    sub.remove(k.substring(dot + 1));
                    return nested;
                }
            }
        }
        return null;
    }

    private static void flatten(String prefix, Object value, Map<String, Object> out, String sep, int depth) {
        if (value instanceof Map<?, ?> map) {
            if (depth >= MAX_DEPTH) {
                out.put(prefix, Json.write(map));
                return;
            }
            if (map.isEmpty() && !prefix.isEmpty()) {
                return;
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                flatten(prefix.isEmpty() ? key : prefix + sep + key, e.getValue(), out, sep, depth + 1);
            }
        } else if (value instanceof List<?> list) {
            if (!list.isEmpty()) {
                out.put(prefix, Json.write(list));
            }
        } else if (value == null || value == Json.NULL) {
            // GELF has no null: skip
        } else if (value instanceof Boolean b) {
            out.put(prefix, b.toString());
        } else {
            out.put(prefix, value); // String or JsonNumber, kept as is
        }
    }

    /** Converts a free-form key into a valid, non-reserved GELF additional field name (with underscore). */
    public static String fieldName(String key) {
        String name = BAD_FIELD_CHARS.matcher(key).replaceAll("_");
        int i = 0;
        while (i < name.length() && name.charAt(i) == '_') {
            i++;
        }
        name = name.substring(i);
        if (name.isEmpty()) {
            name = "field";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (RESERVED.contains(lower) || lower.startsWith("gl2_")) {
            name = "json_" + name;
        }
        return "_" + name;
    }

    private static boolean putField(Map<String, Object> gelf, String name, Object value, GelfConfig cfg) {
        if (value == null) {
            return false;
        }
        Object v = value;
        if (v instanceof Json.JsonNumber n && cfg.valuesAsStrings()) {
            v = n.raw();
        }
        if (v instanceof String s && cfg.maxFieldLength() > 0 && s.length() > cfg.maxFieldLength()) {
            v = s.substring(0, cfg.maxFieldLength()) + "…[truncated " + s.length() + " chars]";
        }
        gelf.put(name, v);
        return true;
    }

    // ------------------------------------------------------------------ levels

    /** Maps a textual level onto the syslog scale used by GELF (0 = emergency … 7 = debug). */
    public static int syslogLevel(String level) {
        String l = level.strip().toUpperCase(Locale.ROOT);
        return switch (l) {
            case "EMERG", "EMERGENCY", "PANIC" -> 0;
            case "ALERT" -> 1;
            case "FATAL", "CRIT", "CRITICAL", "F", "FTL" -> 2;
            case "ERROR", "ERR", "SEVERE", "E", "ERRO" -> 3;
            case "WARN", "WARNING", "W", "WRN" -> 4;
            case "NOTICE" -> 5;
            case "INFO", "INFORMATION", "INFORMATIONAL", "I", "INF", "CONFIG" -> 6;
            case "DEBUG", "TRACE", "FINE", "FINER", "FINEST", "VERBOSE", "D", "T", "DBG", "TRC", "ALL" -> 7;
            default -> {
                try {
                    Integer n = numericLevel(new Json.JsonNumber(l));
                    yield n == null ? 6 : n;
                } catch (NumberFormatException e) {
                    yield 6;
                }
            }
        };
    }

    /** Numeric levels: syslog (0-7), pino/bunyan (10-60), logback level_value (5000-40000). */
    private static Integer numericLevel(Json.JsonNumber n) {
        double d = n.doubleValue();
        if (d >= 0 && d <= 7 && d == Math.rint(d)) {
            return (int) d;
        }
        if (d >= 1000) { // logback / log4j style
            if (d >= 50000) return 2;
            if (d >= 40000) return 3;
            if (d >= 30000) return 4;
            if (d >= 20000) return 6;
            return 7;
        }
        if (d >= 10) { // pino / bunyan
            if (d >= 60) return 2;
            if (d >= 50) return 3;
            if (d >= 40) return 4;
            if (d >= 30) return 6;
            return 7;
        }
        return null;
    }

    static String levelName(int level) {
        return switch (level) {
            case 0 -> "EMERGENCY";
            case 1 -> "ALERT";
            case 2 -> "CRITICAL";
            case 3 -> "ERROR";
            case 4 -> "WARN";
            case 5 -> "NOTICE";
            case 6 -> "INFO";
            default -> "DEBUG";
        };
    }

    // ------------------------------------------------------------------ timestamps

    /** Same as {@link #parseTimestamp(Object, ZoneId)}, dates without a zone are read as UTC. */
    public static Double parseTimestamp(Object value) {
        return parseTimestamp(value, ZoneOffset.UTC);
    }

    /**
     * Returns epoch seconds (with fraction) or {@code null} if the value is not a recognisable date.
     * Dates carrying {@code Z} or an offset are used as they are; dates without one are read in {@code naiveZone}.
     */
    public static Double parseTimestamp(Object value, ZoneId naiveZone) {
        if (value instanceof Json.JsonNumber n) {
            double d;
            try {
                d = n.doubleValue();
            } catch (NumberFormatException e) {
                return null;
            }
            if (d > 1e17) return d / 1e9;     // nanoseconds
            if (d > 1e14) return d / 1e6;     // microseconds
            if (d > 1e11) return d / 1e3;     // milliseconds
            if (d > 1e8) return d;            // seconds
            return null;
        }
        if (!(value instanceof String s)) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        if (t.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
            try {
                return parseTimestamp(new Json.JsonNumber(t));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return toEpoch(Instant.parse(t));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return toEpoch(OffsetDateTime.parse(t).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return toEpoch(ZonedDateTime.parse(t).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return toEpoch(LocalDateTime.parse(t).atZone(naiveZone).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        try {
            return toEpoch(LocalDateTime.parse(t, LOCAL_SPACE).atZone(naiveZone).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        // "2026-10-06 15:53:12.123+02:00" (space instead of T)
        if (t.length() > 10 && t.charAt(10) == ' ') {
            String withT = t.substring(0, 10) + "T" + t.substring(11);
            try {
                return toEpoch(OffsetDateTime.parse(withT).toInstant());
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static double toEpoch(Instant i) {
        return i.getEpochSecond() + i.getNano() / 1_000_000_000.0;
    }
}
