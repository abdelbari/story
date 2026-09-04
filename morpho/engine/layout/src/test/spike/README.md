# The editor's page, driven in a browser

`editor-spike.mjs` opens the editor page in headless Chromium — Blink, which
is what Android's WebView is — and works it the way a reader would: types
Latin and Arabic, presses Return and Backspace, selects across paragraphs
and makes them bold, undoes, redoes, clicks a table, puts one in, makes a
list. After every action it asks the page for its text and caret and the
engine for its document and selection, and they must agree exactly.

It is run by `EditorPageTest` when `node` and Playwright are to hand, and
skipped otherwise — so it runs on a machine set up for it and not in CI,
which has neither. To set a machine up:

    npm install -g playwright && npx playwright install chromium
    ln -s "$(npm root -g)/playwright" node_modules/playwright   # in this directory

The Kotlin test serves the page and the engine over a loopback socket and
starts the script; the script talks to the engine through that socket, so
the time it measures per keystroke is the time of the socket and of the
browser's own IPC, not of the bridge the app will use, which is a call.
