# Closed testing requirement (new personal developer accounts)

Google requires **personal** Play developer accounts created after
13 Nov 2023 to run a **closed test with at least 12 testers who stay opted in
for at least 14 consecutive days** before they can apply for production
access. (Organization accounts, which need a D-U-N-S number, are exempt.)
Check Play Console → Dashboard for the current numbers; Google has changed
them before (it was 20 testers until late 2024).

What counts:
- Testers must **opt in** through the test link *and* stay opted in. Someone
  who opts out on day 10 breaks the 14-day streak for that tester.
- Installing the app matters for the engagement questions Google asks
  afterwards. Ask testers to install it and actually use it at least a few times.
- The 14 days start when the 12th tester is opted in, not when you publish.

## How to run it

1. **Upload a signed AAB.** Create the upload key (see PRODUCTION_READINESS.md),
   add the four `SHOTSYNC_*` secrets, and download `app-release.aab` from the
   `android` workflow artifact. Play App Signing: accept the Google-managed
   app signing key.
2. Play Console → *Test and release → Testing → Closed testing* → *Create
   track* (or use "Alpha") → *Create new release* → upload the AAB → release
   notes → *Save* → *Review release* → *Start rollout to Closed testing*.
3. *Testers* tab → *Create email list* with ≥12 (aim for 15–20 for margin)
   Google-account emails, or use a Google Group (`shotsync-testers@googlegroups.com`).
   Countries: add at least your own.
4. Copy the **opt-in URL** and send it to testers with these instructions:
   - open the link on the Android phone signed into that Google account →
     *Become a tester* → *Download it on Google Play*;
   - install the Mac receiver (or pair with a friend's Mac for the test);
   - keep the app installed and **do not leave the programme for 14 days**;
   - send feedback to support@itbcn.com.
5. Ship at least one more build during the 14 days (fix feedback). Google
   looks favourably on iteration.
6. After 14 days with ≥12 opted-in testers, Dashboard → *Apply for
   production*. Answer the questionnaire (how you recruited testers, what
   feedback you got, what you changed, why the app is ready). Review takes
   up to ~7 days.
7. Then create the production release (start with a staged rollout, e.g. 20%).

Tip: Mac users with Android phones are a small group; recruit from
colleagues, Android/macOS communities and friends early, before the build is
final, because the 14-day clock only starts once you have 12.
