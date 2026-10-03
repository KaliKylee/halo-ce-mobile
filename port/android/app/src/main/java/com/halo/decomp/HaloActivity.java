package com.halo.decomp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

/**
 * The game: SDL3's activity, running libmain.so (port/android/host), which
 * loads the game image from the APK's assets.
 */
public class HaloActivity extends SDLActivity {
    /** lets system link's broadcasts in over Wi-Fi while the game runs */
    private WifiManager.MulticastLock multicastLock;
    private TouchControls touchControls;
    private static final int EXPORT_LAYOUT = 401, IMPORT_LAYOUT = 402;
    private String pendingLayoutExport;

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null)
            pendingLayoutExport = savedInstanceState.getString("pending-layout-export");
        if (mLayout != null) {
            touchControls = new TouchControls(this);
            mLayout.addView(touchControls, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferHighestRefreshRate();
        acquireMulticastLock();
    }

    /** SAF lets the player choose a folder and filename without storage permissions. */
    public void chooseLayoutFile(boolean export, String configuration) {
        new AlertDialog.Builder(this).setTitle(export ? "Exportar layout" : "Importar layout")
            .setMessage(export ? "Escolha a pasta e o nome do arquivo do seu layout de toque."
                : "Escolha um layout de toque do Halo exportado. Ele substituirá seus botões, sensibilidade e configurações Gerais atuais.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton(export ? "Escolher local" : "Escolher arquivo", (dialog, which) -> {
                Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(export ? "text/plain" : "*/*");
                if (export) {
                    pendingLayoutExport = configuration;
                    intent.putExtra(Intent.EXTRA_TITLE, "halo-touch-layout.halolayout");
                }
                try { startActivityForResult(intent, export ? EXPORT_LAYOUT : IMPORT_LAYOUT); }
                catch (android.content.ActivityNotFoundException e) {
                    pendingLayoutExport = null;
                    Toast.makeText(this, "Nenhum seletor de documentos disponível.", Toast.LENGTH_LONG).show();
                }
            }).show();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("pending-layout-export", pendingLayoutExport);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        if (request != EXPORT_LAYOUT && request != IMPORT_LAYOUT) {
            super.onActivityResult(request, result, data); return;
        }
        String exported = pendingLayoutExport; pendingLayoutExport = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri document = data.getData();
        new Thread(() -> {
            try {
                if (request == EXPORT_LAYOUT) {
                    if (exported == null) throw new java.io.IOException("Layout snapshot is unavailable");
                    try (OutputStream output = getContentResolver().openOutputStream(document, "wt")) {
                        if (output == null) throw new java.io.IOException("Cannot open destination");
                        output.write(exported.getBytes(StandardCharsets.UTF_8));
                    }
                    runOnUiThread(() -> Toast.makeText(this, "Layout exportado.", Toast.LENGTH_SHORT).show());
                } else {
                    ByteArrayOutputStream contents = new ByteArrayOutputStream();
                    try (InputStream input = getContentResolver().openInputStream(document)) {
                        if (input == null) throw new java.io.IOException("Cannot open layout file");
                        byte[] buffer = new byte[4096]; int size;
                        while ((size = input.read(buffer)) != -1) {
                            if (contents.size()+size > 65536) throw new java.io.IOException("Layout file is too large");
                            contents.write(buffer, 0, size);
                        }
                    }
                    String configuration = new String(contents.toByteArray(), StandardCharsets.UTF_8);
                    // Validate away from the UI thread; apply atomically to the active view.
                    TouchLayout.importConfiguration(configuration);
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed() || touchControls == null) return;
                        try {
                            touchControls.importLayout(configuration);
                            Toast.makeText(this, "Layout importado e salvo.", Toast.LENGTH_SHORT).show();
                        } catch (IllegalArgumentException e) { layoutFileError(e); }
                    });
                }
            } catch (Exception e) { runOnUiThread(() -> layoutFileError(e)); }
        }, "halo-touch-layout-file").start();
    }

    private void layoutFileError(Exception error) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle("Arquivo de layout")
            .setMessage("Não foi possível concluir a operação: "+error.getMessage())
            .setPositiveButton("OK", null).show();
    }

    @Override protected void onResume() {
        super.onResume();
        if (touchControls != null && getWindow().getDecorView().hasWindowFocus())
            touchControls.startDeviceInput();
    }

    @Override
    protected void onPause() {
        if (touchControls != null) touchControls.stopDeviceInput();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (touchControls != null) {
            if (hasFocus) touchControls.startDeviceInput();
            else touchControls.stopDeviceInput();
        }
        super.onWindowFocusChanged(hasFocus);
    }

    @Override
    protected void onDestroy() {
        if (touchControls != null) touchControls.stopDeviceInput();
        if (multicastLock != null && multicastLock.isHeld())
            multicastLock.release();
        multicastLock = null;
        super.onDestroy();
    }

    /**
     * Many phones drop the Wi-Fi's broadcast and multicast datagrams to
     * save power unless an app holds this: without it they would not see
     * system link games on the local network, nor be seen hosting one.
     */
    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null)
                return;
            multicastLock = wifi.createMulticastLock("halo-system-link");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        } catch (RuntimeException e) {
            // (no Wi-Fi, or not allowed: the local network may miss games)
            multicastLock = null;
        }
    }

    /**
     * The game draws a frame at every display refresh, between its 30 Hz
     * ticks (port/shared/game/render_interpolation.c); Android otherwise
     * often keeps an app at 60 Hz on a faster display.
     */
    private void preferHighestRefreshRate() {
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = current;

        for (Display.Mode mode : display.getSupportedModes()) {
            if (mode.getPhysicalWidth() == current.getPhysicalWidth() &&
                mode.getPhysicalHeight() == current.getPhysicalHeight() &&
                mode.getRefreshRate() > best.getRefreshRate()) {
                best = mode;
            }
        }
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(attributes);
    }
}
