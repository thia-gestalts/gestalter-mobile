package thia.gestalter.core;

import com.google.gson.*;
import java.util.*;

/** Records a note can link to (notes, artifacts, topics), labelled the way desktop labels them. */
public final class Links {
    private static final List<String> KINDS = Arrays.asList("note", "artifact", "topic");

    private Links() {}

    public static final class Target {
        public final String kind;
        public final String id;
        public final String label;

        Target(String kind, String id, String label) {
            this.kind = kind;
            this.id = id;
            this.label = label;
        }
    }

    /** All linkable live records with a non-empty label, ordered by label. */
    static List<Target> project(Manifest manifest, List<Notes.Note> notes) {
        Map<String, Target> found = new LinkedHashMap<>();
        for (Notes.Note note : notes) {
            String label = Notes.headline(note);
            if (!label.isEmpty()) found.putIfAbsent(note.key, new Target("note", note.id, label));
        }
        for (JsonObject head : manifest.heads.values()) {
            JsonElement payload = head.get("value");
            if (head.get("deleted").getAsBoolean() || !(payload instanceof JsonObject)) continue;
            JsonObject value = payload.getAsJsonObject();
            String kind = Json.string(value, "kind", "");
            String id = Json.string(value, "id", "");
            if (kind.equals("note") || !KINDS.contains(kind) || id.isEmpty()) continue;
            String key = head.get("key").getAsString();
            if (!key.equals(Json.recordKey(kind, id))) continue;
            String name = Json.string(value, "name", "").trim();
            if (!name.isEmpty()) found.putIfAbsent(key, new Target(kind, id, name));
        }
        List<Target> targets = new ArrayList<>(found.values());
        targets.sort(Comparator.comparing(target -> target.label.toLowerCase(Locale.ROOT)));
        return targets;
    }

    /** The label of a linked record, or null when this device has no such record. */
    public static String label(List<Target> targets, String kind, String id) {
        for (Target target : targets) if (target.kind.equals(kind) && target.id.equals(id)) return target.label;
        return null;
    }

    /**
     * Targets matching {@code query} by label that the note may still link to: not itself and not
     * already linked.
     */
    public static List<Target> candidates(List<Target> targets, String query, String noteId,
            List<Notes.Relation> related, int limit) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Target> result = new ArrayList<>();
        for (Target target : targets) {
            if (result.size() >= limit) break;
            if (target.kind.equals("note") && target.id.equals(noteId)) continue;
            if (related.stream().anyMatch(link -> link.sameTarget(target.kind, target.id))) continue;
            if (target.label.toLowerCase(Locale.ROOT).contains(needle)) result.add(target);
        }
        return result;
    }
}
