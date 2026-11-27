package thia.gestalter.android.screens.note;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.util.List;
import thia.gestalter.android.features.noteimages.ImageAttachments;
import thia.gestalter.android.navigation.*;
import thia.gestalter.android.ui.*;
import thia.gestalter.android.workspace.Workspace;
import thia.gestalter.core.*;

/**
 * The full note editor: title, text, extra, aliases, tags, Queue opt-out, images and related
 * records. Changes are kept in a draft until Save; leaving with unsaved changes asks first.
 */
public final class NoteScreen extends Screen {
    private Route route;
    private NoteDraft draft;
    private Bundle pending;
    private boolean missing;
    private boolean busy;
    private List<Links.Target> targets;
    private ImageAttachments images;
    private RelatedSection related;
    private LinearLayout page;
    private LinearLayout actions;
    private Button save;

    public NoteScreen(ScreenHost host, Route route, Bundle state) {
        super(host);
        this.route = route;
        pending = state;
        if (route.key == null) open(null);
        else load();
        loadTargets();
    }

    @Override public Route route() { return route; }

    @Override public String title() {
        return draft == null ? "Note" : draft.original == null ? "New note" : "Edit note";
    }

    @Override public boolean busy() { return busy; }

    private void open(Notes.Note note) {
        draft = new NoteDraft(note);
        if (pending != null) draft.restore(pending);
        pending = null;
        images = new ImageAttachments(host, draft.fields.images, draft.fields.added, this::update);
        related = new RelatedSection(host.views(), draft, this::update, key -> host.go(Route.note(key)));
        if (targets != null) related.targets(targets);
    }

    private void load() {
        Workspace workspace = host.workspace();
        if (!workspace.ready()) return;
        workspace.notes(notes -> {
            if (draft != null || missing) return;
            Notes.Note note = Notes.find(notes, route.key, route.version);
            if (note == null) missing = true;
            else {
                route = Route.note(note);
                open(note);
            }
            host.render();
        }, error -> host.message(Workspace.describe(error, false), true));
    }

    private void loadTargets() {
        if (!host.workspace().ready()) return;
        host.workspace().linkTargets(found -> {
            targets = found;
            if (related != null) related.targets(found);
        }, error -> { /* Links keep showing without labels. */ });
    }

    @Override public void changed() {
        if (draft == null && !missing) load();
        loadTargets();
        update();
    }

    @Override public void build(LinearLayout page, LinearLayout actions) {
        this.page = page;
        this.actions = actions;
        save = null;
        Views ui = host.views();
        if (missing) {
            ui.fill(page, ui.text("This note is unavailable. It may have been deleted on another device.", 16, Theme.MUTED));
            return;
        }
        if (draft == null) {
            ui.fill(page, ui.text("Loading note…", 16, Theme.MUTED));
            return;
        }
        Notes.Fields fields = draft.fields;
        if (draft.original != null && draft.original.conflict)
            ui.fill(page, ui.text("This note has concurrent versions. Changes here apply to this version.", 14, Theme.MUTED));

        EditText title = ui.editor("Title", fields.title, false, 0);
        Views.onText(title, value -> { fields.title = value; update(); });
        ui.margin(page, title, 4);
        EditText text = ui.editor("Text (Markdown)", fields.text, true, 180);
        Views.onText(text, value -> { fields.text = value; update(); });
        ui.margin(page, text, 8);
        EditText extra = ui.editor("Extra (Markdown)", fields.extra, true, 84);
        Views.onText(extra, value -> { fields.extra = value; update(); });
        ui.margin(page, extra, 8);

        ui.margin(page, new ListField(ui, "Aliases", "alias", fields.aliases, this::update).view(), 12);
        ui.margin(page, new ListField(ui, "Tags", "tag", fields.tags, this::update).view(), 12);
        CheckBox skip = new CheckBox(ui.context);
        skip.setText("Skip Queue");
        skip.setChecked(fields.noQueue);
        skip.setOnCheckedChangeListener((view, checked) -> { fields.noQueue = checked; update(); });
        ui.margin(page, skip, 8);

        ui.margin(page, ui.heading("Images", 16), 14);
        ui.fill(page, images.view());
        ui.margin(page, images.attachButton(), 4);
        ui.margin(page, related.view(), 16);

        LinearLayout submit = ui.row();
        if (draft.original != null) ui.weighted(submit, ui.button("Delete", false, this::confirmDelete));
        save = ui.button("Save", true, this::save);
        ui.weighted(submit, save);
        ui.fill(actions, submit);
        update();
    }

    private void update() {
        if (save != null) save.setEnabled(!busy && host.workspace().ready() && draft != null && draft.savable());
    }

    @Override public void requestLeave(Runnable proceed) {
        if (draft == null || !draft.dirty()) {
            proceed.run();
            return;
        }
        Dialogs.confirm(host.activity(), "Discard changes to this note?", "Keep editing", "Discard", proceed);
    }

    @Override public void result(int request, int resultCode, Intent data) {
        if (images != null) images.result(request, resultCode, data);
    }

    @Override public void save(Bundle state) {
        if (draft != null) draft.save(state);
    }

    private void working(String message) {
        busy = true;
        update();
        host.hideKeyboard();
        host.message(message, false);
        Views.setEnabled(page, false);
        Views.setEnabled(actions, false);
    }

    private void failed(Exception error) {
        busy = false;
        Views.setEnabled(page, true);
        Views.setEnabled(actions, true);
        update();
        host.message(Workspace.describe(error, false), true);
    }

    private void save() {
        if (busy || draft == null || !draft.savable()) return;
        working("Saving…");
        host.workspace().save(draft.original, draft.fields, saved -> {
            busy = false;
            route = Route.note(saved);
            open(saved);
            host.render();
            host.message("Saved on this device.", false);
        }, this::failed);
    }

    private void confirmDelete() {
        if (busy || draft == null || draft.original == null) return;
        Notes.Note note = draft.original;
        Dialogs.confirm(host.activity(), "Delete this note?", "Cancel", "Delete", () -> {
            if (busy) return;
            working("Deleting…");
            host.workspace().delete(note, () -> {
                busy = false;
                host.close();
                host.message("Note deleted.", false);
            }, this::failed);
        });
    }
}
