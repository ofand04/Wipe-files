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
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
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
    private static final int MIN_NAME_CHANGES = 1;
    private static final int MAX_NAME_CHANGES = 100;
    private static final int MIN_CONTENT_PASSES = 1;
    private static final int MAX_CONTENT_PASSES = 10;

    private final List<Uri> selectedFiles = new ArrayList<>();
    private final SecureRandom secureRandom = new SecureRandom();

    private TextView selectedCount;
    private TextView selectedFilesView;
    private TextView statusView;
    private EditText editNameChanges;
    private EditText editContentPasses;
    private Button pickButton;
    private Button wipeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().setStatusBarColor(Color.rgb(8, 13, 12));
        getWindow().setNavigationBarColor(Color.rgb(8, 13, 12));

        selectedCount = findViewById(R.id.selectedCount);
        selectedFilesView = findViewById(R.id.selectedFiles);
        statusView = findViewById(R.id.status);
        editNameChanges = findViewById(R.id.editNameChanges);
        editContentPasses = findViewById(R.id.editContentPasses);
        pickButton = findViewById(R.id.btnPick);
        wipeButton = findViewById(R.id.btnWipe);

        pickButton.setOnClickListener(v -> pickFiles());
        wipeButton.setOnClickListener(v -> confirmWipe());
        refreshSelection();
    }

    private void pickFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, PICK_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) {
            return;
        }

        selectedFiles.clear();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (!selectedFiles.contains(uri)) selectedFiles.add(uri);
            }
        } else if (data.getData() != null) {
            selectedFiles.add(data.getData());
        }
        refreshSelection();
    }

    private void refreshSelection() {
        selectedCount.setText(String.format(Locale.getDefault(), "%02d FILE(S) QUEUED",
                selectedFiles.size()));

        if (selectedFiles.isEmpty()) {
            selectedFilesView.setText("No files selected yet.\nChoose files to review them here.");
            statusView.setText("READY // Waiting for file selection");
            wipeButton.setEnabled(false);
            wipeButton.setAlpha(0.55f);
            return;
        }

        StringBuilder names = new StringBuilder();
        for (int i = 0; i < selectedFiles.size(); i++) {
            names.append(i + 1).append(".  ").append(displayName(selectedFiles.get(i)));
            long size = fileSize(selectedFiles.get(i));
            if (size >= 0) names.append("  ·  ").append(formatSize(size));
            if (i < selectedFiles.size() - 1) names.append('\n');
        }
        selectedFilesView.setText(names.toString());
        statusView.setText("READY // Review settings, then start the wipe");
        wipeButton.setEnabled(true);
        wipeButton.setAlpha(1f);
    }

    private void confirmWipe() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Select at least one file first", Toast.LENGTH_SHORT).show();
            return;
        }

        int nameChanges = readSetting(editNameChanges, 10, MIN_NAME_CHANGES, MAX_NAME_CHANGES);
        int contentPasses = readSetting(editContentPasses, 3,
                MIN_CONTENT_PASSES, MAX_CONTENT_PASSES);

        String message = "Files selected: " + selectedFiles.size()
                + "\nFilename change attempts: " + nameChanges
                + "\nRandom-content overwrite passes: " + contentPasses
                + "\n\nThis operation is destructive. Unsupported storage providers may reject "
                + "renames or overwrites. Continue only if these are the correct files.";

        new AlertDialog.Builder(this)
                .setTitle("CONFIRM SECURE WIPE")
                .setMessage(message)
                .setNegativeButton("CANCEL", null)
                .setPositiveButton("WIPE SELECTED", (dialog, which) ->
                        startWipe(nameChanges, contentPasses))
                .show();
    }

    private int readSetting(EditText field, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(field.getText().toString().trim());
            return Math.max(min, Math.min(value, max));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void startWipe(int nameChanges, int contentPasses) {
        final List<Uri> workList = new ArrayList<>(selectedFiles);
        pickButton.setEnabled(false);
        wipeButton.setEnabled(false);
        statusView.setText("RUNNING // Processing " + workList.size() + " file(s)…");

        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            int deleted = 0;
            int failed = 0;

            for (Uri originalUri : workList) {
                String originalName = displayName(originalUri);
                try {
                    WipeResult result = wipeOne(originalUri, nameChanges, contentPasses);
                    deleted++;
                    report.append("✓ DELETED  ").append(originalName)
                            .append("\n   Filename changes: ").append(result.renameCount)
                            .append("/").append(nameChanges)
                            .append(" · Content passes: ").append(contentPasses)
                            .append("\n");
                    if (result.renameCount < nameChanges) {
                        report.append("   NOTE: Some filename changes were not supported.\n");
                    }
                } catch (Exception e) {
                    failed++;
                    report.append("✕ FAILED  ").append(originalName)
                            .append("\n   ").append(safeMessage(e)).append("\n");
                }
            }

            report.append("\nFINISHED // Deleted: ").append(deleted)
                    .append(" · Failed: ").append(failed)
                    .append("\n\nSECURITY NOTE: Filename changes modify provider-visible names; "
                            + "they do not erase filesystem metadata. Multiple content-overwrite "
                            + "passes cannot guarantee recovery-proof erasure on flash storage, "
                            + "cloud providers, or storage systems with copy-on-write behavior.");

            runOnUiThread(() -> {
                selectedFiles.clear();
                refreshSelection();
                statusView.setText(report.toString());
                pickButton.setEnabled(true);
                editNameChanges.setText("10");
                editContentPasses.setText("3");
            });
        }).start();
    }

    private static final class WipeResult {
        final Uri finalUri;
        final int renameCount;

        WipeResult(Uri finalUri, int renameCount) {
            this.finalUri = finalUri;
            this.renameCount = renameCount;
        }
    }

    private WipeResult wipeOne(Uri originalUri, int nameChangeAttempts, int contentPasses)
            throws Exception {
        Uri workingUri = originalUri;
        int renamed = 0;

        // Renaming is best-effort. Some SAF providers do not expose rename support.
        for (int i = 0; i < nameChangeAttempts; i++) {
            String temporaryName = "wipe-" + randomHex(12);
            try {
                Uri renamedUri = DocumentsContract.renameDocument(
                        getContentResolver(), workingUri, temporaryName);
                if (renamedUri == null) break;
                workingUri = renamedUri;
                renamed++;
            } catch (Exception unsupported) {
                break;
            }
        }

        overwriteContent(workingUri, contentPasses);

        if (!DocumentsContract.deleteDocument(getContentResolver(), workingUri)) {
            throw new IllegalStateException("Storage provider did not confirm deletion");
        }
        return new WipeResult(workingUri, renamed);
    }

    private void overwriteContent(Uri uri, int passes) throws Exception {
        long size = fileSize(uri);
        if (size < 0) size = measureSize(uri);

        byte[] bytes = new byte[64 * 1024];
        for (int pass = 0; pass < passes; pass++) {
            if (size == 0) continue;

            try (ParcelFileDescriptor pfd =
                         getContentResolver().openFileDescriptor(uri, "rw")) {
                if (pfd == null) {
                    throw new IllegalStateException("Storage provider refused write access");
                }
                try (FileOutputStream output = new FileOutputStream(pfd.getFileDescriptor())) {
                    FileChannel channel = output.getChannel();
                    channel.position(0);
                    long written = 0;

                    while (written < size) {
                        secureRandom.nextBytes(bytes);
                        int chunk = (int) Math.min(bytes.length, size - written);
                        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, chunk);
                        while (buffer.hasRemaining()) channel.write(buffer);
                        written += chunk;
                    }
                    channel.force(true);
                }
            }
        }
    }

    private long measureSize(Uri uri) throws Exception {
        long count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot read file size");
            int read;
            while ((read = input.read(buffer)) != -1) count += read;
        }
        return count;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
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
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    long size = cursor.getLong(index);
                    if (size >= 0) return size;
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private String randomHex(int chars) {
        final char[] alphabet = "0123456789abcdef".toCharArray();
        StringBuilder value = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) value.append(alphabet[secureRandom.nextInt(alphabet.length)]);
        return value.toString();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message;
    }
                                      }
