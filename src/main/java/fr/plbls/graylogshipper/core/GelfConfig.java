package fr.plbls.graylogshipper.core;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Immutable snapshot of the shipper settings, independent of IntelliJ.
 *
 * @param protocol          transport used to reach the Graylog GELF input
 * @param host              Graylog host
 * @param port              GELF input port (12201 by default)
 * @param httpPath          path of the GELF HTTP input (HTTP only, usually {@code /gelf})
 * @param sourceName        GELF {@code host} field, shown as "source" in Graylog
 * @param staticFields      extra fields added to every message (keys without leading underscore)
 * @param parseJson         parse JSON log lines and turn their properties into fields
 * @param mergeMultiline    glue stack-trace continuation lines of plain-text logs into one message
 * @param valuesAsStrings   send every field value as a string (avoids type conflicts in the index)
 * @param separator         separator used when flattening nested objects ({@code a.b} becomes {@code a_b})
 * @param maxFieldLength    truncate field values longer than this (0 = never); protects the
 *                          OpenSearch keyword limit of 32 766 bytes
 * @param maxFields         max number of additional fields per message
 * @param naiveZone         time zone assumed for log dates that carry no zone/offset
 *                          (e.g. {@code 2026-10-06T13:53:12}); dates with {@code Z} or an offset ignore it
 */
public record GelfConfig(
        Protocol protocol,
        String host,
        int port,
        String httpPath,
        String sourceName,
        Map<String, String> staticFields,
        boolean parseJson,
        boolean mergeMultiline,
        boolean valuesAsStrings,
        String separator,
        int maxFieldLength,
        int maxFields,
        ZoneId naiveZone
) {

    /** Same as the canonical constructor; dates without a time zone are read as UTC. */
    public GelfConfig(Protocol protocol, String host, int port, String httpPath, String sourceName,
                      Map<String, String> staticFields, boolean parseJson, boolean mergeMultiline,
                      boolean valuesAsStrings, String separator, int maxFieldLength, int maxFields) {
        this(protocol, host, port, httpPath, sourceName, staticFields, parseJson, mergeMultiline,
                valuesAsStrings, separator, maxFieldLength, maxFields, ZoneOffset.UTC);
    }

    public enum Protocol {
        TCP, UDP, HTTP
    }

    public static GelfConfig defaults() {
        return new GelfConfig(Protocol.TCP, "localhost", 12201, "/gelf", "intellij", Map.of(),
                true, true, false, "_", 10_000, 300, ZoneOffset.UTC);
    }

    /** Identity of the network endpoint: when it changes the transport must be recreated. */
    public String endpointKey() {
        return protocol + "|" + host + "|" + port + "|" + (protocol == Protocol.HTTP ? httpPath : "");
    }

    public String httpUrl() {
        String path = httpPath == null || httpPath.isBlank() ? "/gelf" : httpPath.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String h = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "http://" + h + ":" + port + path;
    }
}
