package thia.gestalter.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import thia.gestalter.core.*;

public final class MainActivity extends Activity {
    private static final int INK = 0xff232720;
    private static final int MUTED = 0xff50584b;
    private static final int ACCENT = 0xff365746;
    private static final int PAPER = 0xfff9f9ef;
    private static final int PANEL = 0xffeeefe4;
    private static final int ERROR = 0xff8e2931;

    private enum Screen { CAPTURE, QUEUE, EDITOR, ACCOUNT }

    private static final class Draft {
        final Notes.Note original;
        final List<Notes.Image> existing;
        final List<Notes.NewImage> picked = new ArrayList<>();
        String text;
        boolean noQueue;

        Draft(Notes.Note original) {
            this.original = original;
            text = original == null ? "" : original.text;
            noQueue = original != null && original.noQueue;
            existing = original == null ? new ArrayList<>() : new ArrayList<>(original.images);
        }

        boolean changed() {
            return original == null ? !text.isEmpty() || !picked.isEmpty() || noQueue
                    : !text.equals(original.text) || noQueue != original.noQueue || !picked.isEmpty()
                      || !existing.equals(original.images);
        }
    }

    private GestalterApp app;
    private final ImagePicker picker = new ImagePicker();
    private final Runnable observer = this::refresh;
    private Screen screen = Screen.CAPTURE;
    private Screen editorParent = Screen.CAPTURE;
    private Draft capture = new Draft(null);
    private Draft editor;
    private Draft picking;
    private Notes.Note latest;
    private boolean busy;
    private long queueSeed = new Random().nextLong();
    private int queueRequest;
    private LinearLayout page;
    private LinearLayout queueCards;
    private LinearLayout actions;
    private LinearLayout nav;
    private TextView workspace;
    private TextView feedback;
    private Button save;
    private Button saveEdit;
    private String captureFeedback = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (GestalterApp) getApplication();
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (state != null) capture.text = state.getString("captureText", "");
        show(Screen.CAPTURE);
    }

    @Override protected void onStart() {
        super.onStart();
        app.observe(observer);
        app.foreground(true);
        refresh();
    }

    @Override protected void onStop() {
        app.removeObserver(observer);
        app.foreground(false);
        super.onStop();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("captureText", capture.text);
        super.onSaveInstanceState(state);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private void fill(LinearLayout parent, View view) {
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    private void space(LinearLayout parent, int height) {
        parent.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(height)));
    }

    private void margin(LinearLayout parent, View view, int height) {
        space(parent, height);
        fill(parent, view);
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setTextColor(primary ? Color.WHITE : INK);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? ACCENT : PANEL));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void weighted(LinearLayout row, View view) {
        row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void show(Screen next) {
        screen = next;
        queueCards = null;
        save = null;
        saveEdit = null;
        LinearLayout root = column();
        root.setBackgroundColor(PAPER);
        LinearLayout header = column();
        header.setPadding(dp(20), dp(12), dp(20), dp(9));
        TextView title = text(next == Screen.EDITOR ? "Edit note" : next == Screen.QUEUE ? "Queue"
                : next == Screen.ACCOUNT ? "Olymp account" : "Capture", 28, INK);
        title.setTypeface(null, Typeface.BOLD);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        fill(header, title);
        workspace = text(app == null ? "" : app.status(), 13, MUTED);
        fill(header, workspace);
        fill(root, header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page = column();
        page.setPadding(dp(20), dp(5), dp(20), dp(10));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        feedback = text("", 14, MUTED);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setPadding(dp(20), dp(3), dp(20), dp(5));
        feedback.setVisibility(View.GONE);
        fill(root, feedback);
        actions = column();
        actions.setPadding(dp(15), 0, dp(15), 0);
        fill(root, actions);
        nav = row();
        nav.setPadding(dp(15), dp(4), dp(15), dp(7));
        fill(root, nav);
        root.setFocusableInTouchMode(true);
        setContentView(root);

        if (next == Screen.CAPTURE) {
            composer(capture);
            if (!captureFeedback.isEmpty()) message(captureFeedback, false);
        } else if (next == Screen.EDITOR) {
            composer(editor);
        } else if (next == Screen.QUEUE) {
            queue();
        } else {
            account();
        }
        navigation();
        root.requestFocus();
        updateButtons();
    }

    private void navigation() {
        if (screen == Screen.EDITOR) {
            weighted(nav, button(editorParent == Screen.QUEUE ? "Back to Queue" : "Back to Capture",
                    false, () -> navigate(editorParent)));
            return;
        }
        weighted(nav, button("Capture", screen == Screen.CAPTURE, () -> navigate(Screen.CAPTURE)));
        weighted(nav, button("Queue", screen == Screen.QUEUE, () -> navigate(Screen.QUEUE)));
        weighted(nav, button("Account", screen == Screen.ACCOUNT, () -> navigate(Screen.ACCOUNT)));
    }

    private void navigate(Screen target) {
        if (busy || target == screen) return;
        if (screen == Screen.EDITOR && editor.changed()) {
            new AlertDialog.Builder(this).setMessage("Discard changes to this note?")
                    .setNegativeButton("Keep editing", null)
                    .setPositiveButton("Discard", (dialog, which) -> leave(target)).show();
            return;
        }
        leave(target);
    }

    private void leave(Screen target) {
        hideKeyboard();
        editor = null;
        if (target == Screen.QUEUE) queueSeed = new Random().nextLong();
        show(target);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null)
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void message(String message, boolean error) {
        feedback.setText(message);
        feedback.setTextColor(error ? ERROR : ACCENT);
        feedback.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void updateButtons() {
        boolean available = !busy && app.profile() != null;
        Draft draft = screen == Screen.EDITOR ? editor : capture;
        if (save != null) save.setEnabled(available && !draft.text.trim().isEmpty());
        if (saveEdit != null) saveEdit.setEnabled(available && !draft.text.trim().isEmpty());
    }

    private void refresh() {
        workspace.setText(app.status());
        updateButtons();
        if (screen == Screen.QUEUE && !busy) loadQueue();
        if (screen == Screen.ACCOUNT && page.getChildCount() == 1 && app.profile() != null) show(Screen.ACCOUNT);
    }

    private void composer(Draft draft) {
        boolean editing = draft.original != null;
        if (editing && draft.original.conflict)
            fill(page, text("This note has concurrent versions. Changes here apply to this version.", 14, MUTED));
        EditText input = new EditText(this);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setHint("Write a note…");
        input.setContentDescription("Note text");
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinHeight(dp(156));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackgroundColor(PANEL);
        input.setText(draft.text);
        fill(page, input);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                draft.text = s.toString();
                if (!editing) captureFeedback = "";
                if (!busy) message("", false);
                updateButtons();
            }
            public void afterTextChanged(Editable value) {}
        });

        LinearLayout attachments = column();
        margin(page, attachments, 10);
        Replica replica = app.profile() == null ? null : app.profile().replica();
        for (Notes.Image image : draft.existing) {
            imageRow(attachments, () -> replica.image(image), () -> {
                if (busy) return;
                draft.existing.remove(image);
                show(screen);
            });
        }
        for (Notes.NewImage image : draft.picked) {
            imageRow(attachments, image::open, () -> {
                if (busy) return;
                draft.picked.remove(image);
                show(screen);
            });
        }

        LinearLayout options = row();
        weighted(options, button("Attach image", false, () -> chooseImage(draft)));
        if (editing) {
            CheckBox exclude = new CheckBox(this);
            exclude.setText("Skip Queue");
            exclude.setChecked(draft.noQueue);
            exclude.setOnCheckedChangeListener((view, checked) -> draft.noQueue = checked);
            weighted(options, exclude);
        }
        fill(actions, options);

        LinearLayout submit = row();
        if (!editing) {
            if (latest != null) fill(page, button("Edit last saved note", false, this::openLatest));
            saveEdit = button("Save & edit", false, () -> save(draft, true));
            weighted(submit, saveEdit);
        } else {
            weighted(submit, button("Delete", false, () -> confirmDelete(draft)));
        }
        save = button("Save", true, () -> save(draft, false));
        weighted(submit, save);
        fill(actions, submit);
    }

    private void imageRow(LinearLayout box, Notes.OpenImage source, Runnable remove) {
        LinearLayout item = row();
        ImageView image = new ImageView(this);
        image.setContentDescription("Note attachment");
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        image.setBackgroundColor(PANEL);
        item.addView(image, new LinearLayout.LayoutParams(dp(84), dp(68)));
        TextView name = text("Image", 14, MUTED);
        name.setPadding(dp(12), 0, 0, 0);
        weighted(item, name);
        item.addView(button("Remove image", false, remove));
        margin(box, item, 4);
        app.preview(() -> ImagePicker.thumbnail(source), bitmap -> {
            if (isDestroyed()) return;
            image.setImageBitmap(bitmap);
            image.setContentDescription("Open attached image");
            image.setOnClickListener(view -> openImage(bitmap));
        }, error -> name.setText("Preview unavailable"));
    }

    private void openImage(Bitmap bitmap) {
        ImageView full = new ImageView(this);
        full.setContentDescription("Attached image");
        full.setAdjustViewBounds(true);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setImageBitmap(bitmap);
        new AlertDialog.Builder(this).setTitle("Image").setView(full)
                .setPositiveButton("Done", null).show();
    }

    private void chooseImage(Draft draft) {
        if (busy) return;
        picking = draft;
        try { picker.open(this); }
        catch (ActivityNotFoundException error) {
            picking = null;
            message("This device has no image picker.", true);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != ImagePicker.REQUEST) return;
        Draft target = picking;
        picking = null;
        if (result != RESULT_OK || data == null || target == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            target.picked.add(picker.selected(getApplicationContext().getContentResolver(), uri));
            show(screen);
        } catch (IllegalArgumentException | SecurityException error) {
            message(error.getMessage(), true);
        }
    }

    private void openLatest() {
        if (busy || latest == null || app.profile() == null) return;
        String key = latest.key;
        Replica replica = app.profile().replica();
        app.local(() -> {
            List<Notes.Note> versions = replica.notes();
            for (Notes.Note note : versions) if (note.key.equals(key)) return note;
            return null;
        }, note -> {
            if (isDestroyed() || screen != Screen.CAPTURE || busy || app.profile().replica() != replica) return;
            if (note != null) {
                latest = note;
                edit(note, Screen.CAPTURE);
            } else {
                latest = null;
                captureFeedback = "";
                show(Screen.CAPTURE);
                message("That note is no longer available.", false);
            }
        }, this::failure);
    }

    private void edit(Notes.Note note, Screen from) {
        hideKeyboard();
        editor = new Draft(note);
        editorParent = from;
        show(Screen.EDITOR);
    }

    private void save(Draft draft, boolean openEditor) {
        if (app.profile() == null || busy) return;
        busy = true;
        updateButtons();
        hideKeyboard();
        message("Saving…", false);
        Replica replica = app.profile().replica();
        String content = draft.text;
        boolean noQueue = draft.noQueue;
        List<Notes.Image> existing = new ArrayList<>(draft.existing);
        List<Notes.NewImage> picked = new ArrayList<>(draft.picked);
        setEnabled(page, false);
        setEnabled(actions, false);
        app.local(() -> {
            Notes.Note saved = replica.save(draft.original, content, noQueue, existing, picked);
            app.committed();
            return saved;
        }, saved -> {
            busy = false;
            if (isDestroyed()) return;
            if (draft.original == null) {
                latest = saved;
                capture = new Draft(null);
                captureFeedback = "Saved on this device.";
                if (openEditor) edit(saved, Screen.CAPTURE);
                else show(Screen.CAPTURE);
            } else {
                if (latest != null && latest.key.equals(saved.key)) latest = saved;
                editor = new Draft(saved);
                show(Screen.EDITOR);
                message("Saved on this device.", false);
            }
        }, this::failure);
    }

    private void confirmDelete(Draft draft) {
        if (busy) return;
        new AlertDialog.Builder(this).setMessage("Delete this note?")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dialog, which) -> {
                    if (busy || app.profile() == null) return;
                    busy = true;
                    updateButtons();
                    setEnabled(page, false);
                    setEnabled(actions, false);
                    message("Deleting…", false);
                    Replica replica = app.profile().replica();
                    app.local(() -> {
                        replica.delete(draft.original);
                        app.committed();
                        return null;
                    }, unused -> {
                        busy = false;
                        if (isDestroyed()) return;
                        if (latest != null && latest.key.equals(draft.original.key)) {
                            latest = null;
                            captureFeedback = "";
                        }
                        leave(editorParent);
                        message("Note deleted.", false);
                    }, this::failure);
                }).show();
    }

    private void queue() {
        queueCards = column();
        fill(page, queueCards);
        fill(actions, button("Reshuffle", false, () -> {
            queueSeed = new Random().nextLong();
            loadQueue();
        }));
        loadQueue();
    }

    private void loadQueue() {
        int request = ++queueRequest;
        LinearLayout target = queueCards;
        if (target == null) return;
        Profile profile = app.profile();
        if (profile == null) {
            if (target.getChildCount() == 0) fill(target, text("Opening Queue…", 16, MUTED));
            return;
        }
        long seed = queueSeed;
        Replica replica = profile.replica();
        app.local(() -> Notes.queue(replica.notes(), new Random(seed)), notes -> {
            if (isDestroyed() || screen != Screen.QUEUE || request != queueRequest) return;
            target.removeAllViews();
            if (notes.isEmpty()) fill(target, text("No notes in Queue yet.", 16, MUTED));
            for (Notes.Note note : notes) {
                String summary = note.text.trim();
                if (summary.length() > 220) summary = summary.substring(0, 220) + "…";
                LinearLayout card = column();
                card.setBackgroundColor(PANEL);
                card.setPadding(dp(14), dp(8), dp(14), dp(6));
                TextView preview = text(summary, 16, INK);
                preview.setMaxLines(5);
                preview.setEllipsize(TextUtils.TruncateAt.END);
                fill(card, preview);
                if (!note.images.isEmpty())
                    fill(card, text(note.images.size() == 1 ? "1 image" : note.images.size() + " images", 13, MUTED));
                fill(card, button(note.conflict ? "Open conflicting version" : "Open note", false,
                        () -> edit(note, Screen.QUEUE)));
                margin(target, card, 8);
            }
        }, this::failure);
    }

    private EditText field(String label, int inputType, String value) {
        TextView caption = text(label, 14, INK);
        margin(page, caption, 9);
        EditText input = new EditText(this);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setText(value);
        input.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        input.setTextColor(INK);
        input.setContentDescription(label);
        input.setSaveEnabled(false);
        fill(page, input);
        return input;
    }

    private void account() {
        Profile profile = app.profile();
        if (profile == null) {
            fill(page, text(app.status(), 16, MUTED));
            return;
        }
        Olymp.Session active = profile.account();
        if (active != null) {
            fill(page, text(active.name, 19, INK));
            fill(page, text(active.origin, 14, MUTED));
            EditText password = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
            fill(actions, button("Sign in again", true,
                    () -> authenticate(active.origin, active.name, password, false, false)));
            fill(actions, button("Sign out", false, () -> {
                if (busy) return;
                accountWork(() -> { profile.signOut(app.transport); return null; }, true);
            }));
        } else {
            EditText service = field("Olymp origin", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                    "http://127.0.0.1:8787");
            EditText name = field("Login name", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL, "");
            EditText password = field("Password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "");
            CheckBox include = new CheckBox(this);
            include.setText("Include notes from this local workspace");
            include.setChecked(true);
            margin(page, include, 9);
            fill(actions, button("Sign in", true, () -> authenticate(service.getText().toString(),
                    name.getText().toString(), password, false, include.isChecked())));
            fill(actions, button("Create account", false, () -> authenticate(service.getText().toString(),
                    name.getText().toString(), password, true, include.isChecked())));
        }
    }

    private void authenticate(String origin, String name, EditText field, boolean signup, boolean includeLocal) {
        if (busy) return;
        byte[] password = Json.bytes(field.getText().toString());
        field.getText().clear();
        Profile profile = app.profile();
        boolean switching = profile.account() == null;
        accountWork(() -> {
            try { profile.signIn(app.transport, origin, name, password, signup, includeLocal, "Gestalter Android"); }
            finally { Arrays.fill(password, (byte) 0); }
            return null;
        }, switching);
    }

    private void accountWork(GestalterApp.Work<Void> work, boolean switchWorkspace) {
        busy = true;
        hideKeyboard();
        setEnabled(page, false);
        setEnabled(actions, false);
        message("Working…", false);
        app.account(work, unused -> {
            busy = false;
            if (isDestroyed()) return;
            if (switchWorkspace) {
                capture = new Draft(null);
                captureFeedback = "";
                latest = null;
            }
            show(Screen.ACCOUNT);
        }, this::failure);
    }

    private void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
        }
    }

    private void failure(Exception error) {
        busy = false;
        if (isDestroyed()) return;
        setEnabled(page, true);
        setEnabled(actions, true);
        updateButtons();
        String detail;
        if (error instanceof Olymp.HttpFailure) {
            int status = ((Olymp.HttpFailure) error).status;
            detail = status == 429 ? "Too many sign-in attempts. Try again later."
                    : status == 409 ? "That login name already exists."
                    : status == 401 ? "Unable to unlock this Olymp account." : error.getMessage();
        } else if (error instanceof java.io.IOException) {
            detail = screen == Screen.ACCOUNT ? "Could not reach Olymp or save account settings."
                    : "Could not save to local storage or read the selected image.";
        } else {
            detail = error.getMessage();
        }
        message(detail == null ? "The action could not be completed." : detail, true);
    }

    @Override public void onBackPressed() {
        if (busy) return;
        if (screen == Screen.EDITOR) navigate(editorParent);
        else if (screen != Screen.CAPTURE) navigate(Screen.CAPTURE);
        else super.onBackPressed();
    }
}
