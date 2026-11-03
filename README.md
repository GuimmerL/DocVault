<p align="center">
  <img src="docs/docvault-app-icon.svg" alt="DocVault app icon" width="108" />
</p>

<h1 align="center">DocVault</h1>

<p align="center">
  <a href="https://buymeacoffee.com/ekqindmb4"><img src="https://www.buymeacoffee.com/assets/img/custom_images/orange_img.png" alt="Buy Me a Coffee" width="217" /></a>
</p>

If DocVault is useful, [buy me a coffee](https://buymeacoffee.com/ekqindmb4) to support development.

DocVault is an Android app for keeping, organizing, and searching PDF manuals and other reference documents on your device. Import PDFs or download them in the built-in browser, then browse and search your local library offline.

## Features

- Organize PDFs into built-in or custom categories and subfolders.
- Add tags and search by title, filename, category, tags, or extracted PDF text. Full-text matches include page references.
- Browse to a PDF online, download it into a category, or import a PDF from your device.
- Read PDFs in the standard viewer or try the optional experimental AndroidX viewer.
- Choose list, compact-list, or thumbnail-grid layouts and light, dark, or system theme.
- Optionally password-protect a category's PDF files.

## Get DocVault

See [GitHub Releases](https://github.com/GuimmerL/DocVault/releases) for available builds. The current `v1.18.1` APK is a **debug/test build**, not a production-signed release. It has been retained unchanged and was not rebuilt after the source cleanup; its UI and app icon may differ from the current source.

## Build from source

Requirements:

- Android Studio with Android SDK Platform 36 installed
- JDK 17
- Android Gradle Plugin 8.10.1 and a compatible Gradle 8.11.1+ installation

There is no Gradle wrapper in this repository. Open the project directory in Android Studio and sync Gradle, or with a compatible Gradle installation run:

```bash
gradle assembleDebug
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Storage and privacy

DocVault is local-first: it has no account, app-operated cloud sync, or server component. Its browser, search engine, and document-download functions do use the network:

- The in-app browser loads websites, and a plain-text search entered there is sent to Google. Website operators may receive the requests, cookies, and other normal browser data needed to load or download content.
- PDFs downloaded from a website are stored locally. Importing or sharing a PDF may expose it to the selected file provider or receiving app.
- By default, the library stores PDFs under `Documents/DocVault` when available. The app also saves a metadata file there; it can contain document names, titles, tags, source URLs, and short text excerpts. Extracted page-text indexes are stored in the app's private storage.
- Android's manifest requests broad storage access (`MANAGE_EXTERNAL_STORAGE`), enables app-data backup, and permits cleartext traffic. Android may require special all-files-access approval. HTTP pages or downloads are not encrypted in transit; use HTTPS. Review these settings before distributing through an app store or handling sensitive material.

### Password-protected categories

Protected-category PDF files are encrypted using a key derived with PBKDF2-HMAC-SHA-256 and AES-256-GCM. This does **not** encrypt every related item: document metadata and some extracted text may remain outside the encrypted PDF files, and PDFs are decrypted into the app cache while a category is unlocked. Android backup behavior may also affect private app data. Choose a strong, unique password and keep a separate backup; forgotten passwords cannot recover the files. This feature is not a substitute for full-device encryption or a security-reviewed vault.

## License and document rights

The original DocVault source code is licensed under [Apache License 2.0](LICENSE). This license does not grant rights to third-party PDFs, manuals, catalogs, trademarks, or other content imported or accessed with the app. Users are responsible for obtaining and respecting the rights to documents they store or share.
