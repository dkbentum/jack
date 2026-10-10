# Connecting Jack to a Google Sheet

Jack pushes every visit (check-in, and the same row again when the person checks out) to a Google Sheet.
It talks to a small **Google Apps Script** attached to your sheet, so nobody has to sign in to Google on the tablet.

Setup takes about 5 minutes and is done once, on a computer.

## 1. Create the sheet and the script

1. Create a new Google Sheet (e.g. "Jack Attendance").
2. In the sheet: **Extensions → Apps Script**.
3. Delete whatever is in `Code.gs` and paste the script below.
4. Change `SECRET` to a password of your own (letters and numbers, no spaces). You'll type the same secret into Jack.
5. Click **Save** (💾).

```javascript
// ===== Jack attendance receiver =====
const SECRET = 'change-me-to-your-own-secret';
const SHEET_NAME = 'Attendance';
const HEADER = ['Visit ID', 'Device ID', 'Device', 'Attendee ID', 'Name', 'Tag',
                'Check In', 'Check Out', 'Duration (min)', 'Status', 'Last Updated'];

function doPost(e) {
  try {
    const data = JSON.parse(e.postData.contents);
    if (data.secret !== SECRET) return reply({ ok: false, error: 'wrong secret' });

    const lock = LockService.getScriptLock();
    lock.waitLock(20000);
    try {
      const ss = SpreadsheetApp.getActiveSpreadsheet();
      const sheet = ss.getSheetByName(SHEET_NAME) || ss.insertSheet(SHEET_NAME);
      if (sheet.getLastRow() === 0) {
        sheet.appendRow(HEADER);
        sheet.setFrozenRows(1);
      }

      // Existing rows, keyed by "visitId|deviceId", so a check-out updates the check-in row.
      const lastRow = sheet.getLastRow();
      const keys = lastRow > 1
        ? sheet.getRange(2, 1, lastRow - 1, 2).getValues().map(r => r[0] + '|' + r[1])
        : [];

      let count = 0;
      (data.records || []).forEach(r => {
        const row = [r.visitId, data.deviceId, data.device, r.attendeeId, r.name, r.tag,
                     r.checkIn, r.checkOut, r.durationMin === null ? '' : r.durationMin,
                     r.status, new Date()];
        const key = r.visitId + '|' + data.deviceId;
        const i = keys.indexOf(key);
        if (i >= 0) {
          sheet.getRange(i + 2, 1, 1, row.length).setValues([row]);
        } else {
          sheet.appendRow(row);
          keys.push(key);
        }
        count++;
      });
      return reply({ ok: true, upserted: count });
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    return reply({ ok: false, error: String(err) });
  }
}

function doGet() {
  return reply({ ok: true, app: 'Jack attendance receiver' });
}

function reply(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
```

## 2. Deploy it as a web app

1. Click **Deploy → New deployment**.
2. Click the ⚙️ next to "Select type" → **Web app**.
3. Set:
   - **Execute as:** *Me*
   - **Who has access:** ***Anyone*** ← required, otherwise Jack gets a Google sign-in page instead of the sheet.
4. Click **Deploy**, then **Authorize access** and allow it (Google may warn that the app is unverified — it's your own script: *Advanced → Go to … (unsafe)*).
5. Copy the **Web app URL** (it ends in `/exec`).

> Security: the URL alone can't write to your sheet — every request must include the secret.
> Keep the secret private. To change it, edit `SECRET`, then **Deploy → Manage deployments → Edit → New version**.

## 3. Enter it in Jack

1. Jack → Settings (admin PIN) → **5. Attendance Export & Sync**.
2. Paste the **Web app URL** and type the **Secret**.
3. Tap **Save & Test** → should say "✅ Connected to your Google Sheet."
4. Turn on **Google Sheets sync**, then tap **Sync Now** to send everything recorded so far.

From then on Jack syncs automatically a few seconds after each check-in/out, and every 15 minutes.
If the tablet is offline, visits wait and are sent when it's back online.

## What the sheet looks like

| Visit ID | Device ID | Device | Attendee ID | Name | Tag | Check In | Check Out | Duration (min) | Status | Last Updated |
|---|---|---|---|---|---|---|---|---|---|---|
| 12 | a1b2c3d4 | Samsung SM-T295 | 007 | Ada Obi | Worker | 2026-10-10 09:00:00 | 2026-10-10 11:30:59 | 150 | Checked out | … |
| 13 | a1b2c3d4 | Samsung SM-T295 | 42 | Tunde | Subscriber | 2026-10-10 10:00:00 |  |  | In building | … |

Several Jack tablets can share one sheet — rows are kept apart by Device ID.

## Troubleshooting

| Jack says | Fix |
|---|---|
| "wrong secret" | Secret in Jack ≠ `SECRET` in the script (case-sensitive). |
| "unexpected reply — check the web app is deployed with access "Anyone"" | Redeploy with **Who has access: Anyone**. |
| "HTTP 404" | URL copied wrong, or the deployment was deleted. Use the `/exec` URL. |
| Script edits don't take effect | **Deploy → Manage deployments → Edit → Version: New version → Deploy.** |
