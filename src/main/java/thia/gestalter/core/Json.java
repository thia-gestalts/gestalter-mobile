package thia.gestalter.core;

import com.google.gson.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public final class Json {
    private static final Gson STRICT = new GsonBuilder().setStrictness(Strictness.STRICT).create();

    private Json() {}

    public static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add((String) pairs[i], value(pairs[i + 1]));
        return result;
    }

    public static JsonArray array(Collection<?> items) {
        JsonArray result = new JsonArray(items.size());
        for (Object item : items) result.add(value(item));
        return result;
    }

    private static JsonElement value(Object item) {
        if (item == null) return JsonNull.INSTANCE;
        if (item instanceof JsonElement) return (JsonElement) item;
        if (item instanceof String) return new JsonPrimitive((String) item);
        if (item instanceof Number) return new JsonPrimitive((Number) item);
        if (item instanceof Boolean) return new JsonPrimitive((Boolean) item);
        throw new IllegalArgumentException("Expected a JSON value");
    }

    public static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    public static byte[] bytes(JsonElement data) {
        String raw = data.toString();
        StringBuilder escaped = null;
        int start = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isSurrogate(c)) continue;
            if (Character.isHighSurrogate(c) && i + 1 < raw.length()
                    && Character.isLowSurrogate(raw.charAt(i + 1))) {
                i++;
                continue;
            }
            if (escaped == null) escaped = new StringBuilder(raw.length());
            escaped.append(raw, start, i).append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            start = i + 1;
        }
        return bytes(escaped == null ? raw : escaped.append(raw, start, raw.length()).toString());
    }

    public static JsonElement parse(byte[] bytes) {
        try {
            return STRICT.fromJson(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(),
                    JsonElement.class);
        } catch (CharacterCodingException error) {
            throw new JsonSyntaxException("Invalid UTF-8 in Olymp data.", error);
        }
    }

    public static String string(JsonObject data, String field, String fallback) {
        JsonElement v = data.get(field);
        return v instanceof JsonPrimitive && ((JsonPrimitive) v).isString() ? v.getAsString() : fallback;
    }

    public static JsonArray list(JsonObject data, String field) {
        JsonElement v = data.get(field);
        return v instanceof JsonArray ? (JsonArray) v : new JsonArray();
    }

    // JSON.stringify tuple escaping for record keys and authenticated contexts.
    public static String tuple(Object... items) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) out.append(',');
            if (!(items[i] instanceof String)) {
                out.append(value(items[i]));
                continue;
            }
            String text = (String) items[i];
            out.append('"');
            for (int j = 0; j < text.length(); j++) {
                char c = text.charAt(j);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\b': out.append("\\b"); break;
                    case '\t': out.append("\\t"); break;
                    case '\n': out.append("\\n"); break;
                    case '\f': out.append("\\f"); break;
                    case '\r': out.append("\\r"); break;
                    default:
                        if (Character.isHighSurrogate(c) && j + 1 < text.length()
                                && Character.isLowSurrogate(text.charAt(j + 1))) {
                            out.append(c).append(text.charAt(++j));
                        } else if (c < 32 || Character.isSurrogate(c)) {
                            out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                        } else out.append(c);
                }
            }
            out.append('"');
        }
        return out.append(']').toString();
    }

    public static String recordKey(String kind, String id) {
        if (kind.isEmpty() || id.isEmpty()) throw new IllegalArgumentException("Missing record identity");
        return "gestalt/" + tuple(kind, id);
    }
}
