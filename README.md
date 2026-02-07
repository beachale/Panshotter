# PanShot (Fabric 1.21)

This mod detaches rendering from the local player by swapping `MinecraftClient` camera entity to a client-side spectator camera.

## Commands

- `/panshot`: Toggle the camera on/off.
- `/panshot tp <x> <y> <z>`: Teleport camera to exact coordinates.
- `/panshot where`: Print current camera coordinates and rotation.
- `/panshot panorama start`: Start periodic panorama capture from your current eye position with default interval (10s). Uses your yaw only (pitch is leveled).
- `/panshot panorama start <x> <y> <z> <yaw> <pitch>`: Start periodic panorama capture from exact decimal coordinates and exact yaw/pitch. Example: `/panshot panorama start 10.059394 64.135 13.1353 0 0`
- `/panshot panorama start every <intervalSeconds>`: Same as `start`, but with custom interval.
- `/panshot panorama start every <intervalSeconds> <x> <y> <z> <yaw> <pitch>`: Exact coords/orientation with custom interval.
- `/panshot panorama status`: Show capture status.
- `/panshot panorama mode`: Show current capture mode.
- `/panshot panorama mode smooth`: Capture one face per tick (best performance, may show slight motion seams on animated water/entities).
- `/panshot panorama mode precise`: Capture all 6 faces in one burst (best temporal alignment, higher hitch risk).
- `/panshot panorama renderplayer`: Show local-player rendering mode for panorama captures.
- `/panshot panorama renderplayer on`: Render a temporary local-player clone in panorama captures.
- `/panshot panorama renderplayer off`: Disable local-player clone rendering (default).
- `/panshot panorama export`: Show export mode (`on`/`off`).
- `/panshot panorama export on`: Also write stitched cubemap to `screenshots/panorama_cubemap.png`.
- `/panshot panorama export off`: Keep cubemap in-memory only (default).
- `/panshot panorama stop`: Stop periodic panorama capture.
- `/panshot single`: Start single-direction live preview with default interval (1s) from your current eye position + yaw/pitch.
- `/panshot single <x> <y> <z> <yaw> <pitch>`: Start single-direction preview from exact decimal coordinates and exact yaw/pitch.
- `/panshot single every <intervalSeconds>`: Same as `single`, but with custom interval.
- `/panshot single every <intervalSeconds> <x> <y> <z> <yaw> <pitch>`: Exact coords/orientation with custom interval.
- `/panshot single resolution`: Show current single-capture resolution.
- `/panshot single resolution <width> <height>`: Set single-capture resolution (64-4096 per dimension).
- `/panshot single fov`: Show current single-capture FOV.
- `/panshot single fov <degrees>`: Set single-capture FOV (1-179).
- `/panshot single renderplayer`: Show single-player rendering mode (`on`/`off`).
- `/panshot single renderplayer on`: Render a temporary local-player clone in single captures.
- `/panshot single renderplayer off`: Disable local-player clone rendering for single captures (default).
- `/panshot single status`: Show single preview status.
- `/panshot single stop`: Stop single preview.

Panorama capture details:

- Captures all 6 faces in one burst, then waits for interval before next cycle.
- Faces are stitched into a single in-memory cubemap (3x2 layout) and streamed to the localhost viewer.
- Order and orientation:
  - `panorama_0.png`: base yaw/pitch used at start.
  - `panorama_1.png`: 90 degrees clockwise from `panorama_0`.
  - `panorama_2.png`: 180 degrees clockwise from `panorama_0`.
  - `panorama_3.png`: 270 degrees clockwise from `panorama_0`.
  - `panorama_4.png`: returns to `panorama_0` yaw, pitched 90 degrees up from base pitch.
  - `panorama_5.png`: same yaw as `panorama_0`, pitched 90 degrees down from base pitch.
- Stitch layout:
  - Top row: `_3 _1 _4`
  - Bottom row: `_5 _0 _2`
- In the plain `start` command (without yaw/pitch args), player look pitch is ignored and only yaw is used.
- Uses 90 degree FOV.
- Renders each face at 1024x1024.
- Streams stitched cubemap internally to the localhost viewer.
- Default capture mode is `smooth` to reduce gameplay hitching.
- Optional disk export can be enabled with `/panshot panorama export on`.
- Runs in the background while you keep playing.

Single preview details:

- Captures one face repeatedly (same fixed yaw/pitch each frame), defaulting to 1024x1024 at 90 degree FOV.
- Resolution and FOV are configurable via `/panshot single resolution <width> <height>` and `/panshot single fov <degrees>`.
- Optional local-player rendering can be toggled with `/panshot single renderplayer on|off`.
- Uses a dedicated localhost viewer URL (separate from panorama viewer).
- Viewer updates live from internal memory stream (`/live-single.png`) and supports loading a compare image overlay.
- Panorama and single preview are mutually exclusive (starting one stops the other).

## Build

```bash
gradle build
```

Output jar:

`build/libs/panshot-<version>.jar`
