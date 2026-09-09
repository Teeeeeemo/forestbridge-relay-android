# ForestBridge Relay Android

A small native Android shell for the ForestBridge companion-robot display.

## First milestone

- Loads the remote robot UI from https://robot.wichai.xyz/
- Locks the display to landscape and immersive fullscreen
- Keeps the screen awake while the app is running
- Shows an offline screen with retry
- Allows microphone access only for the configured HTTPS origin
- Observes incoming cellular call phases after READ_PHONE_STATE is granted
- Emits local WebView events named forestbridge:native-call
- Does not send call events to the Relay or move the robot yet

## Open in Android Studio

1. Fetch and switch to the codex/android-shell branch.
2. Open this repository in Android Studio.
3. Let Gradle sync install Android SDK 36 if it is missing.
4. Connect a physical Android phone and run the app module.
5. Grant Phone permission. Grant Microphone only when the web UI asks for it.

The project uses Android Gradle Plugin 9.3.0, Gradle 9.5.0, JDK 17,
minimum API 26, and target/compile API 36.

## Change the remote page

Edit WEB_APP_URL in app/build.gradle.kts. Release builds reject cleartext HTTP.

## Native call event

The app dispatches this event into the trusted WebView origin:

    window.addEventListener("forestbridge:native-call", event => {
      console.log(event.detail.source)
      console.log(event.detail.phase)
    })

The phases are ringing, active, and ended. Outgoing calls are ignored. Debug
builds also display a short toast so the cellular-call listener can be tested
before the server event endpoint exists.

## Safety boundary

This milestone intentionally does not post motor commands or navigation
coordinates. A later Relay endpoint will accept an idempotent phone event and
map it to a server-owned, safety-reviewed task preset.
