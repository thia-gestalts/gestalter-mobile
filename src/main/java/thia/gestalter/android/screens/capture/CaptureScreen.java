package thia.gestalter.android.screens.capture;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import java.util.*;
import thia.gestalter.android.features.noteimages.ImageAttachments;
import thia.gestalter.android.navigation.*;
import thia.gestalter.android.ui.Views;
import thia.gestalter.android.workspace.Workspace;
import thia.gestalter.core.*;

/** Quick add: write a thought, optionally attach images, save. The note holds only text and images. */
public final class CaptureScreen extends Screen {
    private final List<Notes.Image> noKeptImages = new ArrayList<>();
    private final List<Notes.NewImage> picked = new ArrayList<>();
    private final ImageAttachments images;
    private String text;
    private Notes.Note latest;
    private Replica latestIn;
    private String feedback = "";
    private boolean busy;
    private LinearLayout page;
    private LinearLayout actions;
    private Button save;
    private Button saveEdit;

    public CaptureScreen(ScreenHost host, Bundle state) {
        super(host);
        text = state == null ? "" : state.getString("text", "");
        images = new ImageAttachments(host, noKeptImages, picked, this::update);
    }

    @Override public Route route() { return Route.tab(Route.Kind.CAPTURE); }

    @Override public String title() { return "Capture"; }

    @Override public boolean busy() { return busy; }

    @Override public void build(LinearLayout page, LinearLayout actions) {
        this.page = page;
        this.actions = actions;
        Views ui = host.views();
        EditText input = ui.editor("Write a note…", text, true, 156);
        input.setContentDescription("Note text");
        ui.fill(page, input);
        Views.onText(input, value -> {
            text = value;
            feedback = "";
            if (!busy) host.message("", false);
            update();
        });
        ui.margin(page, images.view(), 10);
        if (latest != null) ui.margin(page, ui.button("Edit last saved note", false,
                () -> { if (!busy) host.go(Route.note(latest)); }), 10);

        LinearLayout options = ui.row();
        ui.weighted(options, images.attachButton());
        ui.fill(actions, options);
        LinearLayout submit = ui.row();
        saveEdit = ui.button("Save & edit", false, () -> save(true));
        ui.weighted(submit, saveEdit);
        save = ui.button("Save", true, () -> save(false));
        ui.weighted(submit, save);
        ui.fill(actions, submit);
        if (!feedback.isEmpty()) host.message(feedback, false);
        update();
    }

    @Override public void changed() {
        Profile profile = host.workspace().profile();
        // After signing in or out, the last saved note belongs to another workspace.
        if (latest != null && (profile == null || profile.replica() != latestIn)) {
            latest = null;
            feedback = "";
            if (!busy) host.render();
            return;
        }
        update();
    }

    @Override public void result(int request, int resultCode, Intent data) {
        images.result(request, resultCode, data);
    }

    @Override public void save(Bundle state) { state.putString("text", text); }

    private void update() {
        boolean available = !busy && host.workspace().ready() && !text.trim().isEmpty();
        if (save != null) save.setEnabled(available);
        if (saveEdit != null) saveEdit.setEnabled(available);
    }

    private void save(boolean openEditor) {
        Workspace workspace = host.workspace();
        if (busy || !workspace.ready()) return;
        busy = true;
        update();
        host.hideKeyboard();
        host.message("Saving…", false);
        Views.setEnabled(page, false);
        Views.setEnabled(actions, false);
        Replica replica = workspace.profile().replica();
        workspace.capture(text, picked, saved -> {
            busy = false;
            latest = saved;
            latestIn = replica;
            text = "";
            picked.clear();
            feedback = "Saved on this device.";
            if (openEditor) host.go(Route.note(saved));
            else host.render();
        }, error -> {
            busy = false;
            Views.setEnabled(page, true);
            Views.setEnabled(actions, true);
            update();
            host.message(Workspace.describe(error, false), true);
        });
    }
}
