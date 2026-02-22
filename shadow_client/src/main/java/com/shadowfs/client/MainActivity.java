package com.shadowfs.client;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

public class MainActivity extends Activity {
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Setup base UI with Code
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        Button startServiceBtn = new Button(this);
        startServiceBtn.setText("Avvia Shadow Daemon");
        
        Button permissionsBtn = new Button(this);
        permissionsBtn.setText("Richiedi Permessi Memoria");

        Button forceOffloadBtn = new Button(this);
        forceOffloadBtn.setText("Libera Spazio Adesso");

        layout.addView(permissionsBtn);
        layout.addView(startServiceBtn);
        layout.addView(forceOffloadBtn);
        
        setContentView(layout);

        permissionsBtn.setOnClickListener(v -> requestStorageManagerPermission());
        
        startServiceBtn.setOnClickListener(v -> {
            if (hasStoragePermission()) {
                Intent serviceIntent = new Intent(this, ShadowForegroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "ShadowDaemon avviato in background", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Permessi Storage Mancanti!", Toast.LENGTH_LONG).show();
            }
        });

        forceOffloadBtn.setOnClickListener(v -> {
            Intent forceIntent = new Intent(this, ShadowForegroundService.class);
            forceIntent.setAction("FORCE_OFFLOAD");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(forceIntent);
            } else {
                startService(forceIntent);
            }
            Toast.makeText(this, "Pulizia di Spazio Avviata...", Toast.LENGTH_SHORT).show();
        });
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true; // Per API < 30 si gestiscono con onRequestPermissionsResult, semplificato per il blueprint
    }

    private void requestStorageManagerPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.addCategory("android.intent.category.DEFAULT");
                intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                startActivityForResult(intent, 2296);
            } catch (Exception e) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, 2296);
            }
        }
    }
}
