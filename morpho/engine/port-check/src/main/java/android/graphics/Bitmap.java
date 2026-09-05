package android.graphics;
public class Bitmap {
    public enum Config { ALPHA_8, RGB_565, ARGB_4444, ARGB_8888 }
    public enum CompressFormat { JPEG, PNG, WEBP }
    public static Bitmap createBitmap(int w, int h, Config c) { throw new UnsupportedOperationException("stub"); }
    public static Bitmap createBitmap(Bitmap b, int x, int y, int w, int h) { throw new UnsupportedOperationException("stub"); }
    public static Bitmap createBitmap(Bitmap b, int x, int y, int w, int h, Matrix m, boolean filter) { throw new UnsupportedOperationException("stub"); }
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public boolean compress(CompressFormat f, int q, java.io.OutputStream s) { return false; }
    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {}
    public void recycle() {}
}
