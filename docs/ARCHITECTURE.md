# Android shell architecture

## Responsibilities

Android owns phone-specific capabilities:

- WebView lifecycle
- landscape/fullscreen presentation
- screen wake behavior
- runtime permissions
- cellular call observation
- later, optional notification-based VoIP detection

The remote web application owns expressions, reminders, and user-visible task
state. The Relay owns authentication, event deduplication, task policy, and
server-approved robot presets. Jetson owns navigation, manipulation, local
speech recognition, and safety enforcement.

## Event flow

For this first milestone, CallStateMonitor sends a local event to MainActivity,
which dispatches forestbridge:native-call into the WebView. No network call is
made.

The production flow will be:

1. Android detects an incoming call.
2. Android POSTs an idempotent device event to the Relay.
3. Relay applies task priority and safety policy.
4. Jetson claims an allowed approach task.
5. Jetson reports completion or assistance required.
6. The web UI renders the resulting state.

## Security choices

- Only the configured HTTPS origin can remain inside the WebView.
- File and content access are disabled.
- Mixed HTTP/HTTPS content is blocked.
- Third-party cookies and popup windows are disabled.
- No addJavascriptInterface bridge is exposed.
- Microphone requests are accepted only from the configured origin.
- Android never receives arbitrary motor coordinates from web content.
