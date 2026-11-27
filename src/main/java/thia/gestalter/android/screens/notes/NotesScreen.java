package thia.gestalter.android.screens.notes;

import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import java.util.List;
import thia.gestalter.android.features.notecard.NoteCard;
import thia.gestalter.android.navigation.*;
import thia.gestalter.android.ui.*;
import thia.gestalter.android.workspace.Workspace;
import thia.gestalter.core.Notes;

/** All notes on this device: search, open, create and delete. */
public final class NotesScreen extends Screen {
    private String query;
    private List<Notes.Note> notes;
    private LinearLayout list;
    private int request;
    private boolean busy;

    public NotesScreen(ScreenHost host, Bundle state) {
        super(host);
        query = state == null ? "" : state.getString("query", "");
    }

    @Override public Route route() { return Route.tab(Route.Kind.NOTES); }

    @Override public String title() { return "Notes"; }

    @Override public boolean busy() { return busy; }

    @Override public void build(LinearLayout page, LinearLayout actions) {
        Views ui = host.views();
        EditText search = ui.editor("Search notes", query, false, 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_FILTER);
        ui.fill(page, search);
        Views.onText(search, value -> {
            query = value;
            show();
        });
        list = ui.column();
        ui.fill(page, list);
        ui.fill(actions, ui.button("New note", true, () -> { if (!busy) host.go(Route.newNote()); }));
        show();
        load();
    }

    @Override public void changed() { if (!busy) load(); }

    @Override public void save(Bundle state) { state.putString("query", query); }

    private void load() {
        if (!host.workspace().ready()) return;
        int current = ++request;
        host.workspace().notes(found -> {
            if (current != request) return;
            notes = found;
            show();
        }, error -> host.message(Workspace.describe(error, false), true));
    }

    private void show() {
        if (list == null) return;
        Views ui = host.views();
        list.removeAllViews();
        if (notes == null) {
            ui.margin(list, ui.text(host.workspace().ready() ? "Loading notes…" : "Opening workspace…", 16, Theme.MUTED), 12);
            return;
        }
        List<Notes.Note> found = Notes.search(notes, query);
        if (found.isEmpty())
            ui.margin(list, ui.text(query.trim().isEmpty() ? "No notes yet." : "No matching notes.", 16, Theme.MUTED), 12);
        for (Notes.Note note : found) {
            LinearLayout card = NoteCard.view(ui, note);
            card.setContentDescription("Open note " + Notes.headline(note) + ". Long press to delete.");
            card.setOnClickListener(view -> { if (!busy) host.go(Route.note(note)); });
            card.setOnLongClickListener(view -> {
                confirmDelete(note);
                return true;
            });
            ui.margin(list, card, 8);
        }
    }

    private void confirmDelete(Notes.Note note) {
        if (busy) return;
        String headline = Notes.headline(note);
        Dialogs.confirm(host.activity(), headline.isEmpty() ? "Delete this note?" : "Delete “" + headline + "”?",
                "Cancel", "Delete", () -> {
                    if (busy) return;
                    busy = true;
                    host.message("Deleting…", false);
                    host.workspace().delete(note, () -> {
                        busy = false;
                        host.message("Note deleted.", false);
                        load();
                    }, error -> {
                        busy = false;
                        host.message(Workspace.describe(error, false), true);
                    });
                });
    }
}
