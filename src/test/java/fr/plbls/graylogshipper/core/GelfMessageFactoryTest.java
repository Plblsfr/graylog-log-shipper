package fr.plbls.graylogshipper.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelfMessageFactoryTest {

    private static final GelfConfig CFG = GelfConfig.defaults();
    private static final Map<String, Object> BASE = Map.of("_ij_run_config", "api");

    private static Map<String, Object> one(String line) {
        return GelfMessageFactory.build(List.of(line), "stdout", 1000.0, BASE, CFG);
    }

    @Test
    void logstashJsonLineIsFlattenedAndStringsStayStrings() {
        String line = "{\"@timestamp\":\"2026-10-06T15:53:12.123+02:00\",\"@version\":\"1\","
                + "\"message\":\"GET /orders done\",\"logger_name\":\"c.b.OrderController\","
                + "\"thread_name\":\"http-nio-8080-exec-1\",\"level\":\"WARN\",\"level_value\":30000,"
                + "\"flowId\":\"7f1c\",\"payload\":\"{\\\"orderId\\\":42,\\\"lines\\\":[1,2]}\","
                + "\"http\":{\"status\":200,\"request\":{\"method\":\"GET\",\"secure\":false}},"
                + "\"tags\":[\"a\",\"b\"],\"nothing\":null,\"bigId\":12345678901234567890}";
        Map<String, Object> m = one(line);

        assertEquals("GET /orders done", m.get("short_message"));
        assertEquals(4, m.get("level"));
        assertEquals("WARN", m.get("_level_name"));
        assertEquals(1791294792.123, (Double) m.get("timestamp"), 0.0001);

        // sub-objects are indexed as separate fields
        assertEquals(new Json.JsonNumber("200"), m.get("_http_status"));
        assertEquals("GET", m.get("_http_request_method"));
        assertEquals("false", m.get("_http_request_secure"));

        // a string containing JSON stays a plain string
        assertEquals("{\"orderId\":42,\"lines\":[1,2]}", m.get("_payload"));
        assertFalse(m.containsKey("_payload_orderId"));

        assertEquals("[\"a\",\"b\"]", m.get("_tags"));
        assertFalse(m.containsKey("_nothing"));
        assertEquals("12345678901234567890", m.get("_bigId").toString());
        assertEquals("1", m.get("_json_version")); // @version would clash with GELF "version"
        assertEquals("json", m.get("_ij_format"));
        assertEquals("api", m.get("_ij_run_config"));
        assertFalse(m.containsKey("_message"));
    }

    @Test
    void stackTraceInsideJsonGoesToFullMessage() {
        Map<String, Object> m = one("{\"msg\":\"boom\",\"level\":50,\"stack_trace\":\"java.lang.X\\n\\tat a.B\"}");
        assertEquals("boom", m.get("short_message"));
        assertEquals("java.lang.X\n\tat a.B", m.get("full_message"));
        assertEquals(3, m.get("level")); // pino 50 = error
    }

    @Test
    void reservedAndInvalidNamesAreRenamed() {
        Map<String, Object> m = one("{\"message\":\"x\",\"host\":\"h\",\"_id\":\"1\",\"a b@c\":\"v\",\"source\":\"s\"}");
        assertEquals("h", m.get("_json_host"));
        assertEquals("1", m.get("_json_id"));
        assertEquals("v", m.get("_a_b_c"));
        assertEquals("s", m.get("_json_source"));
        assertEquals("intellij", m.get("host"));
    }

    @Test
    void valuesAsStringsAndTruncation() {
        GelfConfig cfg = new GelfConfig(GelfConfig.Protocol.TCP, "h", 1, "/gelf", "src", Map.of("env", "local"),
                true, true, true, ".", 5, 300);
        Map<String, Object> m = GelfMessageFactory.build(
                List.of("{\"message\":\"x\",\"n\":3,\"o\":{\"long\":\"abcdefghij\"}}"), "stdout", 1, Map.of(), cfg);
        assertEquals("3", m.get("_n"));
        assertTrue(((String) m.get("_o.long")).startsWith("abcde…"));
        assertEquals("local", m.get("_env"));
        assertEquals("src", m.get("host"));
    }

    @Test
    void prefixedJsonLineIsParsed() {
        Map<String, Object> m = one("api-1  | {\"message\":\"hello\"}");
        assertEquals("hello", m.get("short_message"));
        assertEquals("api-1  |", m.get("_line_prefix"));
    }

    @Test
    void invalidJsonFallsBackToText() {
        Map<String, Object> m = one("ERROR something {not json}");
        assertEquals("ERROR something {not json}", m.get("short_message"));
        assertEquals(3, m.get("level"));
        assertEquals("text", m.get("_ij_format"));
    }

    @Test
    void textLevelAndStderrDefault() {
        assertEquals(6, one("2026-10-06T15:53:12.123+02:00  INFO 4242 --- [main] c.b.App : Started").get("level"));
        assertEquals(3, GelfMessageFactory.build(List.of("weird output"), "stderr", 1, Map.of(), CFG).get("level"));
        assertEquals(6, one("weird output").get("level"));
        assertNull(GelfMessageFactory.build(List.of("   "), "stdout", 1, Map.of(), CFG));
    }

    @Test
    void timestamps() {
        assertEquals(1791294792.0, GelfMessageFactory.parseTimestamp("2026-10-06T13:53:12Z"), 0.001);
        assertEquals(1791294792.5, GelfMessageFactory.parseTimestamp(new Json.JsonNumber("1791294792500")), 0.001);
        assertEquals(1791294792.0, GelfMessageFactory.parseTimestamp("2026-10-06 15:53:12+02:00"), 0.001);
        assertTrue(GelfMessageFactory.parseTimestamp("2026-10-06 15:53:12,123") != null);
        assertNull(GelfMessageFactory.parseTimestamp("yesterday"));
    }

    @Test
    void ansiIsStripped() {
        Map<String, Object> m = one("\u001B[32mINFO\u001B[0m started");
        assertEquals("INFO started", m.get("short_message"));
    }

    @Test
    void sessionRebuildsLinesAndGroupsStackTraces() {
        List<Map<String, Object>> out = new ArrayList<>();
        LogSession s = new LogSession(BASE, () -> CFG, out::add, 300);
        s.onText("12:00 ERROR failed to call\njava.lang.IllegalState", "stdout");
        s.onText("Exception: nope\n\tat a.B.c(B.java:1)\n", "stdout");
        s.onText("\tat a.D.e(D.java:2)\nCaused by: java.io.IOException\n\t... 3 more\n", "stdout");
        s.onText("{\"message\":\"next\"}\n", "stdout");
        s.onText("partial without newline", "stderr");
        s.close();

        assertEquals(4, out.size());
        assertEquals("12:00 ERROR failed to call", out.get(0).get("short_message"));
        assertEquals(3, out.get(0).get("level"));
        assertNull(out.get(0).get("full_message"));

        Map<String, Object> trace = out.get(1);
        assertEquals("java.lang.IllegalStateException: nope", trace.get("short_message"));
        assertEquals("java.lang.IllegalStateException: nope\n\tat a.B.c(B.java:1)\n\tat a.D.e(D.java:2)\n"
                + "Caused by: java.io.IOException\n\t... 3 more", trace.get("full_message"));

        assertEquals("next", out.get(2).get("short_message"));
        assertEquals("partial without newline", out.get(3).get("short_message"));
        assertEquals("stderr", out.get(3).get("_ij_stream"));
    }

    @Test
    void idleFlushEmitsPendingRecord() throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        LogSession s = new LogSession(Map.of(), () -> CFG, out::add, 50);
        s.onText("hello\n", "stdout");
        s.flushIdle(System.currentTimeMillis());
        assertEquals(0, out.size());
        Thread.sleep(80);
        s.flushIdle(System.currentTimeMillis());
        assertEquals(1, out.size());
    }

    @Test
    void datesWithoutZoneUseConfiguredZone() {
        java.time.ZoneId paris = java.time.ZoneId.of("Europe/Paris");
        // sans fuseau : UTC par défaut, ou la zone choisie
        assertEquals(1791294792.0, GelfMessageFactory.parseTimestamp("2026-10-06T13:53:12"), 0.001);
        assertEquals(1791294792.0, GelfMessageFactory.parseTimestamp("2026-10-06 13:53:12.000"), 0.001);
        assertEquals(1791287592.0, GelfMessageFactory.parseTimestamp("2026-10-06T13:53:12", paris), 0.001);
        assertEquals(1791287592.5, GelfMessageFactory.parseTimestamp("2026-10-06 13:53:12.500", paris), 0.001);
        // avec Z ou offset : la zone configurée est ignorée
        assertEquals(1791294792.0, GelfMessageFactory.parseTimestamp("2026-10-06T13:53:12Z", paris), 0.001);
        assertEquals(1791294792.0, GelfMessageFactory.parseTimestamp("2026-10-06T15:53:12+02:00", paris), 0.001);

        GelfConfig cfg = new GelfConfig(GelfConfig.Protocol.TCP, "h", 1, "/gelf", "s", Map.of(),
                true, true, false, "_", 0, 300, paris);
        Map<String, Object> m = GelfMessageFactory.build(
                List.of("{\"message\":\"x\",\"@timestamp\":\"2026-10-06T13:53:12\"}"), "stdout", 1, Map.of(), cfg);
        assertEquals(1791287592.0, (Double) m.get("timestamp"), 0.001);
    }
}
