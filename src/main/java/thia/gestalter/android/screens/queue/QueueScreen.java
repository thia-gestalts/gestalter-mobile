package thia.gestalter.android.screens.queue;

import android.os.Bundle;
import android.widget.*;
import java.util.*;
import thia.gestalter.android.features.notecard.NoteCard;
import thia.gestalter.android.navigation.*;
import thia.gestalter.android.ui.*;
import thia.gestalter.android.workspace.Workspace;
import thia.gestalter.core.Notes;

/** A small random selection of notes to revisit. */
public final class QueueScreen extends Screen {
    private List<String> shown = new ArrayList<>();
    private LinearLayout cards;
    private int request;

    public QueueScreen(ScreenHost host, Bundle state) {
        super(host);
        if (state != null && state.getStringArrayList("shown") != null) shown = state.getStringArrayList("shown");
    }

    @Override public Route route() { return Route.tab(Route.Kind.QUEUE); }

    @Override public String title() { return "Queue"; }

    /** Opening the tab anew draws a fresh selection; returning from a note keeps the shown one. */
    @Override public void entered() { shown = new ArrayList<>(); }

    @Override public void build(LinearLayout page, LinearLayout actions) {
        cards = host.views().column();
        host.views().fill(page, cards);
        host.views().fill(actions, host.views().button("Next", true, () -> load(true)));
        load(false);
    }

    @Override public void changed() { load(false); }

    @Override public void save(Bundle state) { state.putStringArrayList("shown", new ArrayList<>(shown)); }

    /** Next picks three notes other than the shown ones; otherwise the shown notes stay put. */
    private void load(boolean next) {
        int current = ++request;
        LinearLayout target = cards;
        if (target == null) return;
        Views ui = host.views();
        Workspace workspace = host.workspace();
        if (!workspace.ready()) {
            if (target.getChildCount() == 0) ui.fill(target, ui.text("Opening Queue…", 16, Theme.MUTED));
            return;
        }
        List<String> keep = next ? Collections.emptyList() : new ArrayList<>(shown);
        Set<String> avoid = next ? new HashSet<>(shown) : Collections.emptySet();
        workspace.queue(keep, avoid, notes -> {
            if (current != request || target != cards) return;
            List<String> keys = new ArrayList<>();
            for (Notes.Note note : notes) if (!keys.contains(note.key)) keys.add(note.key);
            shown = keys;
            target.removeAllViews();
            if (notes.isEmpty()) ui.fill(target, ui.text("No notes in Queue yet.", 16, Theme.MUTED));
            for (Notes.Note note : notes) {
                LinearLayout card = NoteCard.view(ui, note);
                LinearLayout controls = ui.row();
                ui.weighted(controls, ui.button(note.conflict ? "Open conflicting version" : "Open note", false,
                        () -> host.go(Route.note(note))));
                controls.addView(exclude(note), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
                ui.fill(card, controls);
                ui.margin(target, card, 8);
            }
        }, error -> host.message(Workspace.describe(error, false), true));
    }

    private ImageButton exclude(Notes.Note note) {
        ImageButton[] button = new ImageButton[1];
        button[0] = host.views().icon(Icons.HIDE, "Exclude from Queue", () -> {
            button[0].setEnabled(false);
            host.workspace().excludeFromQueue(note, () -> {
                load(false);
                host.message("Excluded from Queue.", false);
            }, error -> {
                button[0].setEnabled(true);
                host.message(Workspace.describe(error, false), true);
            });
        });
        return button[0];
    }
}
