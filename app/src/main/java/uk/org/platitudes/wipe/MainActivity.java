package com.gorillacoding.wipefiles;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_MANAGE_STORAGE = 2296;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Show the permission pop-up immediately when the app opens
        checkAndRequestAllFilesPermission();

        Button btnWipe = findViewById(R.id.btnWipe);
        btnWipe.setOnClickListener(v -> {
            if (checkStoragePermission()) {
                Toast.makeText(this, "Permission granted. Ready to wipe files.", Toast.LENGTH_SHORT).show();
            } else {
                checkAndRequestAllFilesPermission();
            }
        });
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    private void checkAndRequestAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                new AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("This app asks for permission to manage all files on your device to securely clean and wipe data. Please grant 'All files access' on the next screen.")
                    .setCancelable(false)
                    .setPositiveButton("Grant Permission", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                        } catch (Exception e) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        Toast.makeText(MainActivity.this, "Permission is required to use this app.", Toast.LENGTH_LONG).show();
                        finish();
                    })
                    .show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "All Files Access granted!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Permission denied. App cannot function without it.", Toast.LENGTH_LONG).show();
                }
            }
        }
    }
}
