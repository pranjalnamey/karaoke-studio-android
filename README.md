# Karaoke Studio Android v1

Native Android wrapper for the working Karaoke Studio PC + Mobile engine.

## Features
- Standalone Android app shell (no Chrome UI)
- Remembers the PC address
- Native Android audio file picker
- Uses the existing pairing-code flow served by the PC engine
- Keeps the tested upload, live worker status, cancellation, preview and MP3 generation workflow
- Intercepts MP3 downloads and saves them to Android Downloads
- Settings gear lets the user change the PC address

## Connection
1. Run `KaraokeStudio_PC_Mobile_v1.exe` on Windows.
2. Keep PC and Android device on the same Wi-Fi.
3. Open the Android app.
4. Enter the PC IP shown in the Windows app, e.g. `192.168.1.15`.
5. Enter the pairing code when the mobile UI asks for it.

## Build
The repository includes `.github/workflows/build-apk.yml`.
A GitHub Actions run produces `app-debug.apk` as the artifact `KaraokeStudio-Android-v1`.

Target SDK: 35
Minimum Android: 10 (API 29)


Build trigger: Android APK CI configured.
