package thia.gestalter.android.workspace;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import thia.gestalter.android.platform.*;
import thia.gestalter.core.*;

/**
 * The learner base on this device: opens the profile, serializes local writes, runs sync and tells
 * observers when anything changed. Every callback runs on the main thread.
 */
public final class Workspace {
    private final ExecutorService local = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService previews = Executors.newFixedThreadPool(2);
    private final Olymp.Transport transport = new AndroidTransport();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private final List<Consumer<Boolean>> waiters = new ArrayList<>();
    private final CompletableFuture<Profile> initialized = new CompletableFuture<>();
    private final SyncSignals signals;
    private volatile Profile profile;
    private volatile Exception openingError;
    private volatile boolean foreground;
    private boolean syncing;
    private boolean syncAgain;

    Workspace(Context context) {
        signals = new SyncSignals(context, this::sync);
        Path dir = context.getNoBackupFilesDir().toPath();
        local.execute(() -> {
            try {
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

    /** Null until local storage is open. */
    public Profile profile() { return profile; }

    public boolean ready() { return profile != null; }

    /** The Olymp origin this build syncs with. */
    public String origin() { return BuildConfig.OLYMP_ORIGIN; }

    public String status() {
        if (openingError != null) return "Could not open local storage";
        Profile current = profile;
        return current == null ? "Opening workspace…" : current.replica().status();
    }

    public void observe(Runnable listener) { listeners.add(listener); }
    public void removeObserver(Runnable listener) { listeners.remove(listener); }

    public void foreground(boolean active) {
        foreground = active;
        if (active) sync();
    }

    private void notifyUi() {
        main.post(() -> {
            for (Runnable listener : new ArrayList<>(listeners)) listener.run();
        });
    }

    private void schedule() {
        if (profile.auth() != Profile.Auth.ACTIVE) signals.cancel();
        else signals.schedule(profile.replica().pending());
    }

    private void committed() {
        schedule();
        notifyUi();
        sync();
    }

    private void sync() { requestSync(null); }

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

    // --- Notes -------------------------------------------------------------------------------

    private interface Work<T> { T run() throws Exception; }

    private interface ReplicaWork<T> { T run(Replica replica) throws Exception; }

    /** Reads from the current replica on the local thread. */
    private <T> void read(ReplicaWork<T> work, Consumer<T> done, Consumer<Exception> failed) {
        submit(local, () -> work.run(initialized.get().replica()), done, failed);
    }

    /** Writes to the current replica on the local thread, then schedules sync. */
    private <T> void write(ReplicaWork<T> work, Consumer<T> done, Consumer<Exception> failed) {
        submit(local, () -> {
            T value = work.run(initialized.get().replica());
            committed();
            return value;
        }, done, failed);
    }

    public void notes(Consumer<List<Notes.Note>> done, Consumer<Exception> failed) {
        read(Replica::notes, done, failed);
    }

    public void linkTargets(Consumer<List<Links.Target>> done, Consumer<Exception> failed) {
        read(Replica::linkTargets, done, failed);
    }

    public void queue(Collection<String> keep, Set<String> avoid, Consumer<List<Notes.Note>> done,
            Consumer<Exception> failed) {
        read(replica -> Notes.queue(replica.notes(), new Random(), keep, avoid), done, failed);
    }

    public void capture(String text, List<Notes.NewImage> images, Consumer<Notes.Note> done,
            Consumer<Exception> failed) {
        List<Notes.NewImage> picked = new ArrayList<>(images);
        write(replica -> replica.capture(text, picked), done, failed);
    }

    public void save(Notes.Note prior, Notes.Fields fields, Consumer<Notes.Note> done, Consumer<Exception> failed) {
        Notes.Fields snapshot = fields.copy();
        write(replica -> replica.save(prior, snapshot), done, failed);
    }

    public void delete(Notes.Note note, Runnable done, Consumer<Exception> failed) {
        write(replica -> { replica.delete(note); return null; }, unused -> done.run(), failed);
    }

    public void excludeFromQueue(Notes.Note note, Runnable done, Consumer<Exception> failed) {
        write(replica -> replica.excludeFromQueue(note), unused -> done.run(), failed);
    }

    /** The bytes of a stored attachment, decrypted on demand. */
    public Notes.OpenImage image(Notes.Image image) {
        Profile current = profile;
        if (current == null) throw new IllegalStateException("Local storage is not open.");
        Replica replica = current.replica();
        return () -> replica.image(image);
    }

    public void thumbnail(Notes.OpenImage source, Consumer<Bitmap> done, Consumer<Exception> failed) {
        submit(previews, () -> ImagePicker.thumbnail(source), done, failed);
    }

    // --- Account -----------------------------------------------------------------------------

    public void signIn(String origin, String name, byte[] password, boolean signup, boolean includeLocal,
            Runnable done, Consumer<Exception> failed) {
        account(() -> {
            try {
                initialized.get().signIn(transport, origin, name, password, signup, includeLocal, "Gestalter Android");
            } finally {
                Arrays.fill(password, (byte) 0);
            }
        }, done, failed);
    }

    public void signOut(Runnable done, Consumer<Exception> failed) {
        account(() -> initialized.get().signOut(transport), done, failed);
    }

    private interface AccountWork { void run() throws Exception; }

    private void account(AccountWork work, Runnable done, Consumer<Exception> failed) {
        submit(network, () -> {
            initialized.get();
            work.run();
            committed();
            return null;
        }, unused -> done.run(), failed);
    }

    /** A sentence for the person, for any failure of the operations above. */
    public static String describe(Exception error, boolean account) {
        if (error instanceof ExecutionException && error.getCause() instanceof Exception)
            return "Could not open local storage.";
        String detail;
        if (error instanceof Olymp.HttpFailure) {
            int status = ((Olymp.HttpFailure) error).status;
            detail = status == 429 ? "Too many sign-in attempts. Try again later."
                    : status == 409 ? "That login name already exists."
                    : status == 401 ? "Unable to unlock this Olymp account." : error.getMessage();
        } else if (error instanceof java.io.IOException) {
            detail = account ? "Could not reach Olymp or save account settings."
                    : "Could not save to local storage or read the selected image.";
        } else {
            detail = error.getMessage();
        }
        return detail == null ? "The action could not be completed." : detail;
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
