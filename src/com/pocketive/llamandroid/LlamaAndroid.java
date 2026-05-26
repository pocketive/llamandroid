package com.pocketive.llamandroid;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import com.google.appinventor.components.annotations.*;
import com.google.appinventor.components.runtime.*;
import com.google.appinventor.components.common.ComponentCategory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@DesignerComponent(
    version = 3,
    description = "Run small language models on-device using llama.cpp",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "aiwebres/icon.png"
)
@SimpleObject(external = true)
@UsesPermissions(permissionNames =
    "android.permission.READ_EXTERNAL_STORAGE," +
    "android.permission.WRITE_EXTERNAL_STORAGE"
)
public class LlamaAndroid extends AndroidNonvisibleComponent {

    private static final String PREFS_NAME      = "LlamaAndroidPrefs";
    private static final String PREF_LIB_PATH   = "lib_path";
    private static final String PREF_MODEL_PATH = "model_path";
    private static final String PREF_CTX_SIZE   = "ctx_size";
    private static final String PREF_THREADS    = "threads";

    private final Context context;
    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile long    modelHandle = 0;
    private volatile boolean libsLoaded  = false;

    private native long nativeLoadModel(String modelPath, int contextSize, int threads);
    private native void nativeInfer(long handle, String prompt, int maxTokens, String stopString);
    private native void nativeFreeModel(long handle);

    public LlamaAndroid(ComponentContainer container) {
        super(container.$form());
        this.context  = container.$context();
        this.activity = container.$context() instanceof Activity
            ? (Activity) container.$context() : null;

        String savedLib = getSavedLibPath();
        if (savedLib != null && new File(savedLib).exists()) {
            loadLibFromPath(savedLib);
        }
        if (!libsLoaded) return;

        fireLibsReady();

        final String savedModel = getSavedModelPath();
        if (savedModel != null && new File(savedModel).exists()) {
            final int ctxSize = getSavedCtxSize();
            final int threads = getSavedThreads();
            executor.submit(new Runnable() {
                public void run() {
                    modelHandle = nativeLoadModel(savedModel, ctxSize, threads);
                    final boolean ok = modelHandle != 0;
                    if (!ok) clearSavedModelPath();
                    fireModelLoaded(ok);
                }
            });
        }
    }

    private String getSavedLibPath() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LIB_PATH, null);
    }
    private void saveLibPath(String path) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_LIB_PATH, path).apply();
    }
    private String getSavedModelPath() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_MODEL_PATH, null);
    }
    private void saveModelPath(String path) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_MODEL_PATH, path).apply();
    }
    private void clearSavedModelPath() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_MODEL_PATH).apply();
    }
    private void saveModelParams(int ctxSize, int threads) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(PREF_CTX_SIZE, ctxSize).putInt(PREF_THREADS, threads).apply();
    }
    private int getSavedCtxSize() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_CTX_SIZE, 2048);
    }
    private int getSavedThreads() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(PREF_THREADS, 4);
    }

    private void loadLibFromPath(String path) {
        try {
            System.load(path);
            libsLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            fireError("Failed to load lib: " + e.getMessage());
        }
    }

    @SimpleFunction(description = "Load libllamajni.so from a file path or content:// URI.")
    public void LoadLib(final String uriOrPath) {
        executor.submit(new Runnable() {
            public void run() {
                try {
                    String realPath = resolveAndCopy(uriOrPath, "libllamajni.so");
                    if (realPath == null) { fireError("Could not resolve: " + uriOrPath); return; }
                    loadLibFromPath(realPath);
                    if (libsLoaded) { saveLibPath(realPath); fireLibsReady(); }
                } catch (Exception e) {
                    fireError("LoadLib error: " + e.getMessage());
                }
            }
        });
    }

    @SimpleFunction(description =
        "Load a GGUF model. Accepts a direct file path (fastest, no copy) or a content:// URI. " +
        "For URIs, the model is copied to internal storage the first time only — " +
        "future launches auto-load without repicking. " +
        "Fires ModelLoaded(true/false) when done.")
    public void LoadModel(final String pathOrUri, final int contextSize, final int threads) {
        if (!libsLoaded) { fireError("Call LoadLib first."); return; }

        executor.submit(new Runnable() {
            public void run() {
                try {
                    String realPath = resolveModelFile(pathOrUri);
                    if (realPath == null) {
                        fireError("Could not resolve model path: " + pathOrUri);
                        fireModelLoaded(false);
                        return;
                    }

                    if (modelHandle != 0) {
                        nativeFreeModel(modelHandle);
                        modelHandle = 0;
                    }

                    fireProgress("Loading model…");
                    modelHandle = nativeLoadModel(realPath, contextSize, threads);
                    final boolean ok = modelHandle != 0;

                    if (ok) {
                        saveModelPath(realPath);
                        saveModelParams(contextSize, threads);
                    } else {
                        fireError("Model load failed — bad file or not enough RAM. Path: " + realPath);
                    }
                    fireModelLoaded(ok);

                } catch (Exception e) {
                    fireError("LoadModel exception: " + e.getMessage());
                    fireModelLoaded(false);
                }
            }
        });
    }

    @SimpleFunction(description =
        "Run inference on the loaded model. Tokens stream via OnToken; OnComplete fires when done. " +
        "stopString halts generation when that exact string appears in the output — " +
        "it is trimmed from the result automatically. " +
        "Common values: Qwen='<|im_end|>', Llama3='<|eot_id|>', Mistral='</s>', Gemma='<end_of_turn>', Phi='<|end|>'. " +
        "Pass empty string to disable (generation runs until maxTokens or EOG token).")
    public void Infer(final String prompt, final int maxTokens, final String stopString) {
        if (!libsLoaded)      { fireError("Call LoadLib first.");                    return; }
        if (modelHandle == 0) { fireError("No model loaded. Call LoadModel first."); return; }
        executor.submit(new Runnable() {
            public void run() {
                nativeInfer(modelHandle, prompt, maxTokens, stopString != null ? stopString : "");
            }
        });
    }

    @SimpleFunction(description = "Free the loaded model from memory.")
    public void FreeModel() {
        if (modelHandle != 0) {
            final long handle = modelHandle;
            modelHandle = 0;
            executor.submit(new Runnable() {
                public void run() { nativeFreeModel(handle); }
            });
        }
    }

    @SimpleFunction(description = "Delete cached .so and forget its path.")
    public void DeleteLib() {
        String saved = getSavedLibPath();
        if (saved != null) new File(saved).delete();
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_LIB_PATH).apply();
        libsLoaded = false;
    }

    @SimpleFunction(description = "Clear the saved lib path so LoadLib must be called again.")
    public void ForgetLib() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(PREF_LIB_PATH).apply();
        libsLoaded = false;
    }

    @SimpleFunction(description = "Forget the saved model path so it will be re-picked next launch.")
    public void ForgetModel() { clearSavedModelPath(); }

    @SimpleProperty(description = "True if the native library is loaded and ready.")
    public boolean IsLibsLoaded() { return libsLoaded; }

    @SimpleProperty(description = "True if a model is currently loaded.")
    public boolean IsModelLoaded() { return modelHandle != 0; }

    @SimpleProperty(description = "Saved lib path, or empty string.")
    public String SavedLibPath() { String p = getSavedLibPath(); return p != null ? p : ""; }

    @SimpleProperty(description =
        "Last successfully loaded model path, or empty string. " +
        "Check this in LibsReady: if empty, open the file picker; " +
        "otherwise the model is already auto-loading in the background.")
    public String SavedModelPath() { String p = getSavedModelPath(); return p != null ? p : ""; }

    @SimpleProperty(description = "Device Downloads folder path.")
    public String DownloadFolder() {
        return Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
    }

    @SimpleProperty(description = "Downloads/models/ folder. Put .gguf files here for zero-copy loading.")
    public String ModelsFolder() {
        File folder = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "models");
        if (!folder.exists()) folder.mkdirs();
        return folder.getAbsolutePath();
    }

    @SimpleProperty(description = "Stop string for Qwen models: <|im_end|>")
    public String StopQwen() { return "<|im_end|>"; }

    @SimpleProperty(description = "Stop string for Llama 3 models: <|eot_id|>")
    public String StopLlama3() { return "<|eot_id|>"; }

    @SimpleProperty(description = "Stop string for Mistral models: </s>")
    public String StopMistral() { return "</s>"; }

    @SimpleProperty(description = "Stop string for Gemma models: <end_of_turn>")
    public String StopGemma() { return "<end_of_turn>"; }

    @SimpleProperty(description = "Stop string for Phi models: <|end|>")
    public String StopPhi() { return "<|end|>"; }

    @SimpleEvent(description = "Fires when the native library is ready (including auto-load at startup).")
    public void LibsReady() { EventDispatcher.dispatchEvent(this, "LibsReady"); }

    @SimpleEvent(description = "Fires when model loading completes. 'success' is true if ready to use.")
    public void ModelLoaded(boolean success) { EventDispatcher.dispatchEvent(this, "ModelLoaded", success); }

    @SimpleEvent(description = "Fires for each batch of generated tokens.")
    public void OnToken(String token) { EventDispatcher.dispatchEvent(this, "OnToken", token); }

    @SimpleEvent(description = "Fires when generation is complete with the full output text.")
    public void OnComplete(String fullText) { EventDispatcher.dispatchEvent(this, "OnComplete", fullText); }

    @SimpleEvent(description =
        "Fires when an error occurs, or for progress updates (prefixed with 'progress: ').")
    public void Error(String message) { EventDispatcher.dispatchEvent(this, "Error", message); }

    private String resolveModelFile(String uriString) throws Exception {
        if (uriString == null)         return null;
        if (uriString.startsWith("/")) return uriString;

        Uri uri = Uri.parse(uriString);
        if ("file".equals(uri.getScheme()))    return uri.getPath();
        if ("content".equals(uri.getScheme())) return copyContentUri(uri);

        return uriString;
    }

    private String copyContentUri(Uri uri) throws Exception {
        String fileName   = "model.gguf";
        long   sourceSize = -1;

        Cursor cursor = context.getContentResolver().query(
            uri,
            new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
            null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
            if (nameIdx >= 0 && cursor.getString(nameIdx) != null)
                fileName  = cursor.getString(nameIdx);
            if (sizeIdx >= 0)
                sourceSize = cursor.getLong(sizeIdx);
            cursor.close();
        }

        File outFile = new File(context.getFilesDir(), fileName);

        if (outFile.exists()) {
            if (sourceSize > 0 && outFile.length() == sourceSize) {
                fireProgress("Using cached copy (" + (sourceSize / 1024 / 1024) + " MB)");
                return outFile.getAbsolutePath();
            }
            outFile.delete();
        }

        fireProgress("First-time copy to internal storage — please wait…");

        InputStream      is  = context.getContentResolver().openInputStream(uri);
        FileOutputStream fos = new FileOutputStream(outFile);
        byte[] buf    = new byte[131072];
        int    len;
        long   copied = 0;
        while ((len = is.read(buf)) > 0) {
            fos.write(buf, 0, len);
            copied += len;
            if (sourceSize > 0 && copied % (50L * 1024 * 1024) < 131072) {
                int pct = (int)(copied * 100 / sourceSize);
                fireProgress("Copying: " + pct + "% (" + (copied / 1024 / 1024) + " MB / "
                    + (sourceSize / 1024 / 1024) + " MB)");
            }
        }
        fos.close();
        is.close();
        fireProgress("Copy complete!");
        return outFile.getAbsolutePath();
    }

    private String resolveAndCopy(String uriString, String fallbackName) throws Exception {
        if (uriString == null)         return null;
        if (uriString.startsWith("/")) return uriString;

        Uri uri = Uri.parse(uriString);
        if ("file".equals(uri.getScheme())) return uri.getPath();

        if ("content".equals(uri.getScheme())) {
            String fileName  = fallbackName;
            long   sourceSize = -1;

            Cursor cursor = context.getContentResolver().query(
                uri,
                new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0 && cursor.getString(nameIdx) != null)
                    fileName  = cursor.getString(nameIdx);
                if (sizeIdx >= 0)
                    sourceSize = cursor.getLong(sizeIdx);
                cursor.close();
            }
            File outFile = new File(context.getFilesDir(), fileName);
            if (outFile.exists() && sourceSize > 0 && outFile.length() == sourceSize)
                return outFile.getAbsolutePath();
            if (outFile.exists()) outFile.delete();

            InputStream      is  = context.getContentResolver().openInputStream(uri);
            FileOutputStream fos = new FileOutputStream(outFile);
            byte[] buf = new byte[65536];
            int    len;
            while ((len = is.read(buf)) > 0) fos.write(buf, 0, len);
            fos.close();
            is.close();
            return outFile.getAbsolutePath();
        }
        return uriString;
    }

    private void fireLibsReady() {
        if (activity != null)
            activity.runOnUiThread(new Runnable() { public void run() { LibsReady(); } });
    }
    private void fireModelLoaded(final boolean ok) {
        if (activity != null)
            activity.runOnUiThread(new Runnable() { public void run() { ModelLoaded(ok); } });
    }
    private void fireProgress(final String msg) {
        if (activity != null)
            activity.runOnUiThread(new Runnable() { public void run() { Error("progress: " + msg); } });
    }
    private void fireError(final String msg) {
        if (activity != null)
            activity.runOnUiThread(new Runnable() { public void run() { Error(msg); } });
    }

    public void onToken(final String token) {
        if (activity != null)
            activity.runOnUiThread(new Runnable() { public void run() { OnToken(token); } });
    }
    public void onComplete(final String fullText) {
        if (activity != null)
            activity.runOnUiThread(new Runnable() { public void run() { OnComplete(fullText); } });
    }
}