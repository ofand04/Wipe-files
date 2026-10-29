package uk.org.platitudes.wipe;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int PICK_FILES = 42;
    private final List<Uri> mSelected = new ArrayList<>();
    private TextView mStatus;
    private EditText mEditPasses;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatus = findViewById(R.id.status);
        mEditPasses = findViewById(R.id.editPasses);

        Button btnPick = findViewById(R.id.btnPick);
        Button btnWipe = findViewById(R.id.btnWipe);

        btnPick.setOnClickListener(v -> pickFiles());
        btnWipe.setOnClickListener(v -> wipeSelected());
    }

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FILES || resultCode != RESULT_OK || data == null) return;

        mSelected.clear();
        if (data.getClipData() != null) {
            for (int n = 0; n < data.getClipData().getItemCount(); n++) {
                mSelected.add(data.getClipData().getItemAt(n).getUri());
            }
        } else if (data.getData() != null) {
            mSelected.add(data.getData());
        }
        updateStatus();
    }

    private void updateStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append(mSelected.size()).append(" file(s) selected:\n");
        for (Uri u : mSelected) {
            sb.append("  • ").append(displayName(u)).append('\n');
        }
        int passes = getPasses();
        sb.append("\nEach file will be overwritten ").append(passes)
          .append(" times with random data, then deleted.");
        mStatus.setText(sb.toString());
    }

    private int getPasses() {
        try {
            int p = Integer.parseInt(mEditPasses.getText().toString().trim());
            return Math.max(1, Math.min(p, 10));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    private long fileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0) {
                    long s = c.getLong(idx);
                    if (s >= 0) return s;
                }
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private void wipeSelected() {
        if (mSelected.isEmpty()) {
            Toast.makeText(this, "Choose files first", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Uri> toWipe = new ArrayList<>(mSelected);
        mStatus.setText("Wiping…");

        new Thread(() -> {
            StringBuilder results = new StringBuilder();
            int passes = getPasses();
            for (Uri uri : toWipe) {
                String name = displayName(uri);
                try {
                    wipeOne(uri, passes);
                    results.append("✔ wiped & deleted: ").append(name).append('\n');
                } catch (Exception e) {
                    results.append("✘ FAILED (").append(name).append("): ")
                           .append(e.getMessage()).append('\n');
                }
            }
            results.append("\nDone. Note: Flash wear-leveling means overwrite ");
            results.append("reduces but cannot guarantee recovery-proof erasure.\n");
            results.append("Modern Android encryption usually makes deleted ");
            results.append("files unrecoverable anyway.");
            runOnUiThread(() -> {
                mSelected.clear();
                mStatus.setText(results.toString());
                mEditPasses.setText("3");
            });
        }).start();
    }

    private void wipeOne(Uri uri, int numPasses) throws Exception {
        long size = fileSize(uri);
        if (size == 0) {
            // Empty file
        } else if (size < 0) {
            overwriteStreaming(uri, 1024 * 1024, numPasses);
        } else {
            for (int pass = 0; pass < numPasses; pass++) {
                try (ParcelFileDescriptor pfd =
                             getContentResolver().openFileDescriptor(uri, "rw")) {
                    if (pfd == null) throw new IllegalStateException("cannot open for write");
                    SecureRandom rnd = new SecureRandom();
                    byte[] buf = new byte[64 * 1024];
                    try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                        long written = 0;
                        while (written < size) {
                            rnd.nextBytes(buf);
                            int chunk = (int) Math.min(buf.length, size - written);
                            out.write(buf, 0, chunk);
                            written += chunk;
                        }
                        out.flush();
                        out.getFD().sync();
                    }
                }
            }
        }
        if (!DocumentsContract.deleteDocument(getContentResolver(), uri)) {
            throw new IllegalStateException("deleteDocument returned false");
        }
    }

    private void overwriteStreaming(Uri uri, long maxBytes, int numPasses) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] buf = new byte[64 * 1024];
        for (int pass = 0; pass < numPasses; pass++) {
            try (ParcelFileDescriptor pfd =
                         getContentResolver().openFileDescriptor(uri, "rwt")) {
                if (pfd == null) throw new IllegalStateException("cannot open for write");
                try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                    long written = 0;
                    while (written < maxBytes) {
                        rnd.nextBytes(buf);
                        out.write(buf);
                        written += buf.length;
                    }
                    out.flush();
                    out.getFD().sync();
                }
            }
        }
    }
    }
