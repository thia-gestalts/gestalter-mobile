package thia.gestalter.core;

import com.google.gson.*;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

/** Projection of note records (gestalt note/v3) and the pure rules around them. */
public final class Notes {
    static final String NO_QUEUE = "no-queue";
    private static final Pattern MARKDOWN_PREFIX =
            Pattern.compile("^(#{1,6}\\s+|>\\s*|[-*+]\\s+(\\[[ xX]\\]\\s+)?|\\d+[.)]\\s+)");

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

    /** A typed link from a note to another record, with an optional relation label. */
    public static final class Relation {
        public final String kind;
        public final String id;
        /** Null when the link has no label. */
        public final String relation;

        public Relation(String kind, String id, String relation) {
            this.kind = Objects.requireNonNull(kind);
            this.id = Objects.requireNonNull(id);
            this.relation = relation == null || relation.trim().isEmpty() ? null : relation.trim();
        }

        public Relation withRelation(String label) { return new Relation(kind, id, label); }

        public boolean sameTarget(String otherKind, String otherId) {
            return kind.equals(otherKind) && id.equals(otherId);
        }

        /** Zettelkasten placement links that desktop manages; mobile keeps them but never edits them. */
        public boolean reserved() {
            return "note".equals(kind) && ("follows".equals(relation) || "branches from".equals(relation));
        }

        JsonObject json() {
            JsonObject value = Json.object("kind", kind, "id", id);
            if (relation != null) value.addProperty("relation", relation);
            return value;
        }

        static Relation read(JsonElement element) {
            JsonObject value = element.getAsJsonObject();
            return new Relation(value.get("kind").getAsString(), value.get("id").getAsString(),
                    Json.string(value, "relation", null));
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Relation)) return false;
            Relation that = (Relation) other;
            return kind.equals(that.kind) && id.equals(that.id) && Objects.equals(relation, that.relation);
        }

        @Override public int hashCode() { return Objects.hash(kind, id, relation); }
    }

    public static final class Note {
        public final String key;
        public final String id;
        /** Identifies this version among concurrent ones for the same key. */
        public final String version;
        public final String title;
        public final String text;
        public final String extra;
        public final List<String> aliases;
        /** User tags; the reserved no-queue tag is exposed as {@link #noQueue} instead. */
        public final List<String> tags;
        public final boolean noQueue;
        public final List<Relation> related;
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
            version = observed.get(0);
            id = value.get("id").getAsString();
            title = text(value, "title");
            text = text(value, "text");
            extra = text(value, "extra");
            aliases = strings(value, "aliases");
            List<String> allTags = new ArrayList<>(strings(value, "tags"));
            noQueue = allTags.contains(NO_QUEUE);
            while (allTags.remove(NO_QUEUE)) { /* Exposed as noQueue. */ }
            tags = Collections.unmodifiableList(allTags);
            List<Relation> links = new ArrayList<>();
            for (JsonElement ref : Json.list(value, "related")) {
                try { links.add(Relation.read(ref)); }
                catch (RuntimeException unreadable) { throw new IllegalArgumentException("Unreadable note link."); }
            }
            related = Collections.unmodifiableList(links);
            JsonArray refs = Json.list(value, "attachments");
            if (refs.size() != files.size()) throw new IllegalArgumentException("Note images do not match the record.");
            List<Image> images = new ArrayList<>();
            for (int i = 0; i < refs.size(); i++)
                images.add(new Image(refs.get(i).getAsJsonObject(), files.get(i).getAsJsonArray()));
            this.images = Collections.unmodifiableList(images);
        }

        private static String text(JsonObject value, String field) {
            JsonElement item = value.get(field);
            return item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()
                    ? item.getAsString() : "";
        }

        private static List<String> strings(JsonObject value, String field) {
            List<String> result = new ArrayList<>();
            for (JsonElement item : Json.list(value, field))
                if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) result.add(item.getAsString());
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * The editable contents of a note: the editor's draft and the input of {@link Replica#save}.
     * Lists are mutable so a draft can be edited in place; {@link #copy()} before handing it off.
     */
    public static final class Fields {
        public String title = "";
        public String text = "";
        public String extra = "";
        public final List<String> aliases = new ArrayList<>();
        public final List<String> tags = new ArrayList<>();
        public boolean noQueue;
        public final List<Relation> related = new ArrayList<>();
        /** Images of the saved note that stay attached. */
        public final List<Image> images = new ArrayList<>();
        /** Images picked on this device, attached on save. */
        public final List<NewImage> added = new ArrayList<>();

        public static Fields of(Note note) {
            Fields fields = new Fields();
            if (note == null) return fields;
            fields.title = note.title;
            fields.text = note.text;
            fields.extra = note.extra;
            fields.aliases.addAll(note.aliases);
            fields.tags.addAll(note.tags);
            fields.noQueue = note.noQueue;
            fields.related.addAll(note.related);
            fields.images.addAll(note.images);
            return fields;
        }

        public static Fields capture(String text, List<NewImage> images) {
            Fields fields = new Fields();
            fields.text = text;
            fields.added.addAll(images);
            return fields;
        }

        public Fields copy() {
            Fields copy = new Fields();
            copy.title = title;
            copy.text = text;
            copy.extra = extra;
            copy.aliases.addAll(aliases);
            copy.tags.addAll(tags);
            copy.noQueue = noQueue;
            copy.related.addAll(related);
            copy.images.addAll(images);
            copy.added.addAll(added);
            return copy;
        }

        /** Mirrors note/v3: a note needs content in at least one field or an attachment. */
        public boolean hasContent() {
            return !title.trim().isEmpty() || !text.trim().isEmpty() || !extra.trim().isEmpty()
                    || !aliases.isEmpty() || !tags.isEmpty() || !related.isEmpty()
                    || !images.isEmpty() || !added.isEmpty();
        }

        /** Adds a trimmed, non-blank, not yet present entry; returns whether the list changed. */
        public static boolean addUnique(List<String> list, String entry) {
            String value = entry == null ? "" : entry.trim();
            if (value.isEmpty() || list.contains(value)) return false;
            list.add(value);
            return true;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Fields)) return false;
            Fields that = (Fields) other;
            return title.equals(that.title) && text.equals(that.text) && extra.equals(that.extra)
                    && aliases.equals(that.aliases) && tags.equals(that.tags) && noQueue == that.noQueue
                    && related.equals(that.related) && images.equals(that.images) && added.equals(that.added);
        }

        @Override public int hashCode() { return Objects.hash(title, text, aliases, tags, related); }
    }

    /** The title, else the first non-blank text line without its Markdown block prefix. */
    public static String headline(Note note) {
        return headline(note.title, note.text);
    }

    static String headline(String title, String text) {
        if (!title.trim().isEmpty()) return title.trim();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) return MARKDOWN_PREFIX.matcher(trimmed).replaceFirst("");
        }
        return "";
    }

    private static String fold(String value) { return value.toLowerCase(Locale.ROOT); }

    /** Notes whose title, text or aliases contain the query, ordered by headline. */
    public static List<Note> search(List<Note> notes, String query) {
        String needle = fold(query.trim());
        List<Note> found = new ArrayList<>();
        for (Note note : notes) {
            String haystack = fold(note.title + "\n" + note.text + "\n" + String.join("\n", note.aliases));
            if (haystack.contains(needle)) found.add(note);
        }
        found.sort(Comparator.comparing((Note note) -> headline(note).isEmpty())
                .thenComparing(note -> fold(headline(note)))
                .thenComparing(note -> note.key)
                .thenComparing(note -> note.version));
        return found;
    }

    /** The version of a note to reopen: the same version if it still exists, else any version of the key. */
    public static Note find(List<Note> notes, String key, String version) {
        Note fallback = null;
        for (Note note : notes) {
            if (!note.key.equals(key)) continue;
            if (note.version.equals(version) || note.observed.contains(version)) return note;
            if (fallback == null) fallback = note;
        }
        return fallback;
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

    /**
     * Writes {@code fields} onto a copy of the stored value (attachments are handled by the replica).
     * A field is written when it has content or the record already had it, so a captured note stays
     * {@code {kind, id, text}} and untouched fields keep their stored JSON exactly.
     */
    static void apply(JsonObject value, Note prior, Fields fields) {
        putText(value, "title", fields.title);
        putText(value, "text", fields.text);
        putText(value, "extra", fields.extra);
        putList(value, "aliases", fields.aliases, prior == null ? null : prior.aliases);
        boolean tagsUnchanged = prior != null && prior.tags.equals(fields.tags) && prior.noQueue == fields.noQueue;
        if (!tagsUnchanged) {
            List<String> tags = new ArrayList<>(fields.tags);
            if (fields.noQueue) tags.add(NO_QUEUE);
            putList(value, "tags", tags, null);
        }
        if (prior == null || !prior.related.equals(fields.related)) {
            if (!fields.related.isEmpty() || value.has("related")) {
                JsonArray related = new JsonArray();
                for (Relation link : fields.related) related.add(link.json());
                value.add("related", related);
            }
        }
    }

    static void validate(Fields fields) {
        for (List<String> list : Arrays.asList(fields.aliases, fields.tags))
            for (String item : list)
                if (item == null || item.trim().isEmpty()) throw new IllegalArgumentException("Remove empty aliases or tags.");
        if (fields.tags.contains(NO_QUEUE)) throw new IllegalArgumentException("Use Skip Queue instead of the no-queue tag.");
        Set<List<String>> targets = new HashSet<>();
        for (Relation link : fields.related)
            if (!targets.add(Arrays.asList(link.kind, link.id)))
                throw new IllegalArgumentException("A record is linked twice.");
        if (!fields.hasContent()) throw new IllegalArgumentException("Add some content before saving the note.");
    }

    private static void putText(JsonObject value, String field, String text) {
        if (!text.isEmpty() || value.has(field)) value.addProperty(field, text);
    }

    private static void putList(JsonObject value, String field, List<String> items, List<String> unchanged) {
        if (items.equals(unchanged)) return;
        if (items.isEmpty() && !value.has(field)) return;
        value.add(field, Json.array(items));
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
