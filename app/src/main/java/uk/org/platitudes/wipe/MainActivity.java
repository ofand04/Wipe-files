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
    private static final int MAX_PASSES = 100;
    private static final int MAX_RENAME_ATTEMPTS = 100;

    private final List<Uri> selectedFiles = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private final StringBuilder operationLog = new StringBuilder();

    private volatile boolean cancelRequested = false;
    private volatile boolean wipeRunning = false;

    private TextView selectedCount, selectedFilesView, statusView, stageView;
    private EditText editNameChanges, editContentPasses;
    private Button pickButton, wipeButton, cancelButton;
    private ProgressBar progressBar;

    @Override
    public void onCreate(Bundle state) {
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
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(intent, PICK_FILES);
        } catch (Exception e) {
            appendLog("Could not open Android file picker: " + safeMessage(e));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) {
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
            // Some document providers do not offer persistable permissions.
        }
        if (!selectedFiles.contains(uri)) {
            selectedFiles.add(uri);
        }
    }

    private void refreshSelection() {
        if (selectedCount == null) return;

        selectedCount.setText(String.format(Locale.getDefault(),
                "%02d FILE(S) SELECTED", selectedFiles.size()));

        if (selectedFiles.isEmpty()) {
            selectedFilesView.setText("No files selected.");
        } else {
            StringBuilder list = new StringBuilder();
            for (int i = 0; i < selectedFiles.size(); i++) {
                Uri uri = selectedFiles.get(i);
                list.append(i + 1).append(". ").append(displayName(uri));
                long size = fileSize(uri);
                if (size >= 0) list.append(" · ").append(formatSize(size));
                if (i + 1 < selectedFiles.size()) list.append('\n');
            }
            selectedFilesView.setText(list.toString());
        }

        if (!wipeRunning) {
            stageView.setText("READY");
            if (operationLog.length() == 0) {
                statusView.setText("Select files, review settings, then confirm.");
            }
        }
        wipeButton.setEnabled(!selectedFiles.isEmpty() && !wipeRunning);
        wipeButton.setAlpha(wipeButton.isEnabled() ? 1f : 0.55f);
    }

    private void confirmWipe() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Select files first", Toast.LENGTH_SHORT).show();
            return;
        }

        int renameAttempts = readSetting(editNameChanges, 10, 1, MAX_RENAME_ATTEMPTS);
        int passes = readSetting(editContentPasses, 3, 1, MAX_PASSES);

        new AlertDialog.Builder(this)
                .setTitle("Confirm file operation")
                .setMessage("Files: " + selectedFiles.size()
                        + "\nOverwrite passes: " + passes
                        + "\nFilename-change attempts: " + renameAttempts
                        + "\n\nThis operation is destructive. Continue only with files you can afford to lose.")
                .setNegativeButton("Go back", null)
                .setPositiveButton("Continue", (dialog, which) ->
                        startWipe(renameAttempts, passes))
                .show();
    }

    private int readSetting(EditText edit, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(edit.getText().toString().trim());
            return Math.max(min, Math.min(value, max));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void setRunningUi(boolean running) {
        wipeRunning = running;
        pickButton.setEnabled(!running);
        wipeButton.setEnabled(!running && !selectedFiles.isEmpty());
        cancelButton.setEnabled(running);
        cancelButton.setAlpha(running ? 1f : 0.55f);
    }

    private void requestCancel() {
        if (wipeRunning) {
            cancelRequested = true;
            cancelButton.setEnabled(false);
            stageView.setText("CANCELLATION REQUESTED");
            appendLog("Cancellation requested; waiting for the next checkpoint. Any changes already made cannot be undone.");
        }
    }

    private void startWipe(int renameAttempts, int passes) {
        final List<Uri> work = new ArrayList<>(selectedFiles);
        cancelRequested = false;
        operationLog.setLength(0);
        progressBar.setProgress(0);
        setRunningUi(true);
        stageView.setText("PREPARING");
        appendLog("Starting operation for " + work.size() + " file(s).");

        new Thread(() -> {
            int deleted = 0;
            int failed = 0;
            int cancelled = 0;

            for (int index = 0; index < work.size(); index++) {
                if (cancelRequested) break;

                final int fileNumber = index + 1;
                Uri original = work.get(index);
                String name = displayName(original);

                try {
                    logFromWorker("FILE " + fileNumber + "/" + work.size() + ": " + name);
                    update(fileNumber, work.size(), 0, "CHECKING ACCESS",
                            "Checking write access for " + name);

                    // Check permission before modifying anything.
                    try (ParcelFileDescriptor descriptor =
                                 getContentResolver().openFileDescriptor(original, "rw")) {
                        if (descriptor == null) {
                            throw new IllegalStateException(
                                    "Write access was not granted. Select the file again.");
                        }
                    }

                    for (int pass = 0; pass < passes; pass++) {
                        checkCancel();
                        overwritePass(original, pass, passes, fileNumber, work.size(), name);
                    }

                    Uri working = original;
                    int renamed = 0;
                    for (int attempt = 0; attempt < renameAttempts; attempt++) {
                        checkCancel();
                        update(fileNumber, work.size(), 90, "RENAMING FILE",
                                name + " · attempt " + (attempt + 1) + "/" + renameAttempts);
                        try {
                            Uri next = DocumentsContract.renameDocument(
                                    getContentResolver(), working, "wipe-" + randomHex(12));
                            if (next == null) break;
                            working = next;
                            renamed++;
                        } catch (Exception unsupported) {
                            logFromWorker("Rename not supported by this storage provider.");
                            break;
                        }
                    }

                    checkCancel();
                    update(fileNumber, work.size(), 97, "DELETING FILE", name);
                    if (!DocumentsContract.deleteDocument(getContentResolver(), working)) {
                        throw new IllegalStateException(
                                "The storage provider did not confirm deletion.");
                    }

                    deleted++;
                    logFromWorker("SUCCESS: " + name + " — deleted; overwrite passes attempted: "
                            + passes + "; filename changes: " + renamed + ".");
                } catch (Cancelled e) {
                    cancelled++;
                    logFromWorker("CANCELLED: " + name
                            + " — it may have been partly modified; deletion was not intentionally continued.");
                    break;
                } catch (Exception e) {
                    failed++;
                    logFromWorker("FAILED: " + name + " — " + safeMessage(e));
                }

                final int overall = (int) (100.0 * (index + 1) / work.size());
                runOnUiThread(() -> progressBar.setProgress(overall));
            }

            final boolean wasCancelled = cancelRequested || cancelled > 0;
            final int deletedCount = deleted;
            final int failedCount = failed;
            final int cancelledCount = cancelled;

            logFromWorker("FINISHED. Deleted: " + deletedCount
                    + "; failed: " + failedCount + "; cancelled: " + cancelledCount + ".");

            runOnUiThread(() -> {
                setRunningUi(false);
                stageView.setText(wasCancelled ? "CANCELLED" : "FINISHED");
                statusView.setText(operationLog.toString());
                if (!wasCancelled && failedCount == 0) progressBar.setProgress(100);
                selectedFiles.clear();
                refreshSelection();
                statusView.setText(operationLog.toString());
            });
        }, "WipeFilesWorker").start();
    }

    private void overwritePass(Uri uri, int pass, int totalPasses,
                               int fileNumber, int fileTotal, String name) throws Exception {
        checkCancel();

        long size = fileSize(uri);
        if (size < 0) size = measureSize(uri);
        if (size == 0) {
            logFromWorker("File is empty; no content bytes to overwrite: " + name);
            return;
        }

        byte[] buffer = new byte[64 * 1024];
        long written = 0;

        try (ParcelFileDescriptor descriptor =
                     getContentResolver().openFileDescriptor(uri, "rw")) {
            if (descriptor == null) {
                throw new IllegalStateException("Write access was not granted.");
            }

            try (FileOutputStream output =
                         new FileOutputStream(descriptor.getFileDescriptor())) {
                FileChannel channel = output.getChannel();
                channel.position(0);

                while (written < size) {
                    checkCancel();
                    random.nextBytes(buffer);
                    int chunk = (int) Math.min(buffer.length, size - written);
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, chunk);

                    while (byteBuffer.hasRemaining()) {
                        channel.write(byteBuffer);
                    }
                    written += chunk;

                    int passPercent = (int) (written * 100 / size);
                    int overall = (int) (((pass + (passPercent / 100.0))
                            / totalPasses) * 85);

                    update(fileNumber, fileTotal, overall, "OVERWRITING CONTENT",
                            name + " · pass " + (pass + 1) + "/" + totalPasses
                                    + " · " + passPercent + "%");
                }

                channel.force(true);
            }
        }
    }

    private void update(int fileNumber, int total, int progress,
                        String stage, String detail) {
        runOnUiThread(() -> {
            stageView.setText(stage);
            progressBar.setProgress(Math.max(0, Math.min(progress, 100)));
            statusView.setText("File " + fileNumber + " of " + total + "\n"
                    + detail + "\n\n" + operationLog);
        });
    }

    private void appendLog(String message) {
        if (operationLog.length() > 0) operationLog.append('\n');
        operationLog.append(message);
        if (statusView != null) statusView.setText(operationLog.toString());
    }

    private void logFromWorker(String message) {
        runOnUiThread(() -> appendLog(message));
    }

    private void checkCancel() throws Cancelled {
        if (cancelRequested) throw new Cancelled();
    }

    private static class Cancelled extends Exception { }

    private long measureSize(Uri uri) throws Exception {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot read selected file.");
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancel();
                total += count;
            }
        }
        return total;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) { }
        String fallback = uri.getLastPathSegment();
        return fallback == null ? "(unnamed file)" : fallback;
    }

    private long fileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (column >= 0 && !cursor.isNull(column)) {
                    long size = cursor.getLong(column);
                    if (size >= 0) return size;
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private String randomHex(int length) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(alphabet[random.nextInt(alphabet.length)]);
        }
        return result.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return (message == null || message.trim().isEmpty())
                ? exception.getClass().getSimpleName() : message;
    }
}