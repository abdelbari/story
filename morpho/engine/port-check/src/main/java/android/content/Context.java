package android.content;

/**
 * Stub: far enough for the app's readers to compile and for the port's
 * resource loader to take one. The app asks a context for three things —
 * itself, its files directory, and its assets — and none of them is
 * touched by anything this module runs.
 */
public class Context {
    public Context getApplicationContext() { return this; }
    public java.io.File getFilesDir() { throw new UnsupportedOperationException("stub"); }
    public android.content.res.AssetManager getAssets() { throw new UnsupportedOperationException("stub"); }
}
