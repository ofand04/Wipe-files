package uk.org.platitudes.wipe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int PICK_FILES = 42;
    private static final int MAX_ATTEMPTS = 100;

    private final List<Uri> selectedFiles = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final StringBuilder operationLog = new StringBuilder();

    private volatile boolean cancelRequested = false;
    private volatile boolean wipeRunning = false;

    private TextView selectedCount;
    private TextView selectedFilesView;
    private TextView statusView;
    private TextView stageView;

    private EditText editNameChanges;
    private EditText editContentPasses;

    private Button pickButton;
    private Button wipeButton;
    private Button cancelButton;

    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        setContentView(R.layout.activity_main);

        getWindow().setStatusBarColor(Color.rgb(8, 13, 12));
        getWindow().setNavigationBarColor(Color.rgb(8, 13, 12));

        selectedCount = findViewById(R.id.selectedCount);
        selectedFilesView = findViewById(R.id.selectedFiles);
        statusView = findViewById(R.id.status);
        stageView = findViewById(R.id.stage);

        progressBar = findViewById(R.id.progressBar);

        editNameChanges = findViewById(R.id.editNameChanges);
        editContentPasses = findViewById(R.id.editContentPasses);

        pickButton = findViewById(R.id.btnPick);
        wipeButton = findViewById(R.id.btnWipe);
        cancelButton = findViewById(R.id.btnCancel);

        pickButton.setOnClickListener(v -> pickFiles());
        wipeButton.setOnClickListener(v -> confirmWipe());
        cancelButton.setOnClickListener(v -> requestCancel());

        setRunningUi(false);
        refreshSelection();

        appendLog("WIPEFILES READY");
        appendLog("Select files using the Android file picker.");
        appendLog("Physical erasure cannot be guaranteed on flash storage.");
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);

        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        );

        try {
            startActivityForResult(intent, PICK_FILES);
        } catch (Exception e) {
            appendLog("ERROR: Could not open file picker: " + message(e));
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) {
            appendLog("File selection cancelled.");
            return;
        }

        selectedFiles.clear();

        int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                addPickedUri(data.getClipData().getItemAt(i).getUri(), flags);
            }
        } else if (data.getData() != null) {
            addPickedUri(data.getData(), flags);
        }

        refreshSelection();

        appendLog("Selected " + selectedFiles.size() + " file(s).");
    }

    private void addPickedUri(Uri uri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // Some providers do not support persistent permissions.
        }

        if (!selectedFiles.contains(uri)) {
            selectedFiles.add(uri);
        }
    }

    private void refreshSelection() {
        selectedCount.setText(
                String.format(
                        Locale.getDefault(),
                        "%02d FILE(S) SELECTED",
                        selectedFiles.size()
                )
        );

        if (selectedFiles.isEmpty()) {
            selectedFilesView.setText("No files selected.");
        } else {
            StringBuilder builder = new StringBuilder();

            for (int i = 0; i < selectedFiles.size(); i++) {
                Uri uri = selectedFiles.get(i);

                builder.append(i + 1)
                        .append(". ")
                        .append(displayName(uri));

                long size = fileSize(uri);

                if (size >= 0) {
                    builder.append("  ·  ").append(formatSize(size));
                }

                if (i + 1 < selectedFiles.size()) {
                    builder.append('\n');
                }
            }

            selectedFilesView.setText(builder.toString());
        }

        if (!wipeRunning) {
            stageView.setText("READY");
        }

        wipeButton.setEnabled(!selectedFiles.isEmpty() && !wipeRunning);
        wipeButton.setAlpha(wipeButton.isEnabled() ? 1f : 0.55f);
    }

    private void confirmWipe() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(
                    this,
                    "Select files first.",
                    Toast.LENGTH_SHORT
            ).show();

            appendLog("No files selected.");
            return;
        }

        int renameAttempts = readSetting(
                editNameChanges, 10, 1, MAX_ATTEMPTS
        );

        int passes = readSetting(
                editContentPasses, 3, 1, MAX_ATTEMPTS
        );

        new AlertDialog.Builder(this)
                .setTitle("Confirm destructive operation")
                .setMessage(
                        "Selected files: " + selectedFiles.size()
                                + "\nFilename changes: " + renameAttempts
                                + "\nOverwrite passes: " + passes
                                + "\n\nThis operation can permanently destroy data. "
                                + "Android storage providers may not support overwriting. "
                                + "Continue only if you have selected the correct files."
                )
                .setNegativeButton("Go back", (dialog, which) ->
                        appendLog("Operation not started.")
                )
                .setPositiveButton("Continue", (dialog, which) ->
                        startWipe(renameAttempts, passes)
                )
                .show();
    }

    private int readSetting(
            EditText field,
            int fallback,
            int min,
            int max
    ) {
        try {
            int value = Integer.parseInt(
                    field.getText().toString().trim()
            );

            return Math.max(min, Math.min(value, max));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void setRunningUi(boolean running) {
        wipeRunning = running;

        pickButton.setEnabled(!running);

        wipeButton.setEnabled(
                !running && !selectedFiles.isEmpty()
        );

        cancelButton.setEnabled(running);
        cancelButton.setAlpha(running ? 1f : 0.55f);
    }

    private void requestCancel() {
        if (!wipeRunning) {
            return;
        }

        cancelRequested = true;

        cancelButton.setEnabled(false);

        appendLog("Cancellation requested. Waiting for the next checkpoint.");

        stageView.setText("CANCELLATION REQUESTED");
    }

    private void startWipe(int renameAttempts, int passes) {
        final List<Uri> work = new ArrayList<>(selectedFiles);

        cancelRequested = false;

        operationLog.setLength(0);

        progressBar.setProgress(0);
        stageView.setText("STARTING");

        setRunningUi(true);

        appendLog("Operation started.");
        appendLog("Files: " + work.size());
        appendLog("Filename changes: " + renameAttempts);
        appendLog("Overwrite passes: " + passes);
        appendLog("WARNING: overwrite behavior depends on the storage provider.");

        Thread worker = new Thread(() -> {
            int completed = 0;
            int failed = 0;
            int cancelled = 0;

            for (int index = 0; index < work.size(); index++) {
                if (cancelRequested) {
                    cancelled++;
                    break;
                }

                Uri uri = work.get(index);
                String name = displayName(uri);

                int fileNumber = index + 1;

                logFromWorker(
                        "FILE " + fileNumber + " OF " + work.size()
                                + ": " + name
                );

                try {
                    checkCancel();

                    reportProgress(
                            fileNumber,
                            work.size(),
                            0,
                            "CHECKING ACCESS",
                            name
                    );

                    try (
                            ParcelFileDescriptor descriptor =
                                    getContentResolver()
                                            .openFileDescriptor(uri, "rw")
                    ) {
                        if (descriptor == null) {
                            throw new IllegalStateException(
                                    "Provider did not grant write access."
                            );
                        }
                    }

                    long size = fileSize(uri);

                    if (size < 0) {
                        size = measureSize(uri);
                    }

                    if (size <= 0) {
                        throw new IllegalStateException(
                                "File is empty or its size could not be determined."
                        );
                    }

                    for (int pass = 0; pass < passes; pass++) {
                        checkCancel();

                        overwritePass(
                                uri,
                                size,
                                pass,
                                passes,
                                fileNumber,
                                work.size(),
                                name
                        );
                    }

                    Uri currentUri = uri;
                    int renamed = 0;

                    for (int attempt = 0; attempt < renameAttempts; attempt++) {
                        checkCancel();

                        reportProgress(
                                fileNumber,
                                work.size(),
                                90,
                                "RENAMING",
                                name + " · attempt "
                                        + (attempt + 1)
                                        + " of " + renameAttempts
                        );

                        try {
                            Uri next = DocumentsContract.renameDocument(
                                    getContentResolver(),
                                    currentUri,
                                    "wipe-" + randomHex(12)
                            );

                            if (next == null) {
                                logFromWorker(
                                        "Provider did not perform further filename changes."
                                );
                                break;
                            }

                            currentUri = next;
                            renamed++;

                        } catch (Exception e) {
                            logFromWorker(
                                    "Filename change stopped: " + message(e)
                            );
                            break;
                        }
                    }

                    checkCancel();

                    reportProgress(
                            fileNumber,
                            work.size(),
                            96,
                            "DELETING",
                            name
                    );

                    boolean deleted = DocumentsContract.deleteDocument(
                            getContentResolver(),
                            currentUri
                    );

                    if (!deleted) {
                        throw new IllegalStateException(
                                "Provider did not confirm deletion. "
                                        + "The file may have been modified but not deleted."
                        );
                    }

                    completed++;

                    logFromWorker(
                            "DELETED: " + name
                                    + " | Filename changes: " + renamed
                                    + "/" + renameAttempts
                                    + " | Overwrite passes attempted: " + passes
                    );

                } catch (CancelledException e) {
                    cancelled++;

                    logFromWorker(
                            "CANCELLED: " + name
                                    + ". The file may have been partly modified."
                    );

                    break;

                } catch (Exception e) {
                    failed++;

                    logFromWorker(
                            "FAILED: " + name + " | " + message(e)
                    );
                }

                final int overall = (int) (
                        100.0 * (index + 1) / work.size()
                );

                runOnUiThread(() -> progressBar.setProgress(overall));
            }

            final int completedFinal = completed;
            final int failedFinal = failed;
            final int cancelledFinal = cancelled;
            final boolean stopped = cancelRequested || cancelled > 0;

            logFromWorker(
                    "FINISHED. Deleted: " + completedFinal
                            + " | Failed: " + failedFinal
                            + " | Cancelled: " + cancelledFinal
            );

            runOnUiThread(() -> {
                setRunningUi(false);

                stageView.setText(stopped ? "CANCELLED" : "FINISHED");

                if (!stopped && failedFinal == 0) {
                    progressBar