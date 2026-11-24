# Flute Island Lottie animations

All 12 animations are generated procedurally by `gen.mjs` (plain Node, no dependencies).
Do not edit the JSON by hand: change `gen.mjs` and regenerate.

## Regenerate

```sh
node design/lottie/gen.mjs
```

This writes two outputs per animation:

- **App export** `app/src/main/res/raw/<name>.json`: minified, numbers rounded to 3 decimals,
  shape layers only. No player background layer, no `slots`, no `sid` references. Consumed by
  lottie-android 6.6.x.
- **Player scene** `design/lottie-player (not committed; scaffold it with `npx degit diffusionstudio/lottie design/lottie-player`, then run `node design/lottie/gen.mjs`)/public/projects/flute-island/scene-N/`: the same
  animation plus a `bg` layer driven by the `bgColor` slot (default ink `#0B1F2A`), and a
  `controls.json`. Preview with the text-to-lottie player:

```sh
cd design/lottie-player
npm install
npm run dev        # http://localhost:3030/flute-island/scene-N?frame=N
```

`design/lottie-player/.gitignore` excludes `node_modules/` and `dist/`.

## Files

| Scene | Raw resource | Size | Frames @60fps | Playback | Tintable |
|---|---|---|---|---|---|
| 1 | `lottie_weather_sun` | 96x96 | 240 (4 s) | loop | no (coral and yellow) |
| 2 | `lottie_weather_moon` | 96x96 | 240 (4 s) | loop | no (sand and yellow) |
| 3 | `lottie_weather_partly` | 96x96 | 240 (4 s) | loop | no |
| 4 | `lottie_weather_cloud` | 96x96 | 240 (4 s) | loop | no |
| 5 | `lottie_weather_rain` | 96x96 | 120 (2 s) | loop | no |
| 6 | `lottie_weather_storm` | 96x96 | 150 (2.5 s) | loop | no |
| 7 | `lottie_weather_snow` | 96x96 | 240 (4 s) | loop | no |
| 8 | `lottie_ticket_reopen` | 96x96 | 96 (1.6 s) | play once, holds final pose | no |
| 9 | `lottie_success_check` | 96x96 | 72 (1.2 s) | play once, holds final pose | no |
| 10 | `lottie_loading` | 48x48 | 72 (1.2 s) | loop | yes, pure white |

Tintable means the app can recolour the whole animation with a single colour filter,
because every fill is pure `#FFFFFF`.

## Constraints kept

- Shape layers (`ty` 4) only. Groups, rect, ellipse, path, fill, stroke, radial and linear
  gradient fill, trim paths, repeater, transforms. No expressions, text, images, effects,
  blend modes, mattes or 3D.
- Every file has a root `nm`. Transparent background in the export.
- Loops are periodic: every looping property returns to its frame-0 value at `op`.
- Motion uses bezier easing (`io`, `out`, `in`, `back` overshoot) or slope-matched sampling
  for sine-like motion. Linear is used only for spinners and continuous rotation.
