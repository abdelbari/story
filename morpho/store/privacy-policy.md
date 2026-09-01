# Privacy Policy — Morpho

_Last updated: 1 September 2026_

Morpho is a document converter that runs entirely on your device.

## The short version

**Morpho collects nothing, sends nothing, and stores nothing about you.**
It cannot: the app is built without the Android `INTERNET` permission, so it
has no ability to make a network connection of any kind. You can verify this
yourself — check the app's permissions on your device, or inspect the manifest
of the published app bundle.

## Your documents

The documents you convert are opened, converted, and saved entirely on your
device.

- They are **never uploaded**, because the app has no network access at all.
- They are **not stored** by Morpho. The app reads the file you pick, converts
  it in memory, and writes the result to the location you choose through your
  device's own file picker. It keeps no copies and no library of its own.
- Optical character recognition (OCR) for scanned documents also runs on the
  device, using language models bundled inside the app. No page image or
  recognized text leaves your phone.

## What Morpho does not do

- No accounts, no sign-in, no email address.
- No analytics, usage tracking, advertising identifiers, or third-party SDKs
  that collect data.
- No crash reporting. (Should crash reporting ever be added, it will be
  announced in this policy first, and it will never include document content,
  file names, or file paths.)
- No ads.

## Permissions

Morpho requests **no runtime permissions**, and holds no network permission.
It reads and writes files only through the Android Storage Access Framework —
the system file picker — which grants access to exactly the one file you
select, and nothing else.

## Children

Morpho collects no data from anyone, including children.

## Third-party components

Morpho includes open-source libraries that run on the device and do not
transmit data: Tesseract OCR (Apache License 2.0), PDFBox for Android (Apache
License 2.0), and AndroidX/Jetpack Compose (Apache License 2.0). The OCR
language models are from the Tesseract project (Apache License 2.0).

## Changes to this policy

If this policy changes, the updated version will be published here with a new
date. Because the app has no network access, a change to what Morpho does with
data would require a new version of the app, which you would have to install
yourself.

## Contact

_(Add your contact email address here before publishing.)_
