package thia.gestalter.android.navigation;

import android.os.Bundle;
import java.util.Objects;
import thia.gestalter.core.Notes;

/** Where the app is: one of the tabs, or a note editor pushed on top of a tab. */
public final class Route {
    public enum Kind { CAPTURE, NOTES, QUEUE, ACCOUNT, NOTE }

    public final Kind kind;
    /** For {@link Kind#NOTE}: the record key, or null for a note that is not saved yet. */
    public final String key;
    /** For {@link Kind#NOTE}: which concurrent version to open, if any. */
    public final String version;

    private Route(Kind kind, String key, String version) {
        this.kind = kind;
        this.key = key;
        this.version = version;
    }

    public static Route tab(Kind kind) {
        if (kind == Kind.NOTE) throw new IllegalArgumentException("A note is not a tab.");
        return new Route(kind, null, null);
    }

    public static Route note(Notes.Note note) { return new Route(Kind.NOTE, note.key, note.version); }

    /** A note by record key, e.g. from a link; any of its versions. */
    public static Route note(String key) { return new Route(Kind.NOTE, key, null); }

    public static Route newNote() { return new Route(Kind.NOTE, null, null); }

    public boolean isTab() { return kind != Kind.NOTE; }

    public Bundle bundle() {
        Bundle state = new Bundle();
        state.putString("kind", kind.name());
        state.putString("key", key);
        state.putString("version", version);
        return state;
    }

    public static Route from(Bundle state) {
        return new Route(Kind.valueOf(state.getString("kind")), state.getString("key"), state.getString("version"));
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof Route)) return false;
        Route that = (Route) other;
        return kind == that.kind && Objects.equals(key, that.key) && Objects.equals(version, that.version);
    }

    @Override public int hashCode() { return Objects.hash(kind, key, version); }
}
