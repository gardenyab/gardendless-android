# Gardendless for Android

An unofficial Android port of the web version of **Plants vs. Zombies 2: Gardendless**. The game runs locally in the built-in WebView; game files are installed separately from the APK using a ZIP archive.

## Features

- Material 3 Expressive launcher with portrait and landscape layouts.
- Runs the game in landscape orientation.
- Optional auto-launch from launcher settings; use the in-game Home button to return to the launcher.
- Imports saves using the system file picker.
- Supports Android Auto Backup for game WebView storage and launcher preferences.
- Maps one-finger taps and drags to game mouse input; two-finger swipes scroll and two-finger taps send a right-click.
- Supports GP-Next and local storage for its data.

## Installing the game

1. Tap **"Download ZIP from Telegram"** and download the game archive from [our channel](https://t.me/pvz2gardendless).
2. Return to the app and tap **"Select game ZIP"**.
3. Find the downloaded file and wait for it to be extracted. Once installed, tap **"Launch"**.

The archive must contain `index.html` and the game resources. For example, `index.html` can be in the archive root or in `docs/index.html`. Installed files are preserved between app launches.

To update, select **"Update game ZIP"** and choose the new archive.

## Android cloud backup

When Android backup is enabled for the device, the launcher periodically saves the game's WebView local storage into app data. Android Auto Backup can upload that snapshot and launcher preferences to the user's Google account and restore them during device setup or app reinstall.

The installed game files and GP-Next files are not included. Reinstall the app, restore Android's app backup, then import the game ZIP again. Android backup is subject to the system's per-app 25 MB quota and backup schedule; it is not an immediate, continuous sync.

## Preparing a ZIP from the Docker image

The app does not run Docker or download the image itself. To manually extract the files on a computer with Docker:

```powershell
docker pull ghcr.io/gzh0821/pvzge_web:latest
docker create --name pvzge-temp ghcr.io/gzh0821/pvzge_web:latest
docker cp pvzge-temp:/usr/share/nginx/html .\gardendless-game
docker rm pvzge-temp
```

Create a ZIP from the `gardendless-game` folder and import it into the game.

## Building

Android Studio, Android SDK 36, and JDK 21 are required. You do not need to add the game ZIP to the project or APK.

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

## Sources and license

- Original web game: [Gzh0821/pvzge_web](https://github.com/Gzh0821/pvzge_web).
- Telegram channel with ZIP archives: [pvz2gardendless](https://t.me/pvz2gardendless).
- The app is distributed under the GNU GPL v3; the license text is in [LICENSE](LICENSE).
