package android.graphics;

/**
 * Stub: nothing here decodes a picture — there is no platform decoder on
 * this classpath — but the reader that asks one to compiles against this,
 * which is what catches a change to that reader before a phone does.
 *
 * The two fields that matter are the ones a caller both writes and reads:
 * a decode is asked for the size first with {@code inJustDecodeBounds},
 * which fills {@code outWidth} and {@code outHeight} and returns nothing,
 * and asked for the pixels afterwards at a fraction of that size.
 */
public class BitmapFactory {

    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
        public int outWidth;
        public int outHeight;
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        throw new UnsupportedOperationException("stub");
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) {
        throw new UnsupportedOperationException("stub");
    }
}
