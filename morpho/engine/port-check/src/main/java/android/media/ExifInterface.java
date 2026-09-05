package android.media;

import java.io.IOException;
import java.io.InputStream;

/**
 * The note a camera leaves in a photograph saying which way up it goes,
 * as far as the recognizer reads it: the orientation tag and its values.
 * A stub, to compile against and no further; on the JVM every picture
 * is the right way up.
 */
public class ExifInterface {
    public static final String TAG_ORIENTATION = "Orientation";
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int ORIENTATION_NORMAL = 1;
    public static final int ORIENTATION_FLIP_HORIZONTAL = 2;
    public static final int ORIENTATION_ROTATE_180 = 3;
    public static final int ORIENTATION_FLIP_VERTICAL = 4;
    public static final int ORIENTATION_TRANSPOSE = 5;
    public static final int ORIENTATION_ROTATE_90 = 6;
    public static final int ORIENTATION_TRANSVERSE = 7;
    public static final int ORIENTATION_ROTATE_270 = 8;

    public ExifInterface(InputStream in) throws IOException {}

    public int getAttributeInt(String tag, int defaultValue) { return defaultValue; }
}
