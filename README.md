# GLOBIX TV

GLOBIX TV is an Android live-TV playlist player with an Arabic-first interface.

## Features
- Loads public IPTV-org playlists for country, Arabic-region, and Arabic-language channel discovery.
- Channel search, country filters, categories, playback controls, and fullscreen video.
- Caches the most recently loaded playlist for quicker reopening.
- Android TV launcher entry point.

## Build
Open this project in Android Studio with JDK 17 and Android Gradle Plugin 8.13.2, then build the app module.

## Important notes
- Channel availability depends on each upstream stream and may change without notice.
- Use only streams you are authorized to access.
- The app currently references hls.js from a CDN at runtime; this must be bundled locally (or replaced with a fully open-source native player dependency) before an F-Droid submission.
- F-Droid inclusion is not guaranteed; the project must pass its current metadata, licensing, dependency, and reproducible-build review.
