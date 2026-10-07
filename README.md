# Quasar Player

An open sourced audio playing app (for android).

## Advantages against commercially available music playing apps
- Totally free
- You have your music, even if the world ends, and you survive with your phone, the music is still playable.
- High quality opus format support (uses Yt-dlp to download high bitrate opus format files from YouTube). Even though SoundCloud is downloadable, it does not support high-res audio without a subscription.
- Low energy usage.

## Drawbacks
- Literally violate several ToS of some platforms this app reaches.
- Requires manual download musics you want to listen to.

## Explanations
- It's *technically* pirated music from free sources (YouTube and SoundCloud).
- This *thing* support everything, even a whole 12 hours long video (just without the visual part).
- It supports high-res audio download from YouTube, though SoundCloud does not allow for high-res audio download, only at about 64kbs for Opus and 128kbs for MP3, so you should stick to YouTube download.

## How It Works

### Search
- Uses a small search engine built in Kotlin so it searches faster than pure Yt-dlp.
- The search engine is built on the restrictions of YouTube and SoundCloud rarely updates their formats (because of older smart TVs and the cost of updating the format for seemingly no reasons).

### Download
- Uses Yt-dlp
- The app has a built-in updater (though have not gotten the chance to test the updater) for Yt-dlp so it does not require manual reinstall when Yt-dlp requires an update.
- It downloads the audio file (prioritize opus format, but support mp3 and m4a) at highest bitrate possible, along the thumbnail and artist (just the channel/user that uploaded the video/music).
- Everything is saved in the QuasarPlayer folder (default is found at Music/QuasarPlayer at android root)

### Playback
- The music is loaded into memory when it is played
- Uses ExoPlayer on Android with the built-in low power DAC being chosen to decode the audio file for minimum energy usage

## Getting the Best Quality
- Stick to YouTube download, that's all

## Tech Stack
- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose
- **Engine:** Embedded `yt-dlp` binary managed via native process execution for stream extraction and metadata parsing.
- **Local Data:** File-based JSON caching for track metadata and `.m3u` format for playlists, interfacing directly with Android's Storage Access Framework (SAF).
- **Media Playback:** AndroidX Media3 (ExoPlayer) with audio offload enabled, delegating decoding to the hardware DSP to minimize CPU cycles.

## Installation / Build Instructions

### For Listeners
1. Go to the [Releases](https://github.com/Hyouka-Oshibi/QuasarPlayer/releases) page on this GitHub repository.
2. Download the latest `.apk` file to your phone.
3. Tap the file to install it. *(Note: Your phone might ask you to allow "Install from unknown sources" in settings. You will need to allow this to install apps outside the Google Play Store).*

### For Developers
Clone the repository, open the project in Android Studio, and sync Gradle. Build and deploy directly to a physical device or emulator.

## Contributing
Pull requests are accepted. Focus areas include extraction pipeline maintenance, Compose UI state optimizations, and `yt-dlp` synchronization. Please ensure modifications adhere to the existing Kotlin styling and maintain the strict low-overhead requirements for the playback service.

## License
This project is licensed under the GNU General Public License v3.0 (GPLv3). See the [LICENSE](LICENSE) file for more information.