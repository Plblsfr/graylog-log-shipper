package fr.plbls.graylogshipper.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader/writer.
 * <p>
 * Objects become {@link LinkedHashMap} (key order preserved), arrays {@link List},
 * numbers {@link JsonNumber} (raw text kept intact, so 64-bit ids are not rounded),
 * booleans {@link Boolean}, JSON null {@link #NULL}.
 */
public final class Json {

    /** Sentinel for a JSON {@code null} value (distinct from "absent"). */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** A JSON number kept as its original text. */
    public record JsonNumber(String raw) {
        public double doubleValue() {
            return Double.parseDouble(raw);
        }

        @Override
        public String toString() {
            return raw;
        }
    }

    public static final class ParseException extends Exception {
        private static final long serialVersionUID = 1L;

        public ParseException(String message) {
            super(message);
        }
    }

    private Json() {
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(String text) throws ParseException {
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue(0);
        p.skipWs();
        if (p.pos != text.length()) {
            throw new ParseException("Trailing characters at " + p.pos);
        }
        return value;
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 512;
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue(int depth) throws ParseException {
            if (depth > MAX_DEPTH) {
                throw new ParseException("Too deep");
            }
            if (pos >= s.length()) {
                throw new ParseException("Unexpected end");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return readObject(depth);
                case '[':
                    return readArray(depth);
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return NULL;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return readNumber();
                    }
                    throw new ParseException("Unexpected '" + c + "' at " + pos);
            }
        }

        void expect(String word) throws ParseException {
            if (!s.startsWith(word, pos)) {
                throw new ParseException("Expected " + word + " at " + pos);
            }
            pos += word.length();
        }

        Map<String, Object> readObject(int depth) throws ParseException {
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw new ParseException("Expected key at " + pos);
                }
                String key = readString();
                skipWs();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new ParseException("Expected ':' at " + pos);
                }
                pos++;
                skipWs();
                map.put(key, readValue(depth + 1));
                skipWs();
                if (pos >= s.length()) {
                    throw new ParseException("Unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new ParseException("Expected ',' or '}' at " + (pos - 1));
                }
            }
        }

        List<Object> readArray(int depth) throws ParseException {
            pos++; // [
            List<Object> list = new ArrayList<>();
            skipWs();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue(depth + 1));
                skipWs();
                if (pos >= s.length()) {
                    throw new ParseException("Unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new ParseException("Expected ',' or ']' at " + (pos - 1));
                }
            }
        }

        String readString() throws ParseException {
            pos++; // opening quote
            StringBuilder sb = null;
            int start = pos;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '"') {
                    String result = sb == null ? s.substring(start, pos) : sb.append(s, start, pos).toString();
                    pos++;
                    return result;
                }
                if (c == '\\') {
                    if (sb == null) {
                        sb = new StringBuilder();
                    }
                    sb.append(s, start, pos);
                    pos++;
                    if (pos >= s.length()) {
                        throw new ParseException("Bad escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new ParseException("Bad unicode escape");
                            }
                            try {
                                sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw new ParseException("Bad unicode escape");
                            }
                            pos += 4;
                        }
                        default -> throw new ParseException("Bad escape \\" + e);
                    }
                    start = pos;
                } else if (c < 0x20) {
                    throw new ParseException("Control character in string at " + pos);
                } else {
                    pos++;
                }
            }
            throw new ParseException("Unterminated string");
        }

        JsonNumber readNumber() throws ParseException {
            int start = pos;
            if (s.charAt(pos) == '-') {
                pos++;
            }
            int digits = 0;
            while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                pos++;
                digits++;
            }
            if (digits == 0) {
                throw new ParseException("Bad number at " + start);
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                int frac = 0;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                    frac++;
                }
                if (frac == 0) {
                    throw new ParseException("Bad number at " + start);
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int exp = 0;
                while (pos < s.length() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                    exp++;
                }
                if (exp == 0) {
                    throw new ParseException("Bad number at " + start);
                }
            }
            return new JsonNumber(s.substring(start, pos));
        }
    }

    // ------------------------------------------------------------------ writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(value, sb);
        return sb.toString();
    }

    public static void write(Object value, StringBuilder sb) {
        if (value == null || value == NULL) {
            sb.append("null");
        } else if (value instanceof String str) {
            writeString(str, sb);
        } else if (value instanceof JsonNumber n) {
            sb.append(n.raw());
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else {
                sb.append(plainDouble(d));
            }
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static String plainDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return new java.math.BigDecimal(d).setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    public static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
