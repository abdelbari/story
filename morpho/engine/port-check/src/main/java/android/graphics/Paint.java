package android.graphics;
public class Paint {
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public static final int ANTI_ALIAS_FLAG = 1;
    public Paint() {}
    public Paint(int flags) {}
    public void setColor(int c) {}
    public int getColor() { return 0; }
    public void setStyle(Style s) {}
    public Style getStyle() { return Style.FILL; }
}
