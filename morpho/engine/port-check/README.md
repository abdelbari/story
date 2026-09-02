# Port check

The app reads PDFs on the phone with a different library from the one the
engine develops against: the tom-roush PDFBox-Android port, not desktop
PDFBox. The two are API-compatible but not behaviour-compatible, and the
difference has already cost a released-looking bug — a repair of a corrupt
font map that worked on the desktop and did nothing on the phone, because
the two libraries fill an unmapped character code differently.

This module runs the app's own Android readers, unchanged, against the real
port on the JVM, so that class of difference is caught by the build rather
than by a reader holding a phone.

How it stands up:

- the port ships as an Android archive, so the build unpacks the classes and
  the assets it needs out of the `.aar` it resolves from Maven Central;
- the handful of Android classes the port touches — `Log`, `Paint`, `Path`,
  `Matrix`, the geometry — are stubbed here in `src/main/java`, far enough
  to load and run text extraction, which draws nothing;
- the Android readers themselves are compiled from where they live,
  `../../android/pdf/src/main/kotlin`, so this module tests the shipped
  sources rather than a copy of them.

What it cannot do is draw: the stubs have no canvas behind them, so this
checks what a reader *reads*, never what a page *looks like*.
