package android.graphics;
public class Path {
    public enum FillType { WINDING, EVEN_ODD, INVERSE_WINDING, INVERSE_EVEN_ODD }
    public enum Op { DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE }
    public enum Direction { CW, CCW }
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    private boolean empty = true;
    private FillType fillType = FillType.WINDING;
    public Path() {}
    public Path(Path src) { if (src != null) set(src); }
    private void touch(float x, float y) { empty = false; minX = Math.min(minX, x); minY = Math.min(minY, y); maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); }
    public void moveTo(float x, float y) { touch(x, y); }
    public void lineTo(float x, float y) { touch(x, y); }
    public void quadTo(float x1, float y1, float x2, float y2) { touch(x1, y1); touch(x2, y2); }
    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) { touch(x1, y1); touch(x2, y2); touch(x3, y3); }
    public void addRect(RectF r, Direction d) { touch(r.left, r.top); touch(r.right, r.bottom); }
    public void addRect(float l, float t, float r, float b, Direction d) { touch(l, t); touch(r, b); }
    public void addPath(Path p) { if (p != null && !p.empty) { touch(p.minX, p.minY); touch(p.maxX, p.maxY); } }
    public void close() {}
    public void reset() { empty = true; minX = minY = Float.MAX_VALUE; maxX = maxY = -Float.MAX_VALUE; }
    public void rewind() { reset(); }
    public boolean isEmpty() { return empty; }
    public void set(Path src) { empty = src.empty; minX = src.minX; minY = src.minY; maxX = src.maxX; maxY = src.maxY; fillType = src.fillType; }
    public void setFillType(FillType ft) { fillType = ft; }
    public FillType getFillType() { return fillType; }
    public void computeBounds(RectF bounds, boolean exact) { if (empty) bounds.set(0, 0, 0, 0); else bounds.set(minX, minY, maxX, maxY); }
    public boolean op(Path other, Op op) { return true; }
    public boolean op(Path a, Path b, Op op) { return true; }
    public void transform(Matrix m) {}
    public void transform(Matrix m, Path dst) {}
}
