# Google Play listing draft

**App name (≤30):** ShotSync: Screenshots to Mac

**Short description (≤80):**
Screenshots and screen recordings appear on your Mac in seconds. No cloud.

**Full description (≤4000):**

ShotSync sends every screenshot and screen recording you take on your
Android phone straight to a folder on your Mac, usually within 2 seconds
on the same Wi-Fi. No cloud, no account, no cables.

HOW IT WORKS
1. Install the free ShotSync receiver on your Mac (open source:
   github.com/arichazan/shotsync).
2. Open ShotSync on your phone and scan the pairing QR code your Mac shows.
3. Take a screenshot. It's already on your Mac.

WHY SHOTSYNC
• Direct transfer: files go from your phone to your Mac and nowhere else.
• Private by design: no servers, no account, no analytics, no ads.
• Secure: HTTPS (TLS 1.3) with certificate pinning to your own Mac, per-device
  access tokens you can revoke from either side, checksums on every file.
• Reliable: when your Mac is asleep or away, screenshots wait in a queue and
  are sent automatically when it is reachable again. Nothing is missed after
  a reboot.
• Screen recordings too: recordings are sent once the recorder has finished
  saving.
• Works away from home over Tailscale (optional).
• Wi-Fi-only mode for saving mobile data.
• Finds the screenshot folders used by Pixel, Samsung and most other phones, plus any folder you add.

PERMISSIONS
• Photos and videos: to detect new screenshots and screen recordings. Your
  media is only read, never changed or deleted.
• Camera: only to scan the pairing QR code.
• Notifications: a small ongoing notice while monitoring is on.

Requires a Mac running the ShotSync receiver (macOS 12 or later) on the same
network or Tailscale network.

Open source under the MIT license. Questions: support@itbcn.com

**Category:** Tools · **Tags:** File transfer, Productivity
**Contact email:** support@itbcn.com · **Website:** https://arichazan.github.io/apps/shotsync.html

## Graphics to produce (owner)
- App icon 512×512 PNG (32-bit, no alpha in Play's mask area)
- Feature graphic 1024×500
- ≥2 phone screenshots (16:9 or 9:16, 320–3840 px): setup checklist, home
  screen with recent transfers, the Mac folder with received screenshots
