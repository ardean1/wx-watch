# wx-watch

> **This is an untested debug build.** It hasn't been run on a real watch yet.

Wear OS (Kotlin + Compose for Wear OS) port of marion-wx-map for the Galaxy Watch Ultra (round 480 px).
Shows a dark Esri basemap, the IEM NEXRAD n0q radar, and live ADS-B aircraft from adsb.lol, all free with no API keys (the same feeds marion-wx-map uses).
Aircraft are fetched directly over HTTPS with no proxy. Trail segments that fall off the visible disc are not drawn. The center is marked with a box.

- **Location:** it gets your location once each time the app opens, using FusedLocation (watch GPS, or the paired phone's location). If that fails it uses the last known location, then the saved ZIP (looked up with zippopotam.us), then Marion, VA. It asks for location permission on first run.
- **Tap:** opens options. You can turn planes on or off, cycle the radius (5/10/25/50/100/200 mi), cycle the refresh interval (30/45/60 s), set the fallback ZIP, and re-locate.
- **Bezel / rotary:** zooms through the radius steps.
- **Refresh:** runs only while the app is in the foreground (the loop is tied to `repeatOnLifecycle(STARTED)`).
- **Launch from the watch face:** add the "wx-watch" tile, or the "wx-watch" complication (short text, icon, or small image). Tapping either opens the app.

## Build
    export JAVA_HOME=/workspace/tools/jdk/jdk-17.0.20.1+1
    ./gradlew assembleDebug   # -> app/build/outputs/apk/debug/app-debug.apk

## Install from your phone (no PC)
1. On your phone, open the [Releases page](https://github.com/ardean1/wx-watch/releases) and download `wx-watch-0.1.0-debug.apk`.
2. Turn on Developer options on the watch: Settings > About watch > Software info > tap **Software version** 5 times.
3. Go to Settings > Developer options. Turn on **ADB debugging** and **Wireless debugging**. The phone and watch must be on the same Wi-Fi.
4. On the phone, install **Wear Installer 2** or **Bugjaeger** from the Play Store.
5. On the watch, go to Wireless debugging > **Pair new device**. In the phone app, enter the watch IP, pairing port and 6-digit code. Then connect to the IP:port shown on the main Wireless debugging screen.
6. In the app, pick the downloaded APK and install it to the watch.

## Sideload from a PC with adb (Wi-Fi)
1. Turn on Developer options on the watch: Settings > About watch > Software information > tap **Software version** 5 times.
2. Go to Settings > Developer options. Turn on **ADB debugging**, then **Wireless debugging**. The watch and PC must be on the same Wi-Fi.
3. In Wireless debugging, tap **Pair new device**. Note the IP:port and the 6-digit code, then on the PC run:
       adb pair <watch-ip>:<pair-port>      # enter the code
4. Connect using the IP:port shown on the main Wireless debugging screen. This port is different from the pairing port.
       adb connect <watch-ip>:<port>
       adb devices
5. Install:
       adb -s <watch-ip>:<port> install -r app-debug.apk
6. Open wx-watch from the app list and allow location. Then long-press the watch face to add the complication, or add the tile.
