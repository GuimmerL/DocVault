<p align="center">
  <img src="docs/docvault-app-icon.svg" alt="DocVault app icon" width="108" />
</p>

<h1 align="center">DocVault</h1>

<p align="center">
  <a href="https://buymeacoffee.com/ekqindmbd4"><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me a Coffee" width="217" /></a>
</p>

If DocVault is useful, [buy me a coffee](https://buymeacoffee.com/ekqindmbd4) to support development.

DocVault is an Android app for keeping, organizing, and searching PDF manuals and other reference documents on your device. Import PDFs or download them in the built-in browser, then browse and search your local library offline.

## Features

- Organize PDFs into built-in or custom categories and subfolders.
- Add tags and search by title, filename, category, tags, or extracted PDF text. Full-text matches include page references.
- Browse to a PDF online, download it into a category, or import a PDF from your device.
- Read PDFs in the standard viewer or try the optional experimental AndroidX viewer.
- Choose list, compact-list, or thumbnail-grid layouts and light, dark, or system theme.
- Optionally password-protect a category's PDF files.

## Get DocVault

See [GitHub Releases](https://github.com/GuimmerL/DocVault/releases) for available builds. The current `v1.18.1` APK is an old **debug/test build**, not a production-signed release. It predates source cleanup and does not match the current source. Do not install it if you need the current privacy/security changes; a new debug prerelease is planned after verification.

## Build from source

Requirements:

- Android Studio with Android SDK Platform 36 and Extension 19 installed
- JDK 17

Use the included Gradle wrapper:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Storage and privacy

DocVault is local-first: it has no account, app-operated cloud sync, or server component. Its browser, search engine, and document-download functions do use the network:

- The in-app browser loads websites, and a plain-text search entered there is sent to Google. Website operators may receive the requests, cookies, and other normal browser data needed to load or download content.
- PDFs downloaded from a website are stored locally. Importing or sharing a PDF may expose it to the selected file provider or receiving app.
- The working library and metadata are stored in app-private storage. Choose a folder through the Android folder picker to import PDFs from it or export unprotected PDFs back to it; no broad all-files access is requested. The selected folder provider has access to the items you choose to import/export.
- App-data backup is disabled. Browser traffic uses HTTPS; plain HTTP URLs and PDF-download redirects to HTTP are refused. Website operators still receive the requests and normal browser data needed to load content.

### Password-protected categories

Protected-category PDFs, category metadata, and extracted search indexes are encrypted with AES-256-GCM using a PBKDF2-HMAC-SHA-256-derived key. Decrypted PDFs are held temporarily in the app cache while unlocked; the cache is cleared on lock, app restart, or when the app is sent to the background. Locking on background can interrupt viewing. Choose a strong, unique password and keep a separate backup; forgotten passwords cannot recover the files. This feature is not a substitute for full-device encryption or a security-reviewed vault. Explicitly exporting a PDF sends a copy to the selected destination; protected categories are excluded from folder export.

## License and document rights

The original DocVault source code is licensed under [Apache License 2.0](LICENSE). This license does not grant rights to third-party PDFs, manuals, catalogs, trademarks, or other content imported or accessed with the app. Users are responsible for obtaining and respecting the rights to documents they store or share.
