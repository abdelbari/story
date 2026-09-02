package android.graphics;
public class Region {
    public enum Op { DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE, REPLACE }
    private final Rect bounds = new Rect();
    public Region() {}
    public Region(Rect r) { bounds.set(r.left, r.top, r.right, r.bottom); }
    public Region(int l, int t, int r, int b) { bounds.set(l, t, r, b); }
    public boolean setPath(Path p, Region clip) { RectF f = new RectF(); p.computeBounds(f, true); f.roundOut(bounds); return !bounds.isEmpty(); }
    public boolean set(Region r) { bounds.set(r.bounds.left, r.bounds.top, r.bounds.right, r.bounds.bottom); return true; }
    public boolean set(int l, int t, int r, int b) { bounds.set(l, t, r, b); return true; }
    public boolean op(Region r, Op op) { return true; }
    public boolean op(Rect r, Op op) { return true; }
    public Path getBoundaryPath() { Path p = new Path(); p.addRect(bounds.left, bounds.top, bounds.right, bounds.bottom, Path.Direction.CW); return p; }
    public boolean isEmpty() { return bounds.isEmpty(); }
    public Rect getBounds() { return new Rect(bounds.left, bounds.top, bounds.right, bounds.bottom); }
}
