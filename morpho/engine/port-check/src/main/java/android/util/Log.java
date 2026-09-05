package android.util;
public final class Log {
    public static final int VERBOSE = 2, DEBUG = 3, INFO = 4, WARN = 5, ERROR = 6;
    public static boolean isLoggable(String tag, int level) { return false; }
    public static int v(String t, String m) { return 0; } public static int v(String t, String m, Throwable e) { return 0; }
    public static int d(String t, String m) { return 0; } public static int d(String t, String m, Throwable e) { return 0; }
    public static int i(String t, String m) { return 0; } public static int i(String t, String m, Throwable e) { return 0; }
    public static int w(String t, String m) { return 0; } public static int w(String t, String m, Throwable e) { return 0; } public static int w(String t, Throwable e) { return 0; }
    public static int e(String t, String m) { return 0; } public static int e(String t, String m, Throwable e) { return 0; }
}
