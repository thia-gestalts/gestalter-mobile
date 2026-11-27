package thia.gestalter.android.features.noteimages;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.view.View;
import android.widget.*;
import java.util.List;
import thia.gestalter.android.navigation.ScreenHost;
import thia.gestalter.android.platform.ImagePicker;
import thia.gestalter.android.ui.*;
import thia.gestalter.core.Notes;

/**
 * The images of a note draft: thumbnails of kept and newly picked images, each removable and
 * viewable, plus the system image picker. Edits the given lists in place.
 */
public final class ImageAttachments {
    private final ScreenHost host;
    private final List<Notes.Image> kept;
    private final List<Notes.NewImage> added;
    private final Runnable changed;
    private final ImagePicker picker = new ImagePicker();
    private LinearLayout box;
    private boolean enabled = true;

    public ImageAttachments(ScreenHost host, List<Notes.Image> kept, List<Notes.NewImage> added, Runnable changed) {
        this.host = host;
        this.kept = kept;
        this.added = added;
        this.changed = changed;
    }

    public View view() {
        box = host.views().column();
        refresh();
        return box;
    }

    public Button attachButton() {
        return host.views().button("Attach image", false, this::choose);
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (box != null) Views.setEnabled(box, value);
    }

    public void choose() {
        if (!enabled) return;
        try {
            picker.open(host.activity());
        } catch (ActivityNotFoundException error) {
            host.message("This device has no image picker.", true);
        }
    }

    /** Handles the picker's result; returns false for results that belong to someone else. */
    public boolean result(int request, int resultCode, Intent data) {
        if (request != ImagePicker.REQUEST) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        try {
            added.add(picker.selected(host.activity().getApplicationContext().getContentResolver(), data.getData()));
            refresh();
            changed.run();
        } catch (IllegalArgumentException | SecurityException error) {
            host.message(error.getMessage(), true);
        }
        return true;
    }

    private void refresh() {
        if (box == null) return;
        box.removeAllViews();
        for (Notes.Image image : kept) {
            Notes.OpenImage source;
            try { source = host.workspace().image(image); }
            catch (IllegalStateException closed) { continue; }
            row(source, () -> kept.remove(image));
        }
        for (Notes.NewImage image : added) row(image::open, () -> added.remove(image));
    }

    private void row(Notes.OpenImage source, Runnable remove) {
        Views ui = host.views();
        LinearLayout item = ui.row();
        ImageView image = new ImageView(ui.context);
        image.setContentDescription("Note attachment");
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setBackgroundColor(Theme.PANEL);
        item.addView(image, new LinearLayout.LayoutParams(ui.dp(84), ui.dp(68)));
        TextView name = ui.text("Image", 14, Theme.MUTED);
        name.setPadding(ui.dp(12), 0, 0, 0);
        ui.weighted(item, name);
        item.addView(ui.button("Remove image", false, () -> {
            if (!enabled) return;
            remove.run();
            refresh();
            changed.run();
        }));
        ui.margin(box, item, 4);
        host.workspace().thumbnail(source, bitmap -> {
            if (host.activity().isDestroyed()) return;
            image.setImageBitmap(bitmap);
            image.setContentDescription("Open attached image");
            image.setOnClickListener(view -> Dialogs.image(ui.context, bitmap));
        }, error -> name.setText("Preview unavailable"));
    }
}
