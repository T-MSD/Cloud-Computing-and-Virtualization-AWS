package pt.ulisboa.tecnico.cnv.loadbalancer;

import java.util.Map;

/**
 * Minimal JSON helpers used by LoadBalancerServer when talking to Lambda.
 *
 * We keep this hand-rolled to avoid a Jackson/Gson dependency. The data we
 * exchange is shallow: a flat object with a handful of string/int fields.
 */
public final class JsonUtil {

    private JsonUtil() {}

    /** Build a one-level JSON object from a map of string keys to string values. */
    public static String objectOf(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            if (e.getValue() == null) {
                sb.append("null");
            } else {
                sb.append('"').append(escape(e.getValue())).append('"');
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Extract a string field from a flat JSON object. Naive (no recursion,
     * no arrays) — fine for the Lambda response shape we expect:
     * {"statusCode": 200, "body": "..."}.
     *
     * Returns null if the key is absent.
     */
    public static String extractString(String json, String key) {
        if (json == null) return null;
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) return null;
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length()) return null;
        if (json.charAt(i) == '"') {
            // String value
            i++;
            StringBuilder out = new StringBuilder();
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    switch (next) {
                        case '"':  out.append('"');  break;
                        case '\\': out.append('\\'); break;
                        case '/':  out.append('/');  break;
                        case 'n':  out.append('\n'); break;
                        case 'r':  out.append('\r'); break;
                        case 't':  out.append('\t'); break;
                        default:   out.append(next);
                    }
                    i += 2;
                } else if (c == '"') {
                    return out.toString();
                } else {
                    out.append(c);
                    i++;
                }
            }
            return null; // unterminated
        } else {
            // Bare number/literal: read until comma, brace, or whitespace
            int start = i;
            while (i < json.length() && ",}\n\r\t ".indexOf(json.charAt(i)) < 0) i++;
            return json.substring(start, i);
        }
    }

    public static Integer extractInt(String json, String key) {
        String s = extractString(json, key);
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else          sb.append(c);
            }
        }
        return sb.toString();
    }
}
