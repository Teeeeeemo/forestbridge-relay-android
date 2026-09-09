# Jetson Relay integration

This document is the source of truth for Android-to-Relay task commands and
Jetson task feedback.

## Scope

The Android app hosts the trusted companion WebView. The companion UI asks a
small native bridge to call the Relay over HTTPS. The native layer owns the UI
token, performs only allow-listed operations, and returns structured results to
the page. The UI never receives or displays the token.

This integration does not connect Android directly to ROS, serial devices,
motors, or Jetson-local ports. The public Relay remains the only transport and
the Jetson worker remains the only task executor.

## Authentication and configuration

All Relay calls use:

```http
Authorization: Bearer <UI_TOKEN>
```

The token must not be committed. Configure it on the development machine in
`~/.gradle/gradle.properties`:

```properties
FORESTBRIDGE_UI_TOKEN=replace-with-the-existing-ui-token
```

The build reads that property into a native-only BuildConfig value. An empty
value is a configuration error: the bridge must return an error and must not
send an unauthenticated command.

The Relay origin is derived from `WEB_APP_URL` and must be HTTPS. The bridge
does not accept an arbitrary URL from JavaScript.

## Relay contract

### Read total state

```http
GET /api/state
Authorization: Bearer <UI_TOKEN>
```

Expected top-level fields:

```json
{
  "tasks": [],
  "robots": {
    "jetson-dry-run": {
      "robot_id": "jetson-dry-run",
      "last_seen": "2026-09-08T08:01:20+00:00",
      "status": "busy",
      "current_task_id": "task-id"
    }
  },
  "monitor": {}
}
```

There is no `data` envelope around the Relay response.

### Read one task

```http
GET /api/tasks/{task_id}
Authorization: Bearer <UI_TOKEN>
```

The response is the task object itself.

### Create a task

```http
POST /api/tasks
Authorization: Bearer <UI_TOKEN>
Content-Type: application/json
Idempotency-Key: <request-uuid>
```

```json
{
  "task_type": "navigate_then_pick_place",
  "preset": "table_pick_place_01",
  "request_text": "前往桌边抓取物品"
}
```

Android accepts only known presets and generates a new idempotency key for each
user request. Motion coordinates and actuator parameters must never be accepted
from the page; those remain server-owned preset values.

### Request stop

```http
POST /api/tasks/{task_id}/stop
Authorization: Bearer <UI_TOKEN>
Content-Type: application/json
```

A successful HTTP response means the stop was requested. The UI must wait for
`task.status == "stopped"` before reporting that Jetson confirmed the stop.

## State model

Coarse task statuses:

```text
queued
assigned
running
complete
failed
needs_assistance
stopped
expired
```

Terminal task statuses:

```text
complete
failed
needs_assistance
stopped
expired
```

Detailed `current_state` values:

```text
queued
assigned
precheck
set_mapping_camera
localizing
planning
navigating
verifying_dock
set_grasp_camera
grasping
verifying_result
complete
failed
needs_assistance
stopped
expired
```

Jetson event names can include:

```text
task_started
state_started
state_finished
state_retrying
task_completed
task_failed
task_needs_assistance
task_stopped
```

A `state_finished` event with `outcome: "success"` means only that one state
completed. It must not be treated as whole-task completion.

## Client decisions

Robot online is derived, not stored:

```javascript
const jetsonOnline =
  robot &&
  Date.now() - new Date(robot.last_seen).getTime() < 6000;
```

Robot ready requires all of:

```javascript
const jetsonReady =
  jetsonOnline &&
  robot.status === "idle" &&
  robot.current_task_id === null;
```

Whole-task success is only:

```javascript
task?.status === "complete"
```

Never infer success from `robot.status === "idle"`: Jetson also returns to
idle after failure or stop.

UI behavior:

| Relay state | Companion behavior |
| --- | --- |
| `queued` / `assigned` | Working face; short queued/assigned caption |
| `running` | Working face; caption derived from `current_state` |
| `complete` | Done face; speak the completion report once |
| `failed` | Sad face; speak failure once |
| `needs_assistance` | Sad face; ask the owner for help once |
| `stopped` | Sad/neutral face; confirm stopped once |
| `expired` | Sad face; report expiration once |
| no active task | Resume idle expression rotation |

## Native bridge protocol

JavaScript calls the injected `ForestBridgeRelay` interface:

```text
requestState(requestId)
createTask(requestId, preset, requestText)
stopTask(requestId, taskId)
```

Android emits one response event:

```javascript
window.dispatchEvent(new CustomEvent(
  "forestbridge:native-relay-response",
  {
    detail: {
      request_id: "uuid",
      operation: "state",
      ok: true,
      status: 200,
      data: {}
    }
  }
));
```

Errors use `ok: false`, include an HTTP status when available, and provide a
short non-secret `error` value. The token must never appear in JavaScript,
logs, Toasts, exceptions sent to the page, or committed files.

The bridge is exposed only while the WebView is restricted to the configured
HTTPS origin. Its methods are fixed operations rather than a generic HTTP
proxy.

## Polling and transition rules

- Poll `GET /api/state` approximately once per second while the page is
  visible.
- Allow only one state request in flight.
- Track the task ID returned by task creation.
- Store the active task ID locally so an Activity recreation can continue
  observing the same task.
- Announce a terminal transition once. Initial loading of an already-terminal
  historical task must not replay an old completion announcement.
- Network failures show a connection problem but do not convert a task to
  `failed`.
- `stop_requested: true` is not a terminal state.

## Acceptance tests

1. A build without `FORESTBRIDGE_UI_TOKEN` opens the face but returns a clear
   native configuration error for task/state requests.
2. With the token configured, state polling marks Jetson online only when its
   heartbeat is under six seconds old.
3. A voice start request creates one task with an idempotency key and stores the
   returned task ID.
4. `queued`, `assigned`, and all running states keep the working face.
5. Only `task.status == "complete"` triggers the success face and completion
   speech.
6. Failure, assistance, stopped, and expired each produce their own feedback.
7. A stop request does not announce stopped until the Relay reports
   `task.status == "stopped"`.
8. A WebView navigation outside the configured HTTPS origin remains blocked.
9. Neither source control nor JavaScript can read the UI token.

## Current worker boundary

The current worker ID is `jetson-dry-run` and it still uses
`DryRunSkillRunner`. The Relay contract is end-to-end, but real ROS, serial,
motor, and manipulator execution remain outside this Android milestone.
