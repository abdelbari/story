# Running Morpho on your own phone

For a first run from a fresh machine. Everything here is free; nothing needs a
Play account. Budget about an hour, most of it waiting on downloads.

## What you need

- **A phone running Android 8.0 or newer** (`minSdk` is 26). Almost anything
  from 2017 onwards qualifies.
- **A USB cable that carries data.** Many charging cables are power-only and
  will leave the phone invisible to the computer with no error message. If the
  phone charges but never appears in Android Studio, suspect the cable first.
- **About 15 GB free disk** and a decent connection. Android Studio is ~1 GB,
  and the first build pulls another ~2 GB of SDK and libraries. Later builds
  reuse all of it and take seconds.

## 1. Install Android Studio

Download from https://developer.android.com/studio and install with the
defaults. Accept the SDK licences when asked.

You need **Ladybug (2024.2.1) or newer** — this project uses Android Gradle
Plugin 8.7.3, and older Studio versions refuse to open it. If yours is older,
*Help → Check for Updates*.

You do **not** need to install Java separately. Studio ships its own JDK 17,
which is what this project targets.

## 2. Get the code

In Android Studio's welcome window: **Get from VCS**.

- URL: `https://github.com/abdelbari/story`
- Pick any local directory, then **Clone**.

Sign in to GitHub if prompted.

Until the pull request is merged, the code lives on a branch. After cloning,
switch to it: bottom-right corner of Studio shows the current branch (`master`)
— click it, open **Remote → origin**, and pick

```
claude/android-pdf-word-converter-62huh6
```

then **Checkout**. Once the PR merges into `master`, skip this and stay on
`master`.

> Don't download the folder as a ZIP from the GitHub web page. The Android
> project depends on a sibling folder (`morpho/engine`) through a Gradle
> composite build, so it only works inside a full clone.

## 3. Open the right folder — this is the step people get wrong

Studio will offer to open the folder you just cloned. **Don't.** The repository
root is not an Android project, and opening it gives a confusing "no Gradle
project" state.

**File → Open**, then navigate into the clone and select:

```
morpho/android
```

That folder — the one containing `settings.gradle.kts` and `gradlew` — is the
project. Open it and choose **Trust Project**.

Layout, for orientation:

```
story/                    ← the clone; do NOT open this in Studio
└── morpho/
    ├── engine/           ← pure-Java conversion engine, pulled in automatically
    └── android/          ← OPEN THIS ONE
        ├── app/          ← the app itself
        ├── pdf/          ← PDF reading + OCR, with the language models
        └── core/design/  ← theme and colours
```

## 4. Let it sync

Studio starts a **Gradle sync** on its own — the progress bar at the bottom.
The first one downloads Gradle 8.14.3, the Android SDK 35 platform, and every
library. **Ten to twenty minutes is normal.** It is not stuck.

If it offers to install a missing SDK component, accept.

Wait for **"Gradle sync finished"** before doing anything else. The Run button
is unreliable until it does.

## 5. Turn on debugging on the phone

On the phone, once each:

1. **Settings → About phone**.
2. Tap **Build number** seven times. It counts down and says *You are now a
   developer*. (On Samsung: *About phone → Software information → Build
   number*.)
3. Back out to **Settings → System → Developer options**.
4. Turn on **USB debugging**.

## 6. Plug in and authorize

Connect the phone. It shows **"Allow USB debugging?"** with an RSA
fingerprint — tick *Always allow* and **Allow**.

If nothing appears: unlock the phone first (the prompt won't show on a locked
screen), pull the cable and reconnect, and check the USB notification is set to
**File transfer / MTP** rather than *Charging only*.

Your phone should now be named in the device dropdown in Studio's toolbar.

*Cable trouble?* Developer options also has **Wireless debugging** — enable it,
then in Studio use the device dropdown → *Pair using Wi-Fi* and scan the QR code.

## 7. Run

Pick your phone in the dropdown and press the green **▶ Run** button
(or `Ctrl+R` / `Cmd+R`).

The first build takes several minutes. Then the app installs and launches by
itself. It appears in your app drawer as **Morpho** and stays there — you can
open it later without the computer.

You may see **"Install anyway?"** about Play Protect on some phones. That's
normal for an app that didn't come from the Play Store.

## What to try first

`store/RELEASE-CHECKLIST.md` §1 has the full list in a deliberate order. The
four that matter most:

1. **A normal PDF → Word.** Do headings, lists and tables survive?
2. **An Arabic document → Word**, opened in real Word. Letters must stay joined,
   words must not reverse, and any Latin words or digits inside an Arabic
   paragraph must sit on the correct side.
3. **A scanned PDF.** The app offers OCR; watch the page counter and judge the
   recognition. This is the least predictable part of the app.
4. **Word → PDF**, both routes: *Convert to PDF* and *Print…*.

Report anything wrong in plain words — "the Arabic headings came out
backwards", "the table lost its second column" — and it can be traced from the
description.

## When something goes wrong

**"SDK location not found"** — *File → Settings → Languages & Frameworks →
Android SDK*, install **Android 15 (API 35)**, and sync again.

**Sync fails on `cz.adaptech.tesseract4android`** — that library comes from
JitPack, which is occasionally slow or briefly down. Retry the sync; it usually
resolves on the second attempt.

**"Device unauthorized"** — revoke and re-approve: *Developer options → Revoke
USB debugging authorizations*, then reconnect and accept the prompt.

**The build fails right after you switched branches** — *File → Sync Project
with Gradle Files*, then *Build → Clean Project*.

**Anything else** — copy the red text from the **Build** tab at the bottom.
The first error line is almost always the real one, and it is usually enough
to identify the problem exactly.
