# Stremio Channels

Stremio Channels is an Android TV app that publishes TMDB-backed home screen channels through the Android TV Preview Channel APIs. It is designed for TV launchers that read Android TV channels, including Projectivy Launcher.

The app can publish built-in movie and TV channels such as popular movies, trending TV, top rated movies, and genre-based feeds. It also includes a TV-remote-friendly channel editor for creating custom TMDB channels by combining:

- Section: Popular, Top Rated, Trending Today, Trending This Week, Upcoming, or Now Playing
- Media type: Movie or TV
- One or more TMDB genre filters

Clicking a movie or TV card opens a lightweight launch screen and then sends a Stremio search deep link for the selected title. The app does not attempt direct playback.

## Requirements

- Android Studio or Android Gradle tooling
- Android SDK installed
- JDK available through Android Studio or your environment
- TMDB API Read Access Token

Minimum Android API is 26.

## Local Configuration

Create or update `local.properties` in the project root:

```properties
TMDB_TOKEN=your_tmdb_read_access_token_here
```

Use the TMDB API Read Access Token as a Bearer token. Do not use the older `api_key` query parameter.

`local.properties` is ignored by git and should not be committed.

## Build Debug

From the project root:

```powershell
cd C:\Data\StremioChannels
.\gradlew.bat :app:assembleDebug
```

The debug APK will be created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Install Debug

With an Android TV device connected through ADB:

```powershell
.\gradlew.bat :app:installDebug
```

Or install the APK directly:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Launch the app once after installing. This opens the custom channel editor and schedules channel refresh work.

## Logs

Useful log tag:

```text
StremioChannels
```

Example:

```powershell
adb logcat -s StremioChannels
```

Watch for channel config loading, TMDB refresh, PreviewChannel publishing, PreviewProgram counts, and Stremio launch messages.

## Notes

- Built-in channels are defined in `app/src/main/assets/channels.json`.
- Custom channels are stored locally on the device.
- Background refresh uses WorkManager and also runs after device boot.
- Some launchers may cache channel rows briefly after changes.
