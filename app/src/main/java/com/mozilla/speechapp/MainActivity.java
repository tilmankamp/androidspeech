package com.mozilla.speechapp;

import android.Manifest;

import android.app.PendingIntent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.Intent;

import android.net.ConnectivityManager;

import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v7.app.AppCompatActivity;

import android.os.Bundle;
import android.os.AsyncTask;

import android.util.Log;

import android.view.View;

import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import com.mozilla.speechlibrary.ISpeechRecognitionListener;
import com.mozilla.speechlibrary.MozillaSpeechService;
import com.mozilla.speechlibrary.STTResult;
import com.mozilla.speechmodule.R;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import net.lingala.zip4j.core.ZipFile;


public class MainActivity extends AppCompatActivity implements ISpeechRecognitionListener, CompoundButton.OnCheckedChangeListener {
    private static String TAG = "com.mozilla.speechapp.MainActivity";
    private MozillaSpeechService mMozillaSpeechService;
    private GraphView mGraph;
    private long mDtstart;
    private LineGraphSeries<DataPoint> mSeries1;
    private EditText mPlain_text_input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mMozillaSpeechService = MozillaSpeechService.getInstance();
        initialize();
    }

    private void initialize() {

        Button buttonStart, buttonCancel;
        EditText txtProdutTag, txtLanguage;
        Switch switchTranscriptions = findViewById(R.id.switchTranscriptions);
        Switch switchSamples = findViewById(R.id.switchSamples);
        Switch useDeepSpeech = findViewById(R.id.useDeepSpeech);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO},
                    123);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    124);
        }

        buttonStart = findViewById(R.id.button_start);
        buttonCancel = findViewById(R.id.button_cancel);
        txtProdutTag = findViewById(R.id.txtProdutTag);
        txtLanguage = findViewById(R.id.txtLanguage);

        mPlain_text_input = findViewById(R.id.plain_text_input);
        buttonStart.setOnClickListener((View v) ->  {
            try {
                mMozillaSpeechService.addListener(this);
                mDtstart = System.currentTimeMillis();
                mSeries1.resetData(new DataPoint[0]);
                mMozillaSpeechService.setLanguage(txtLanguage.getText().toString());
                mMozillaSpeechService.setProductTag(txtProdutTag.getText().toString());
                mMozillaSpeechService.setModelPath(getExternalFilesDir("models").getAbsolutePath());
                if (mMozillaSpeechService.ensureModelInstalled()) {
                    mMozillaSpeechService.start(getApplicationContext());
                } else {
                    maybeDownloadOrExtractModel(getExternalFilesDir("models").getAbsolutePath(), mMozillaSpeechService.getLanguageDir());
                }
            } catch (Exception e) {
                Log.d(TAG, e.getLocalizedMessage());
                e.printStackTrace();
            }
        });

        buttonCancel.setOnClickListener((View v) ->  {
            try {
                mMozillaSpeechService.cancel();
            } catch (Exception e) {
                Log.d(TAG, e.getLocalizedMessage());
                e.printStackTrace();
            }
        });

        switchTranscriptions.setOnCheckedChangeListener(this);
        switchSamples.setOnCheckedChangeListener(this);
        useDeepSpeech.setOnCheckedChangeListener(this);
        switchTranscriptions.toggle();
        switchSamples.toggle();
        useDeepSpeech.toggle();

        mGraph = findViewById(R.id.graph);
        mSeries1 = new LineGraphSeries<>(new DataPoint[0]);
        mGraph.addSeries(mSeries1);
        mGraph.getViewport().setXAxisBoundsManual(true);
        mGraph.getViewport().setScalable(true);
        mGraph.getViewport().setScalableY(true);
        mGraph.getViewport().setScrollable(true); // enables horizontal scrolling
        mGraph.getViewport().setScrollableY(true); // enables vertical scrolling
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    public void onSpeechStatusChanged(MozillaSpeechService.SpeechState aState, Object aPayload){
        this.runOnUiThread(() -> {
            switch (aState) {
                case DECODING:
                    mPlain_text_input.append("Decoding... \n");
                    break;
                case MIC_ACTIVITY:
                    long mPointx = System.currentTimeMillis() - mDtstart;
                    mSeries1.appendData(new DataPoint(Math.round(mPointx) + 1, (double)aPayload * -1), true, 3000);
                    break;
                case STT_RESULT:
                    String message = String.format("Success: %s (%s)", ((STTResult)aPayload).mTranscription, ((STTResult)aPayload).mConfidence);
                    mPlain_text_input.append(message + "\n");
                    removeListener();
                    break;
                case START_LISTEN:
                    mPlain_text_input.append("Started to listen\n");
                    break;
                case NO_VOICE:
                    mPlain_text_input.append("No Voice detected\n");
                    removeListener();
                    break;
                case CANCELED:
                    mPlain_text_input.append("Canceled\n");
                    removeListener();
                    break;
                case ERROR:
                    mPlain_text_input.append("Error:" + aPayload + " \n");
                    removeListener();
                    break;
                default:
                    break;
            }
        });
    }

    public void removeListener() {
        mMozillaSpeechService.removeListener(this);
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.equals(findViewById(R.id.switchTranscriptions))) {
            mMozillaSpeechService.storeTranscriptions(isChecked);
        } else if (buttonView.equals(findViewById(R.id.switchSamples))) {
            mMozillaSpeechService.storeSamples(isChecked);
        } else if (buttonView.equals(findViewById(R.id.useDeepSpeech))) {
            mMozillaSpeechService.useDeepSpeech(isChecked);
        }
    }

    public class ModelDownloadListener {
        public void onShowMessage(String message) {}
        public void onProgress(int progress) {}
        public void onStart() {}
        public void onSuccess() {}
        public void onError(Exception ex) {}
        public void onCancelled() {}
        public void onEnd() {}
    }

    private class ModelDownloadTask extends AsyncTask<Void, Integer, Boolean> {
        public ModelDownloadTask(Context context, MozillaSpeechService mMozillaSpeechService, String aModelsPath, String aLang) {
            this.context = context;
            this.mMozillaSpeechService = mMozillaSpeechService;
            this.aModelsPath = aModelsPath;
            this.aLang = aLang;
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
        private NotificationCompat.Builder builder;
        private NotificationManagerCompat notificationManager;
        private int notificationId = 1;

        private File zipFile;

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
            ConnectivityManager connectivity = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity.isActiveNetworkMetered()) {
                showMessage("Download too big. Please switch to WIFI or create a wired connection first.");
                this.cancel(true);
                return;
            }
            if (showNotifications) {
                String CHANNEL_ID = "ModelDownload";
                Intent stopIntent = new Intent();
                stopIntent.setAction("com.mozilla.speechapp.stopModelDownload");
                PendingIntent stopPendingIntent = PendingIntent.getBroadcast(this.context, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                NotificationCompat.Action stopAction = new NotificationCompat.Action.Builder(0, "Stop download", stopPendingIntent).build();

                this.notificationManager = NotificationManagerCompat.from(this.context);
                this.builder = new NotificationCompat.Builder(this.context, CHANNEL_ID);
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

            this.zipFile = new File(this.aModelsPath + "/" + this.aLang + ".zip");
            HttpURLConnection urlConnection = null;
            InputStream in = null;
            FileOutputStream out = null;

            try {
                urlConnection = (HttpURLConnection) modelZipURL.openConnection();
                in = new BufferedInputStream(urlConnection.getInputStream());
                out = new FileOutputStream(zipFile);
                long totalSize = Long.parseLong(urlConnection.getHeaderField("content-length"));
                byte[] buffer = new byte[4096];
                int bytesRead;
                long bytesWritten = 0;
                int lastProgress = -1;
                while ((bytesRead = in.read(buffer)) > 0) {
                    out.write(buffer, 0, bytesRead);
                    bytesWritten += bytesRead;
                    int progress = (int)((bytesWritten * 100l) / totalSize);
                    if (lastProgress != progress) {
                        publishProgress(progress);
                        lastProgress = progress;
                    }
                }
            } catch (IOException ioEx) {
                this.exception = ioEx;
                return false;
            } finally {
                tryClose(in);
                tryClose(out);
            }
            try {
                ZipFile zf = new ZipFile(this.zipFile);
                zf.extractAll(this.aModelsPath);
            } catch (Exception zipEx) {
                this.exception = zipEx;
                return false;
            } finally {
                this.zipFile.delete();
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

    public void maybeDownloadOrExtractModel(String aModelsPath, String aLang) {
        Button buttonStart = findViewById(R.id.button_start), buttonCancel = findViewById(R.id.button_cancel);
        ModelDownloadTask download = new ModelDownloadTask(this.getApplicationContext(), mMozillaSpeechService, aModelsPath, aLang);
        download.setShowNotifications(true);
        download.setShowToasts(true);
        download.setListener(new ModelDownloadListener() {
            @Override
            public void onShowMessage(String message) {
                super.onShowMessage(message);
                mPlain_text_input.append(message + "\n");
            }

            @Override
            public void onStart() {
                super.onStart();
                buttonStart.setEnabled(false);
                buttonCancel.setEnabled(false);
            }

            @Override
            public void onEnd() {
                super.onEnd();
                buttonStart.setEnabled(true);
                buttonCancel.setEnabled(false);
            }
        });
        download.execute();
    }
}
