package thia.gestalter.android.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.text.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import java.util.function.Consumer;

/** Builders for the plain-Android widgets every screen uses. No app logic lives here. */
public final class Views {
    public final Context context;

    public Views(Context context) { this.context = context; }

    public int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public TextView text(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    public TextView heading(String value, int size) {
        TextView view = text(value, size, Theme.INK);
        view.setTypeface(null, Typeface.BOLD);
        if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true);
        return view;
    }

    public LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    /** A row whose children wrap onto further lines, for chips. */
    public Flow flow() { return new Flow(context, dp(6)); }

    public void fill(LinearLayout parent, View view) {
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2));
    }

    public void space(LinearLayout parent, int height) {
        parent.addView(new Space(context), new LinearLayout.LayoutParams(1, dp(height)));
    }

    public void margin(LinearLayout parent, View view, int height) {
        space(parent, height);
        fill(parent, view);
    }

    public void weighted(LinearLayout row, View view) {
        row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
    }

    public Button button(String title, boolean primary, Runnable action) {
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setText(title);
        button.setMinHeight(dp(48));
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setTextColor(primary ? Color.WHITE : Theme.INK);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? Theme.ACCENT : Theme.PANEL));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    public ImageButton icon(int drawable, String description, Runnable action) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setContentDescription(description);
        if (Build.VERSION.SDK_INT >= 26) button.setTooltipText(description);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    /** A labelled single-line input. */
    public EditText field(LinearLayout parent, String label, int inputType, String value) {
        TextView caption = text(label, 14, Theme.INK);
        margin(parent, caption, 9);
        EditText input = new EditText(context);
        input.setInputType(inputType);
        input.setSingleLine(true);
        input.setText(value);
        input.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        input.setTextColor(Theme.INK);
        input.setContentDescription(label);
        input.setSaveEnabled(false);
        fill(parent, input);
        return input;
    }

    /** An input styled as a writing surface. */
    public EditText editor(String hint, String value, boolean multiline, int minHeight) {
        EditText input = new EditText(context);
        input.setTextColor(Theme.INK);
        input.setHintTextColor(Theme.MUTED);
        input.setHint(hint);
        input.setContentDescription(hint);
        input.setGravity(Gravity.TOP | Gravity.START);
        int type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        if (multiline) type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        input.setInputType(type);
        input.setSingleLine(!multiline);
        if (minHeight > 0) input.setMinHeight(dp(minHeight));
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackgroundColor(Theme.PANEL);
        input.setSaveEnabled(false);
        input.setText(value);
        return input;
    }

    /** Calls {@code changed} with the new text after every edit. */
    public static void onText(EditText input, Consumer<String> changed) {
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { changed.accept(s.toString()); }
            public void afterTextChanged(Editable value) {}
        });
    }

    /** Calls {@code submit} when the keyboard's action key (or Enter) is pressed. */
    public static void onSubmit(EditText input, Runnable submit) {
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((view, action, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (action != EditorInfo.IME_ACTION_DONE && !enter) return false;
            submit.run();
            return true;
        });
    }

    /** A removable value, e.g. a tag. */
    public Button chip(String label, Runnable remove) {
        Button chip = button(label + "  ×", false, remove);
        chip.setContentDescription("Remove " + label);
        chip.setMinHeight(dp(40));
        return chip;
    }

    public static void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabled(group.getChildAt(i), enabled);
        }
    }
}
