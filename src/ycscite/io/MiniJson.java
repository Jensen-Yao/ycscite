package ycscite.io;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser (objects, arrays, strings with escapes,
 * numbers, true/false/null) — just enough for the CrossRef REST response, no dependencies.
 */
public final class MiniJson {

    public static Object parse(String text) {
        P p = new P(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos < p.s.length()) throw new IllegalArgumentException("trailing content at " + p.pos);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }

    public static String str(Object o) { return o instanceof String ? (String) o : null; }

    public static double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0; }

    // ---------------- writer ----------------

    /** Serialize Map/List/String/Number/Boolean/null to compact JSON (round-trips through parse). */
    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        writeValue(o, sb);
        return sb.toString();
    }

    private static void writeValue(Object o, StringBuilder sb) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof String s) { writeString(s, sb); return; }
        if (o instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (o instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) sb.append("null");
            else sb.append(d);
            return;
        }
        if (o instanceof Float f) { writeValue(f.doubleValue(), sb); return; }
        if (o instanceof Number n) { sb.append(n); return; }
        if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
            return;
        }
        if (o instanceof List<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object v : l) {
                if (!first) sb.append(',');
                first = false;
                writeValue(v, sb);
            }
            sb.append(']');
            return;
        }
        writeString(String.valueOf(o), sb);
    }

    private static void writeString(String s, StringBuilder sb) {
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final class P {
        final String s;
        int pos = 0;
        P(String s) { this.s = s; }

        void ws() { while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++; }

        Object value() {
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        void expect(String lit) {
            if (!s.startsWith(lit, pos)) throw new IllegalArgumentException("bad literal at " + pos);
            pos += lit.length();
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            ws();
            if (pos < s.length() && s.charAt(pos) == '}') { pos++; return m; }
            while (true) {
                ws();
                String k = string();
                ws();
                if (pos >= s.length() || s.charAt(pos) != ':') throw new IllegalArgumentException("expected : at " + pos);
                pos++;
                ws();
                m.put(k, value());
                ws();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , or } at " + (pos - 1));
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++; // [
            ws();
            if (pos < s.length() && s.charAt(pos) == ']') { pos++; return l; }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (pos >= s.length()) throw new IllegalArgumentException("unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , or ] at " + (pos - 1));
            }
        }

        String string() {
            if (s.charAt(pos) != '"') throw new IllegalArgumentException("expected string at " + pos);
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) break;
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
                            if (pos + 4 > s.length()) throw new IllegalArgumentException("bad \\u");
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape \\" + e);
                    }
                } else sb.append(c);
            }
            throw new IllegalArgumentException("unterminated string");
        }

        Number number() {
            int start = pos;
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) pos++;
            while (pos < s.length() && "0123456789.eE+-".indexOf(s.charAt(pos)) >= 0) pos++;
            String token=s.substring(start,pos);
            if(!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("bad number at "+start);
            try { return Long.valueOf(token); }
            catch(NumberFormatException integerError){
                try{return new java.math.BigDecimal(token);}
                catch(NumberFormatException error){throw new IllegalArgumentException("bad number at "+start);}
            }
        }
    }
}
