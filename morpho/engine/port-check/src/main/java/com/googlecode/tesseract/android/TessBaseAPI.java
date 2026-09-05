package com.googlecode.tesseract.android;

/**
 * Stub: recognition itself cannot run here — there is no Tesseract on this
 * classpath and no canvas behind the bitmap it would read — but the reader
 * that drives it compiles against this, which is what catches a change to
 * that reader before a phone does.
 *
 * The signatures and the constants are the real ones, taken from the
 * library's own class file and its published source rather than from
 * memory of them: a stub that is merely plausible compiles the reader
 * against an API that does not exist, which is worse than not compiling it
 * at all.
 */
public class TessBaseAPI {

    /**
     * How much of the page recognition works out before it reads any of
     * it. The values are the ones Tesseract has published since the modes
     * were named, and the ones the Android library declares.
     */
    public static final class PageSegMode {
        /** Find the columns, the blocks and the lines, then read them. */
        public static final int PSM_AUTO = 3;

        /**
         * Read the whole image as one block of text, working nothing out.
         * This is what recognition does when it is not asked, which is why
         * it is named here rather than left as a number in a comment.
         */
        public static final int PSM_SINGLE_BLOCK = 6;
    }

    public boolean init(String datapath, String language) { throw new UnsupportedOperationException("stub"); }
    public boolean setVariable(String var, String value) { throw new UnsupportedOperationException("stub"); }
    public void setPageSegMode(int mode) { throw new UnsupportedOperationException("stub"); }
    public int getPageSegMode() { throw new UnsupportedOperationException("stub"); }
    public void setImage(android.graphics.Bitmap bitmap) { throw new UnsupportedOperationException("stub"); }
    public String getUTF8Text() { throw new UnsupportedOperationException("stub"); }
    public String getHOCRText(int page) { throw new UnsupportedOperationException("stub"); }
    public void recycle() {}
}
