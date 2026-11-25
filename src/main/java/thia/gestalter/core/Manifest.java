package thia.gestalter.core;

import com.google.gson.*;
import java.util.*;

final class Manifest {
    final SortedMap<String, JsonObject> heads;
    final SortedSet<String> removed;

    Manifest() {
        this(new TreeMap<>(), new TreeSet<>());
    }

    private Manifest(SortedMap<String, JsonObject> heads, SortedSet<String> removed) {
        this.heads = heads;
        this.removed = removed;
    }

    static Manifest read(JsonObject document) {
        TreeMap<String, JsonObject> heads = new TreeMap<>();
        TreeSet<String> removed = new TreeSet<>();
        for (JsonElement item : document.getAsJsonArray("removed")) removed.add(item.getAsString());
        for (JsonElement item : document.getAsJsonArray("heads")) {
            JsonObject head = item.getAsJsonObject();
            String id = head.get("id").getAsString();
            head.get("key").getAsString();
            head.get("deleted").getAsBoolean();
            if (!head.has("value")) throw new IllegalArgumentException("Missing Olymp payload.");
            for (JsonElement file : head.getAsJsonArray("files")) {
                for (JsonElement chunk : file.getAsJsonArray())
                    if (!chunk.getAsString().matches("[0-9a-f]{64}"))
                        throw new IllegalArgumentException("Invalid Olymp chunk reference.");
            }
            if (!removed.contains(id)) insert(heads, id, head);
        }
        return new Manifest(heads, removed);
    }

    JsonObject json() {
        return Json.object("heads", Json.array(heads.values()), "removed", Json.array(removed));
    }

    private static void insert(Map<String, JsonObject> target, String id, JsonObject head) {
        JsonObject existing = target.putIfAbsent(id, head);
        if (existing != null && !existing.equals(head))
            throw new IllegalArgumentException("An Olymp operation has different contents for the same ID.");
    }

    Manifest merge(Manifest other) {
        TreeMap<String, JsonObject> combined = new TreeMap<>();
        TreeSet<String> tombstones = new TreeSet<>(removed);
        tombstones.addAll(other.removed);
        for (Manifest source : new Manifest[]{this, other}) {
            source.heads.forEach((id, head) -> {
                if (!tombstones.contains(id)) insert(combined, id, head);
            });
        }
        return new Manifest(combined, tombstones);
    }

    Manifest append(JsonObject head, Collection<String> observed) {
        TreeMap<String, JsonObject> incoming = new TreeMap<>();
        incoming.put(head.get("id").getAsString(), head);
        return merge(new Manifest(incoming, new TreeSet<>(observed)));
    }

    Set<String> references() {
        Set<String> refs = new TreeSet<>();
        for (JsonObject head : heads.values())
            for (JsonElement file : head.getAsJsonArray("files"))
                for (JsonElement id : file.getAsJsonArray()) refs.add(id.getAsString());
        return refs;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof Manifest)) return false;
        Manifest manifest = (Manifest) other;
        return heads.equals(manifest.heads) && removed.equals(manifest.removed);
    }

    @Override public int hashCode() { return Objects.hash(heads, removed); }
}
