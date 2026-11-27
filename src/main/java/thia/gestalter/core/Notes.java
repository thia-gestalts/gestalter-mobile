package thia.gestalter.core;

import com.google.gson.*;
import java.io.InputStream;
import java.util.*;

public final class Notes {
    private static final String NO_QUEUE = "no-queue";

    private Notes() {}

    public static final class Image {
        public final String id;
        public final String mediaType;
        final JsonObject reference;
        final JsonArray chunks;

        Image(JsonObject reference, JsonArray chunks) {
            this.reference = reference;
            this.chunks = chunks;
            this.id = reference.get("id").getAsString();
            this.mediaType = reference.get("mediaType").getAsString();
        }
    }

    @FunctionalInterface
    public interface OpenImage { InputStream open() throws java.io.IOException; }

    public static final class NewImage {
        final String mediaType;
        final OpenImage source;

        public NewImage(String mediaType, OpenImage source) {
            if (!mediaType.matches("image/[a-z0-9][a-z0-9!#$&^_.+\\-]*"))
                throw new IllegalArgumentException("Choose an image.");
            this.mediaType = mediaType;
            this.source = source;
        }

        public InputStream open() throws java.io.IOException { return source.open(); }
    }

    public static final class Note {
        public final String key;
        public final String text;
        public final boolean noQueue;
        public final List<Image> images;
        public final boolean conflict;
        final JsonObject value;
        final JsonArray files;
        final List<String> observed;

        Note(String key, JsonObject value, JsonArray files, List<String> observed, boolean conflict) {
            this.key = key;
            this.value = value;
            this.files = files;
            this.observed = Collections.unmodifiableList(new ArrayList<>(observed));
            this.conflict = conflict;
            text = Json.string(value, "text", "");
            noQueue = Json.list(value, "tags").contains(new JsonPrimitive(NO_QUEUE));
            JsonArray refs = Json.list(value, "attachments");
            if (refs.size() != files.size()) throw new IllegalArgumentException("Note images do not match the record.");
            List<Image> images = new ArrayList<>();
            for (int i = 0; i < refs.size(); i++)
                images.add(new Image(refs.get(i).getAsJsonObject(), files.get(i).getAsJsonArray()));
            this.images = Collections.unmodifiableList(images);
        }
    }

    private static boolean live(JsonObject head) { return !head.get("deleted").getAsBoolean(); }

    private static boolean isNote(JsonObject head) {
        JsonElement payload = head.get("value");
        if (!live(head) || !(payload instanceof JsonObject)) return false;
        JsonObject value = payload.getAsJsonObject();
        String id = Json.string(value, "id", "");
        return "note".equals(Json.string(value, "kind", "")) && !id.isEmpty()
                && head.get("key").getAsString().equals(Json.recordKey("note", id));
    }

    static List<Note> project(Manifest manifest) {
        Map<String, List<JsonObject>> groups = new LinkedHashMap<>();
        for (JsonObject head : manifest.heads.values())
            groups.computeIfAbsent(head.get("key").getAsString(), key -> new ArrayList<>()).add(head);
        List<Note> notes = new ArrayList<>();
        for (Map.Entry<String, List<JsonObject>> group : groups.entrySet()) {
            List<JsonObject> heads = group.getValue();
            if (heads.stream().noneMatch(Notes::isNote)) continue;
            JsonObject composed = compose(heads);
            if (composed != null) {
                addNote(notes, group.getKey(), composed, ids(heads), false);
                continue;
            }
            Map<JsonObject, List<JsonObject>> versions = new LinkedHashMap<>();
            for (JsonObject head : heads) {
                JsonObject signature = Json.object("deleted", head.get("deleted"), "value", head.get("value"),
                        "files", head.get("files"));
                versions.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(head);
            }
            for (List<JsonObject> version : versions.values())
                addNote(notes, group.getKey(), version.get(0), ids(version), versions.size() > 1);
        }
        return notes;
    }

    private static List<String> ids(List<JsonObject> heads) {
        List<String> result = new ArrayList<>(heads.size());
        for (JsonObject head : heads) result.add(head.get("id").getAsString());
        return result;
    }

    private static void addNote(List<Note> notes, String key, JsonObject head, List<String> ids, boolean conflict) {
        if (!isNote(head)) return;
        try {
            notes.add(new Note(key, head.getAsJsonObject("value"), head.getAsJsonArray("files"), ids, conflict));
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException unreadableNote) {
            // The complete record stays in the replica if this projection cannot render it.
        }
    }

    private static JsonObject compose(List<JsonObject> heads) {
        if (heads.size() < 2 || !heads.get(0).has("edit")) return null;
        Map<String, JsonElement> changes = new LinkedHashMap<>();
        JsonArray files = null;
        JsonObject first = heads.get(0);
        try {
            String parent = first.getAsJsonObject("edit").get("from").getAsString();
            for (JsonObject head : heads) {
                if (!isNote(head) || !head.has("edit")) return null;
                JsonObject edit = head.getAsJsonObject("edit");
                if (!parent.equals(edit.get("from").getAsString())) return null;
                JsonObject value = head.getAsJsonObject("value");
                for (JsonElement name : edit.getAsJsonArray("fields")) {
                    String field = name.getAsString();
                    JsonElement change = value.get(field);
                    if (changes.containsKey(field) && !Objects.equals(change, changes.get(field))) return null;
                    changes.put(field, change);
                }
                if (edit.get("files").getAsBoolean()) {
                    JsonArray candidate = head.getAsJsonArray("files");
                    if (files != null && !files.equals(candidate)) return null;
                    files = candidate;
                }
            }
            JsonObject result = first.deepCopy();
            JsonObject value = result.getAsJsonObject("value");
            changes.forEach((field, change) -> {
                if (change == null) value.remove(field);
                else value.add(field, change);
            });
            if (files != null) result.add("files", files);
            return result;
        } catch (RuntimeException invalidEditMetadata) {
            return null;
        }
    }

    static void updateNoQueue(JsonObject value, boolean noQueue) {
        JsonArray tags = Json.list(value, "tags");
        JsonPrimitive tag = new JsonPrimitive(NO_QUEUE);
        if (tags.contains(tag) == noQueue) return;
        if (noQueue) tags.add(tag);
        else while (tags.remove(tag)) { /* Remove duplicates of this flag only. */ }
        value.add("tags", tags);
    }

    public static List<Note> queue(List<Note> notes, Random random) {
        return queue(notes, random, Collections.emptyList(), Collections.emptySet());
    }

    /**
     * Picks three random notes without the no-queue tag. Still-eligible notes in {@code keep} stay
     * selected; the remaining slots prefer notes outside {@code avoid}, so "Next" shows new notes
     * whenever enough exist.
     */
    public static List<Note> queue(List<Note> notes, Random random, Collection<String> keep, Set<String> avoid) {
        Set<String> excluded = new HashSet<>();
        Map<String, List<Note>> groups = new LinkedHashMap<>();
        for (Note note : notes) {
            if (note.noQueue) excluded.add(note.key);
            groups.computeIfAbsent(note.key, ignored -> new ArrayList<>()).add(note);
        }
        List<String> eligible = new ArrayList<>(groups.keySet());
        eligible.removeAll(excluded);
        List<String> chosen = new ArrayList<>();
        for (String key : keep)
            if (chosen.size() < 3 && eligible.contains(key) && !chosen.contains(key)) chosen.add(key);
        eligible.removeAll(chosen);
        Collections.shuffle(eligible, random);
        eligible.sort(Comparator.comparing(avoid::contains));
        for (String key : eligible) if (chosen.size() < 3) chosen.add(key);
        List<Note> selected = new ArrayList<>();
        for (String key : chosen) selected.addAll(groups.get(key));
        return selected;
    }
}
