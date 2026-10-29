package uk.org.platitudes.wipe;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int PICK_FILES = 42;
    private static final int NUM_PASSES = 3;
    private static final int BUF_SIZE = 64 * 1024;

    private final List<Uri> mSelected = new ArrayList<>();
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        Button pick = new Button(this);
        pick.setText("Choose files to wipe");
        pick.setOnClickListener(v -> pickFiles());
        root.addView(pick);

        Button wipe = new Button(this);
        wipe.setText("WIPE SELECTED FILES FOREVER");
        wipe.setOnClickListener(v -> wipeSelected());
        root.addView(wipe);

        mStatus = new TextView(this);
        mStatus.setText("No files selected.");
        mStatus.setTextIsSelectable(true);
        root.addView(mStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        setContentView(root);
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
        sb.append("\nEach file will be overwritten with ")
          .append(NUM_PASSES).append(" passes of random data, then deleted.");
        mStatus.setText(sb.toString());
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
            mStatus.setText("Nothing selected — choose files first.");
            return;
        }
        final List<Uri> toWipe = new ArrayList<>(mSelected);
        mStatus.setText("Wiping…");

        new Thread(() -> {
            StringBuilder results = new StringBuilder();
            for (Uri uri : toWipe) {
                String name = displayName(uri);
                try {
                    wipeOne(uri);
                    results.append("✔ wiped and deleted: ").append(name).append('\n');
                } catch (Exception e) {
                    results.append("✘ FAILED (").append(name).append("): ")
                           .append(e.getMessage()).append('\n');
                }
            }
            results.append("\nDone. Storage note: on flash storage, overwriting "
                    + "reduces but cannot guarantee recovery-proof erasure "
                    + "(wear-leveling). Best-effort only.");
            runOnUiThread(() -> {
                mSelected.clear();
                mStatus.setText(results.toString());
            });
        }).start();
    }

    private void wipeOne(Uri uri) throws Exception {
        long size = fileSize(uri);
        if (size == 0) {
            // Empty file
        } else if (size < 0) {
            overwriteStreaming(uri, 1024 * 1024);
        } else {
            for (int pass = 0; pass < NUM_PASSES; pass++) {
                try (ParcelFileDescriptor pfd =
                             getContentResolver().openFileDescriptor(uri, "rw")) {
                    if (pfd == null) throw new IllegalStateException("cannot open for write");
                    SecureRandom rnd = new SecureRandom();
                    byte[] buf = new byte[BUF_SIZE];
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

    private void overwriteStreaming(Uri uri, long maxBytes) throws Exception {
        SecureRandom rnd = new SecureRandom();
        byte[] buf = new byte[BUF_SIZE];
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
