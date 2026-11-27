package thia.gestalter.android.screens.note;

import android.os.Bundle;
import java.util.ArrayList;
import thia.gestalter.core.Notes;

/** The editor's working copy of a note next to the version it started from. */
final class NoteDraft {
    final Notes.Note original;
    private final Notes.Fields baseline;
    final Notes.Fields fields;

    NoteDraft(Notes.Note original) {
        this.original = original;
        baseline = Notes.Fields.of(original);
        fields = baseline.copy();
    }

    boolean dirty() { return !fields.equals(baseline); }

    boolean savable() { return dirty() && fields.hasContent(); }

    /** The note's own id, or null before its first save. */
    String noteId() { return original == null ? null : original.id; }

    /** Text fields survive the process being killed; links and images are kept only while it runs. */
    void save(Bundle state) {
        state.putString("title", fields.title);
        state.putString("text", fields.text);
        state.putString("extra", fields.extra);
        state.putStringArrayList("aliases", new ArrayList<>(fields.aliases));
        state.putStringArrayList("tags", new ArrayList<>(fields.tags));
        state.putBoolean("noQueue", fields.noQueue);
    }

    void restore(Bundle state) {
        fields.title = state.getString("title", fields.title);
        fields.text = state.getString("text", fields.text);
        fields.extra = state.getString("extra", fields.extra);
        if (state.getStringArrayList("aliases") != null) {
            fields.aliases.clear();
            fields.aliases.addAll(state.getStringArrayList("aliases"));
        }
        if (state.getStringArrayList("tags") != null) {
            fields.tags.clear();
            fields.tags.addAll(state.getStringArrayList("tags"));
        }
        fields.noQueue = state.getBoolean("noQueue", fields.noQueue);
    }
}
