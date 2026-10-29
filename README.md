# WipeFiles (modernized)

Original app by peterhearty: https://github.com/peterhearty/WipeFiles
Modernized: no permissions, SAF-based, targetSdk 35.

## Build
Push to GitHub — the included workflow builds the APK automatically.
Go to Actions → latest run → Artifacts → WipeFiles-debug-apk.

Or open the project in Android Studio and use Build → Build APK(s).

## Install
Transfer the APK to your phone and tap it. Allow installation from
unknown sources when prompted. The app requests no permissions.

## Note
On flash storage, overwriting reduces but cannot guarantee
recovery-proof erasure (wear-leveling). Best-effort only.
Modern Android's file-based encryption makes deleted data
mostly unrecoverable anyway.
