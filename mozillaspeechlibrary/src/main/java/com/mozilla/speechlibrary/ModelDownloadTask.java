package com.mozilla.speechlibrary;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.AsyncTask;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.Builder;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import android.widget.Toast;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

public class ModelDownloadTask extends AsyncTask<Void, Integer, Boolean> {
    private static final String TAG = "com.mozilla.speechlibrary.ModelDownloadTask";

    public ModelDownloadTask(Context context, MozillaSpeechService mMozillaSpeechService, String aModelsPath, String aLang) {
        this.context = context;
        this.mMozillaSpeechService = mMozillaSpeechService;
        this.aModelsPath = aModelsPath;
        this.aLang = aLang;
    }

    private class ByteCountStream extends FilterInputStream {
        private volatile long totalBytesRead = 0l;

        public ByteCountStream(InputStream in) {
            super(in);
        }

        @Override
        public void mark(int readlimit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reset() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                incrementCounter(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return (int)incrementCounter(super.read(b, off, len));
        }

        @Override
        public long skip(long n) throws IOException {
            return incrementCounter(super.skip(n));
        }

        private long incrementCounter(long bytesRead) {
            if (bytesRead > 0) {
                this.totalBytesRead += bytesRead;
                onBytesRead(this.totalBytesRead);
            }
            return bytesRead;
        }

        public void onBytesRead(long totalBytesRead) {}
    }

    private Context context;
    private MozillaSpeechService mMozillaSpeechService;
    private String aModelsPath;
    private String aLang;

    private ModelDownloadListener listener = new ModelDownloadListener();
    private Boolean showNotifications = false;
    private Boolean showToasts = false;

    private Exception exception;
    private ModelDownloadTaskReceiver receiver = new ModelDownloadTaskReceiver(this);
    private Builder builder;
    private NotificationManagerCompat notificationManager;
    private int notificationId = 1;

    private class ModelDownloadTaskReceiver extends BroadcastReceiver {
        public ModelDownloadTaskReceiver(ModelDownloadTask task) {
            this.task = task;
        }

        private ModelDownloadTask task;

        @Override
        public void onReceive(Context context, Intent intent) {
            this.task.cancel(true);
        }
    }

    public void setListener(ModelDownloadListener listener) {
        if (listener == null) {
            this.listener = new ModelDownloadListener();
        } else {
            this.listener = listener;
        }
    }

    public ModelDownloadListener listener() {
        return this.listener;
    }

    public void setShowNotifications(Boolean showNotifications) {
        this.showNotifications = showNotifications;
    }

    public Boolean showNotifications() {
        return this.showNotifications;
    }

    public void setShowToasts(Boolean showToasts) {
        this.showToasts = showToasts;
    }

    public Boolean showToasts() {
        return this.showToasts;
    }

    private void showMessage(String message) {
        if (showToasts) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        }
        listener.onShowMessage(message);
    }

    private void tryClose(Closeable closable) {
        try {
            if (closable != null) {
                closable.close();
            }
        } catch(Exception ex) {}
    }

    @Override
    protected void onPreExecute() {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivity.isActiveNetworkMetered()) {
            showMessage("Download too big. Please switch to WIFI or create a wired connection first.");
            this.cancel(true);
            return;
        }

        if (showNotifications) {
            Intent stopIntent = new Intent();
            stopIntent.setAction("com.mozilla.speechapp.stopModelDownload");
            PendingIntent stopPendingIntent = PendingIntent.getBroadcast(this.context, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            NotificationCompat.Action stopAction = new NotificationCompat.Action.Builder(0, "Stop download", stopPendingIntent).build();

            this.notificationManager = NotificationManagerCompat.from(this.context);
            this.builder = new Builder(this.context);
            this.builder
                .setContentTitle("Retrieving " + aLang + " data")
                .setContentText("Download in progress")
                .setSmallIcon(R.drawable.ic_download)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .addAction(stopAction);

            this.context.registerReceiver(this.receiver, new IntentFilter(stopIntent.getAction()));
        }
        listener.onStart();
    }

    @Override
    protected Boolean doInBackground(Void... voids) {
        URL modelZipURL;
        try {
            modelZipURL = new URL(this.mMozillaSpeechService.getModelDownloadURL());
        } catch (MalformedURLException ex) {
            showMessage("Wrong URL");
            return false;
        }

        HttpURLConnection urlConnection = null;
        InputStream in = null;

        try {
            urlConnection = (HttpURLConnection) modelZipURL.openConnection();
            final long totalSize = Long.parseLong(urlConnection.getHeaderField("content-length"));
            ByteCountStream bcs = new ByteCountStream(urlConnection.getInputStream()) {
                private int lastProgress = -1;

                @Override
                public void onBytesRead(long totalBytesRead) {
                    int progress = (int)(totalBytesRead * 100l / totalSize);
                    if (progress != this.lastProgress) {
                        publishProgress(progress);
                    }
                    this.lastProgress = progress;
                }
            };
            in = new BufferedInputStream(bcs);

            File targetDir = new File(aModelsPath).getAbsoluteFile();
            ArchiveInputStream ais = new ZipArchiveInputStream(in);
            ArchiveEntry tarEntry;
            while ((tarEntry = ais.getNextEntry()) != null) {
                Log.d(TAG, "Target: " + tarEntry);
                if (!ais.canReadEntryData(tarEntry)) {
                    continue;
                }
                File targetFile = new File(targetDir, tarEntry.toString()).getAbsoluteFile();
                if (targetFile.getPath().length() < targetDir.getPath().length()) {
                    throw new IOException("Path outside target directory: " + targetFile);
                }
                if (tarEntry.isDirectory()) {
                    if (!targetFile.isDirectory() && !targetFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + targetFile);
                    }
                } else {
                    File parentDirectory = targetFile.getParentFile();
                    if (!parentDirectory.isDirectory() && !parentDirectory.mkdirs()) {
                        throw new IOException("Failed to create directory " + parentDirectory);
                    }
                    try (FileOutputStream out = new FileOutputStream(targetFile.getPath())) {
                        IOUtils.copy(ais, out);
                    }
                }
            }
        } catch (IOException ioEx) {
            this.exception = ioEx;
            return false;
        } finally {
            tryClose(in);
        }
        return true;
    }

    @Override
    protected void onProgressUpdate(Integer... progress) {
        if (showNotifications) {
            builder.setProgress(100, progress[0], false);
            notificationManager.notify(notificationId, builder.build());
        }
        listener.onProgress(progress[0]);
    }

    @Override
    protected void onCancelled() {
        if (showNotifications) {
            this.notificationManager.cancel(this.notificationId);
        }
        showMessage("Download cancelled");
        listener.onCancelled();
        listener.onEnd();
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (this.exception != null) {
            showMessage("Download failed");
            this.exception.printStackTrace();
            listener.onError(this.exception);
        } else {
            showMessage("Download complete");
            listener.onSuccess();
        }
        if (showNotifications) {
            this.notificationManager.cancel(this.notificationId);
        }
        listener.onEnd();
    }
}
