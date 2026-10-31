# Google Play Data Safety form: answers for ShotSync

Play Console → App content → Data safety. Answers reflect the code on the
`production-ready` branch. Re-check them whenever an SDK or network call is added.

## Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | (shown only if "Yes" above) **Yes**: TLS 1.3 + certificate pinning |
| Do you provide a way for users to request that their data is deleted? | (shown only if "Yes") N/A, no data reaches the developer |

**Why we answer "No":** Google defines *collection* as data transmitted
off the device by the app. ShotSync transmits screenshots/recordings, file
names, a user-chosen device name and a random device ID **only to the
user's own Mac**, a device the user controls and paired by QR code, over TLS
that terminates on that Mac. Only the sender and the recipient can read it.
There is no developer server and no analytics, ads or crash SDK, so neither
the developer nor any third party can access the data. That fits Google's
end-to-end-encrypted / user-controlled-destination case. It is a judgement
call, though. Re-read the current Help Center article ("Provide information
for Google Play's Data safety section") before submitting.

If Google support or review disagrees, use this conservative fallback:

| Data type | Collected | Shared | Optional? | Purpose | Ephemeral? |
|---|---|---|---|---|---|
| Photos and videos → Photos | Yes | No | Required (core function) | App functionality | No (stored on the user's Mac) |
| Photos and videos → Videos | Yes | No | Required | App functionality | No |
| Device or other IDs | Yes (random app-generated ID) | No | Required | App functionality | No |

…with "encrypted in transit: Yes" and "users can request deletion: Yes
(unpair / delete files on the Mac; contact support@itbcn.com)".

## Other declarations (App content page)

| Item | Answer |
|---|---|
| Privacy policy URL | https://arichazan.github.io/apps/shotsync.html (publish PRIVACY.md content there or link `PRIVACY.md` on GitHub) |
| Ads | No ads |
| App access | All functionality needs a paired Mac. Provide reviewers with instructions: "Install the free receiver from github.com/arichazan/shotsync (`shotsync install`), click Show Pairing QR Code, scan it in the app." Also attach a short screen recording of the pairing |
| Content rating | IARC questionnaire: Utility, no user-generated content shared, no violence, etc. → expected "Everyone / PEGI 3" |
| Target audience | 18+ (or 13+); not designed for children |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Data deletion URL | Not required (no account, no collected data) |

## Permission declarations

### Photo and video permissions (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO)
Play allows broad media access only when it is core functionality. Declaration text:

> ShotSync's only function is to automatically copy every new screenshot and
> screen recording to the user's own computer as soon as it is taken. This
> requires continuous, background read access to the device's screenshot and
> screen-recording folders. The one-time Android photo picker cannot detect
> new screenshots in the background, so it cannot provide this function. The
> app only reads; it never modifies or deletes media and never uploads it
> anywhere other than the user's paired computer.

### Foreground service: `specialUse`
Play Console → App content → Foreground service permissions → tick
*Special use*. Description:

> A user-enabled, persistent foreground service watches MediaStore for new
> screenshots and screen recordings and immediately transfers them to the
> user's paired personal computer over the local network or the user's VPN.
> The user starts and stops it from the app's main screen; a persistent
> notification is always shown. No other FGS type fits: dataSync has a
> 6-hour daily limit on Android 15+ and is not allowed to start after boot,
> while monitoring must run for as long as the user keeps it enabled.

Attach a ≤30 s video: enable monitoring → notification visible → take a
screenshot → it appears on the Mac.

### Camera
Used only to scan the pairing QR code (on-device). No declaration form, but
mention it in the review notes.
