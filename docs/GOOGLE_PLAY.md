# Google Play

The Android app goes to Google Play from the same `desktop-release` workflow that builds the APK
and AAB for the GitHub release.

## How a release gets there

1. The `android` job builds the signed APK and AAB (it needs the four `ANDROID_KEYSTORE_*`
   secrets, see [INSTALL.md](INSTALL.md#android-release-key)). It then runs
   `.github/scripts/check-android-build.sh`, which fails the build if it is debuggable, targets an
   SDK below Play's minimum (36 today), has 32-bit native code without 64-bit, or isn't ready for
   16 KB memory pages.
2. The `play` job uploads the AAB to a Play track with
   [r0adkll/upload-google-play](https://github.com/r0adkll/upload-google-play) (pinned to a commit),
   along with the R8 mapping so Play's crash reports are readable.
   - A version tag sends it to **internal** testing, rolled out at once.
   - A run by hand picks the track (`internal`, `alpha`, `beta`, `production`, or `none`), the
     status (`completed`, or `draft` to finish it in the Play Console) and the release notes
     (500 characters at most, English UK).
3. Moving a build from internal testing to production is done in the Play Console
   (**Test and release**, the track, **Promote release**), or by running the workflow by hand
   with the next version and `production`.

Play accepts each version code once. The code comes from `agents.version` (1.2.3 is 1002003), so
a second upload of the same version fails: bump the version instead.

## Secret

| Secret | What it is |
| --- | --- |
| `PLAY_SERVICE_ACCOUNT_JSON` | The JSON key of a Google Cloud service account that has release rights on the app in the Play Console (Users and permissions). |

Without it the `play` job says so and uploads nothing; the rest of the release is unaffected.

## Things Play needs that the code already does

- Play App Signing: Google holds the key that signs what phones install. The `ANDROID_KEYSTORE_*`
  key is the upload key.
- A copy installed from Google Play has no link to signing up or to buying the Team plan
  (`StoreRules.kt`): Play's payments policy allows selling a service only through Play's billing.
  A copy from the GitHub release links to the website as the desktop app does.
- The very first upload of a new app has to be made by hand in the Play Console; the API can only
  add releases to an app that already has one.
- Until some release has been rolled out (the first, manual one does it), Play only takes `draft`
  releases from the API. Run by hand with status `draft` if that happens.
