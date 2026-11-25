package thia.gestalter.android;

import android.app.job.*;
import android.content.*;
import android.net.*;

final class SyncSignals {
    private static final int RECONNECT = 11;
    private static final int PERIODIC = 12;
    private final JobScheduler scheduler;
    private final ComponentName service;

    SyncSignals(Context context, Runnable online) {
        scheduler = context.getSystemService(JobScheduler.class);
        service = new ComponentName(context, SyncJob.class);
        context.getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(
                new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network network) { online.run(); }
                });
    }

    void schedule(boolean pending) {
        if (scheduler.getPendingJob(PERIODIC) == null)
            scheduler.schedule(job(PERIODIC).setPeriodic(15 * 60 * 1000L).build());
        if (pending && scheduler.getPendingJob(RECONNECT) == null)
            scheduler.schedule(job(RECONNECT).setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build());
    }

    void cancel() {
        scheduler.cancel(RECONNECT);
        scheduler.cancel(PERIODIC);
    }

    private JobInfo.Builder job(int id) {
        return new JobInfo.Builder(id, service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true);
    }
}
