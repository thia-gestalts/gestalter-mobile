package thia.gestalter.android;

import android.app.Application;
import android.os.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import thia.gestalter.core.*;

public final class GestalterApp extends Application {
    private final ExecutorService local = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService previews = Executors.newFixedThreadPool(2);
    final Olymp.Transport transport = new AndroidTransport();
    final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private final List<Consumer<Boolean>> waiters = new ArrayList<>();
    private final CompletableFuture<Profile> initialized = new CompletableFuture<>();
    private volatile Profile profile;
    private volatile Exception openingError;
    private volatile boolean foreground;
    private boolean syncing;
    private boolean syncAgain;
    private SyncSignals signals;

    @Override public void onCreate() {
        super.onCreate();
        signals = new SyncSignals(this, this::sync);
        local.execute(() -> {
            try {
                Path dir = getNoBackupFilesDir().toPath();
                DurableFiles disk = new DurableFiles(AndroidFiles::syncDirectory);
                profile = new Profile(new AndroidFiles.Vault(disk, dir.resolve("profile.enc")), disk,
                        dir.resolve("replicas"));
                schedule();
                initialized.complete(profile);
            } catch (Exception error) {
                openingError = error;
                initialized.completeExceptionally(error);
            }
            notifyUi();
            sync();
        });
        network.scheduleWithFixedDelay(() -> { if (foreground) sync(); }, 15, 15, TimeUnit.SECONDS);
    }

    Profile profile() { return profile; }

    String status() {
        if (openingError != null) return "Could not open local storage";
        Profile current = profile;
        return current == null ? "Opening workspace…" : current.replica().status();
    }

    void observe(Runnable listener) { listeners.add(listener); }
    void removeObserver(Runnable listener) { listeners.remove(listener); }

    void foreground(boolean active) {
        foreground = active;
        if (active) sync();
    }

    void notifyUi() {
        main.post(() -> {
            for (Runnable listener : new ArrayList<>(listeners)) listener.run();
        });
    }

    private void schedule() {
        if (profile.auth() != Profile.Auth.ACTIVE) signals.cancel();
        else signals.schedule(profile.replica().pending());
    }

    void committed() {
        schedule();
        notifyUi();
        sync();
    }

    void sync() { requestSync(null); }

    synchronized void requestSync(Consumer<Boolean> done) {
        if (done != null) waiters.add(done);
        if (syncing) {
            syncAgain = true;
            return;
        }
        syncing = true;
        network.execute(this::runSync);
    }

    private void runSync() {
        boolean retry = false;
        try {
            Profile current = initialized.get();
            // A rejected token stays rejected until the user signs in again.
            if (current.auth() == Profile.Auth.ACTIVE) {
                current.replica().sync(transport);
                retry = current.replica().pending();
            }
        } catch (ExecutionException error) {
            retry = false;
        } catch (Olymp.HttpFailure error) {
            retry = error.status != 401 && error.status != 403 && error.status != 413;
            if (error.status == 401) main.post(this::schedule);
        } catch (Exception error) {
            retry = true;
        } finally {
            notifyUi();
            List<Consumer<Boolean>> completed;
            synchronized (this) {
                completed = new ArrayList<>(waiters);
                waiters.clear();
                syncing = false;
                if (syncAgain) {
                    syncAgain = false;
                    sync();
                }
            }
            boolean reschedule = retry;
            main.post(() -> completed.forEach(done -> done.accept(reschedule)));
        }
    }

    interface Work<T> { T run() throws Exception; }

    <T> void local(Work<T> work, Consumer<T> done, Consumer<Exception> failed) {
        submit(local, work, done, failed);
    }

    <T> void preview(Work<T> work, Consumer<T> done, Consumer<Exception> failed) {
        submit(previews, work, done, failed);
    }

    void account(Work<Void> work, Consumer<Void> done, Consumer<Exception> failed) {
        submit(network, () -> {
            initialized.get();
            work.run();
            committed();
            return null;
        }, done, failed);
    }

    private <T> void submit(Executor executor, Work<T> work, Consumer<T> done, Consumer<Exception> failed) {
        executor.execute(() -> {
            try {
                T value = work.run();
                main.post(() -> done.accept(value));
            } catch (Exception error) {
                main.post(() -> failed.accept(error));
            }
        });
    }
}
