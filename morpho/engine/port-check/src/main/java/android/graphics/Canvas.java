package android.graphics;
public class Canvas {
    public Canvas() {}
    public Canvas(Bitmap b) {}
    public void drawColor(int c) {}
    public void drawBitmap(Bitmap b, float x, float y, Paint p) {}
    public void drawRect(float l, float t, float r, float b, Paint p) {}
    public int save() { return 0; }
    public void restore() {}
    public void translate(float dx, float dy) {}
}
