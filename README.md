PanShot (Fabric)
This mod detaches rendering from the local player by swapping MinecraftClient camera entity to a client-side spectator camera.

It's a mod which auto-captures single screenshot or panorama cubemap separate from the player's pov, so you can keep playing and the screenshots will be taken in a static position at whatever interval you choose.

Generates a local 360 cubemap viewer or single image viewer with optional reference image/cubemap comparison for easy recreation. Essentially works like n00bbot + cubemap viewer, except it works entirely on the client side.

Supports custom resource packs, render distance, entities, player rendering and cubemap export.

Vibecoded, of course, but works fine. More features may come in the future.

Usage

/panshot panorama start every 5

/panshot single every 5

/panshot panorama downscale 2.0 cubemap bicubic

/panshot panorama nudge 0.05

Downscale syntax:

/panshot panorama downscale <factor> [stage] [interpolation]

- `factor`: `1.0` disables scaling, values `> 1.0` downscale.
- `stage`: `faces` (before stitching) or `cubemap` (after stitching, default).
- `interpolation`: `nearest`, `bilinear`, `bicubic` (or `cubic`), `supersample`, `box`.

Nudge syntax:

/panshot panorama nudge <distance>

- Applies a per-face offset along that face camera direction before capture.
- Example: with `0.05`, face 0/1/2/3 are nudged forward in each cardinal direction, face 4 nudges up, face 5 nudges down.
- Use `/panshot panorama nudge off` to disable.
