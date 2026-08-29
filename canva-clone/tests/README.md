# Tests

End-to-end smoke suite driving the real app in headless Chromium.

```bash
# 1. serve the app
npx http-server canva-clone -p 8321 -s &

# 2. run (needs playwright + a chromium; set CHROMIUM_PATH to point at one)
node canva-clone/tests/smoke.mjs
```

Environment: `BASE_URL` (default http://127.0.0.1:8321/index.html),
`CHROMIUM_PATH`, `SHOTS_DIR` (screenshots + export artifacts).
