package thia.gestalter.android.shell;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import thia.gestalter.android.navigation.*;
import thia.gestalter.android.screens.account.AccountScreen;
import thia.gestalter.android.screens.capture.CaptureScreen;
import thia.gestalter.android.screens.lists.ListsScreen;
import thia.gestalter.android.screens.note.NoteScreen;
import thia.gestalter.android.screens.notes.NotesScreen;
import thia.gestalter.android.screens.queue.QueueScreen;
import thia.gestalter.android.ui.*;
import thia.gestalter.android.workspace.*;

/**
 * The app frame: a header with the workspace status, the current screen's page and actions, and
 * the tab bar. Each tab keeps its screen; note editors are pushed on top of the current tab.
 */
public final class MainActivity extends Activity implements ScreenHost {
    private static final Route.Kind[] TABS = { Route.Kind.CAPTURE, Route.Kind.NOTES, Route.Kind.LISTS, Route.Kind.QUEUE,
            Route.Kind.ACCOUNT };
    private static final String[] TAB_TITLES = { "Capture", "Notes", "Lists", "Queue", "Account" };

    private final Map<Route.Kind, Screen> tabs = new EnumMap<>(Route.Kind.class);
    private final List<Screen> pushed = new ArrayList<>();
    private final Runnable observer = this::changed;
    private Workspace workspace;
    private Views views;
    private Route.Kind tab = Route.Kind.CAPTURE;
    private TextView status;
    private TextView feedback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        workspace = GestalterApp.workspace(this);
        views = new Views(this);
        getWindow().setStatusBarColor(Theme.PAPER);
        getWindow().setNavigationBarColor(Theme.PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (state != null) restore(state);
        render();
    }

    @Override protected void onStart() {
        super.onStart();
        workspace.observe(observer);
        workspace.foreground(true);
        changed();
    }

    @Override protected void onStop() {
        workspace.removeObserver(observer);
        workspace.foreground(false);
        super.onStop();
    }

    // --- Screens -----------------------------------------------------------------------------

    private Screen create(Route route, Bundle state) {
        switch (route.kind) {
            case CAPTURE: return new CaptureScreen(this, state);
            case NOTES: return new NotesScreen(this, state);
            case LISTS: return new ListsScreen(this, state);
            case QUEUE: return new QueueScreen(this, state);
            case ACCOUNT: return new AccountScreen(this, state);
            case NOTE: return new NoteScreen(this, route, state);
            default: throw new IllegalArgumentException(route.kind.name());
        }
    }

    private Screen tabScreen(Route.Kind kind) {
        Screen screen = tabs.get(kind);
        if (screen == null) {
            screen = create(Route.tab(kind), null);
            tabs.put(kind, screen);
        }
        return screen;
    }

    private Screen current() {
        return pushed.isEmpty() ? tabScreen(tab) : pushed.get(pushed.size() - 1);
    }

    @Override public void go(Route route) {
        Screen from = current();
        if (from.busy()) return;
        hideKeyboard();
        if (!route.isTab()) {
            pushed.add(create(route, null));
            render();
            return;
        }
        if (route.kind == tab && pushed.isEmpty()) return;
        leavePushed(pushed.size() - 1, () -> {
            boolean switching = route.kind != tab;
            pushed.clear();
            tab = route.kind;
            if (switching) tabScreen(tab).entered();
            render();
        });
    }

    /** Lets every pushed screen, top first, confirm that it may be left. */
    private void leavePushed(int index, Runnable proceed) {
        if (index < 0) proceed.run();
        else pushed.get(index).requestLeave(() -> leavePushed(index - 1, proceed));
    }

    @Override public void close() {
        if (pushed.isEmpty()) return;
        hideKeyboard();
        pushed.remove(pushed.size() - 1);
        render();
    }

    @Override public void onBackPressed() {
        Screen screen = current();
        if (screen.busy()) return;
        if (!pushed.isEmpty()) screen.requestLeave(this::close);
        else if (tab != Route.Kind.CAPTURE) go(Route.tab(Route.Kind.CAPTURE));
        else super.onBackPressed();
    }

    private void changed() {
        if (status != null) status.setText(workspace.status());
        current().changed();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        current().result(request, result, data);
    }

    // --- Frame -------------------------------------------------------------------------------

    @Override public void render() {
        if (isDestroyed()) return;
        Screen screen = current();
        LinearLayout root = views.column();
        root.setBackgroundColor(Theme.PAPER);
        LinearLayout header = views.column();
        header.setPadding(views.dp(20), views.dp(12), views.dp(20), views.dp(9));
        views.fill(header, views.heading(screen.title(), 28));
        status = views.text(workspace.status(), 13, Theme.MUTED);
        views.fill(header, status);
        views.fill(root, header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = views.column();
        page.setPadding(views.dp(20), views.dp(5), views.dp(20), views.dp(10));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        feedback = views.text("", 14, Theme.MUTED);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setPadding(views.dp(20), views.dp(3), views.dp(20), views.dp(5));
        feedback.setVisibility(View.GONE);
        views.fill(root, feedback);
        LinearLayout actions = views.column();
        actions.setPadding(views.dp(15), 0, views.dp(15), 0);
        views.fill(root, actions);
        LinearLayout nav = views.row();
        nav.setPadding(views.dp(15), views.dp(4), views.dp(15), views.dp(7));
        navigation(nav);
        views.fill(root, nav);
        root.setFocusableInTouchMode(true);
        setContentView(root);

        screen.build(page, actions);
        root.requestFocus();
    }

    private void navigation(LinearLayout nav) {
        if (!pushed.isEmpty()) {
            String parent = TAB_TITLES[Arrays.asList(TABS).indexOf(tab)];
            views.weighted(nav, views.button(pushed.size() > 1 ? "Back" : "Back to " + parent, false,
                    this::onBackPressed));
            return;
        }
        for (int i = 0; i < TABS.length; i++) {
            Route.Kind kind = TABS[i];
            views.weighted(nav, views.button(TAB_TITLES[i], kind == tab, () -> go(Route.tab(kind))));
        }
    }

    @Override public void message(String text, boolean error) {
        if (feedback == null || isDestroyed()) return;
        feedback.setText(text);
        feedback.setTextColor(error ? Theme.ERROR : Theme.ACCENT);
        feedback.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override public void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus != null)
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    @Override public Activity activity() { return this; }

    @Override public Views views() { return views; }

    @Override public Workspace workspace() { return workspace; }

    // --- State -------------------------------------------------------------------------------

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("tab", tab.name());
        for (Map.Entry<Route.Kind, Screen> entry : tabs.entrySet()) {
            Bundle screen = new Bundle();
            entry.getValue().save(screen);
            state.putBundle("tab:" + entry.getKey().name(), screen);
        }
        ArrayList<Bundle> stack = new ArrayList<>();
        for (Screen screen : pushed) {
            Bundle saved = new Bundle();
            saved.putBundle("route", screen.route().bundle());
            Bundle data = new Bundle();
            screen.save(data);
            saved.putBundle("state", data);
            stack.add(saved);
        }
        state.putParcelableArrayList("pushed", stack);
        super.onSaveInstanceState(state);
    }

    private void restore(Bundle state) {
        tab = Route.Kind.valueOf(state.getString("tab", Route.Kind.CAPTURE.name()));
        for (Route.Kind kind : TABS) {
            Bundle screen = state.getBundle("tab:" + kind.name());
            if (screen != null) tabs.put(kind, create(Route.tab(kind), screen));
        }
        ArrayList<Bundle> stack = state.getParcelableArrayList("pushed");
        if (stack == null) return;
        for (Bundle saved : stack) pushed.add(create(Route.from(saved.getBundle("route")), saved.getBundle("state")));
    }
}
