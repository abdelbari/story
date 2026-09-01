# Play Data safety form — answers

Play requires this declaration and audits it against the app's behaviour. Every
answer below follows from one fact: **the app holds no `INTERNET` permission**,
which CI verifies against the merged manifest of the shipped bundle on every
push. There is no way for it to collect or share anything.

## Data collection and sharing

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | N/A — no data is transmitted |
| Do you provide a way for users to request that their data be deleted? | N/A — no data is collected or retained |

Leave **every** data category unchecked: no location, no personal info, no
financial info, no health, no messages, no photos or videos, no audio, no
files or docs, no calendar, no contacts, no app activity, no web browsing, no
app info and performance (this includes crash logs — the app has none), and no
device or other IDs.

### Why "Files and docs" is still No

The app opens the document you pick and writes the result where you choose.
Play's definition of *collection* is transmitting data off the device or
storing it for the developer's access. Morpho does neither: it holds no copy,
keeps no library, and has no network permission with which to send anything.

## Security practices

- Data is not encrypted in transit because no data is transmitted.
- Committed to Play Families Policy: not applicable (app is not aimed at
  children, though it collects nothing from anyone).
- Independent security review: none claimed.

## Sensitive permissions

None. The app declares no runtime permissions and no network permission. File
access is through the Storage Access Framework, which grants access to the
single file the user picks — no storage permission is requested or held.

## Ads

None. The app contains no advertising SDK.
