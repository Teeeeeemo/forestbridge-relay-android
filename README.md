# ForestBridge Relay Android

A small native Android shell for the ForestBridge companion-robot display.

## First milestone

- Loads the isolated companion-face UI from https://robot.wichai.xyz/companion/index.html
- Locks the display to landscape and immersive fullscreen
- Keeps the screen awake while the app is running
- Shows an offline screen with retry
- Allows microphone access only for the configured HTTPS origin
- Observes incoming cellular call phases after READ_PHONE_STATE is granted
- Detects likely WeChat audio/video call notifications after notification access is enabled
- Emits local WebView events named forestbridge:native-call
- Does not send call events to the Relay or move the robot yet

## Open in Android Studio

1. Fetch and switch to the codex/android-shell branch.
2. Open this repository in Android Studio.
3. Let Gradle sync install Android SDK 36 if it is missing.
4. Connect a physical Android phone and run the app module.
5. Grant Phone permission. Grant Microphone only when the web UI asks for it.
6. On the phone, open Settings > Apps > Special app access > Notification access,
   then enable ForestBridge Relay. No in-app setup screen is included.

The project uses Android Gradle Plugin 9.0.0, Gradle 9.1.0, JDK 17,
minimum API 26, and target/compile API 36.

## Change the remote page

Edit WEB_APP_URL in app/build.gradle.kts. The default points to the isolated /companion/index.html page; the legacy site at / remains unchanged. Release builds reject cleartext HTTP.

## Native call event

The app dispatches this event into the trusted WebView origin:

    window.addEventListener("forestbridge:native-call", event => {
      console.log(event.detail.source)
      console.log(event.detail.phase)
    })

The phases are ringing, active, and ended. WeChat events use source wechat and
may include kind audio or video. The detector uses notification category and
conservative text matching because WeChat does not expose a stable public call
API. Debug builds display a short toast for detected events.

## Safety boundary

This milestone intentionally does not post motor commands or navigation
coordinates. A later Relay endpoint will accept an idempotent phone event and
map it to a server-owned, safety-reviewed task preset.
