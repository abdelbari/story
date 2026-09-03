package com.googlecode.tesseract.android;

/**
 * Stub: recognition itself cannot run here — there is no Tesseract on this
 * classpath and no canvas behind the bitmap it would read — but the reader
 * that drives it compiles against this, which is what catches a change to
 * that reader before a phone does.
 */
public class TessBaseAPI {
    public boolean init(String datapath, String language) { throw new UnsupportedOperationException("stub"); }
    public void setImage(android.graphics.Bitmap bitmap) { throw new UnsupportedOperationException("stub"); }
    public String getUTF8Text() { throw new UnsupportedOperationException("stub"); }
    public void recycle() {}
}
