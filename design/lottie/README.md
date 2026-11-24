# Flute Island Lottie animations

All 13 animations are generated procedurally by `gen.mjs` (plain Node, no dependencies).
Do not edit the JSON by hand: change `gen.mjs` and regenerate.

## Regenerate

```sh
node design/lottie/gen.mjs
```

Per animation it writes:

- **App export** `app/src/main/res/raw/<name>.json`: minified, numbers rounded to 3 decimals,
  shape layers only. No player background layer, no `slots`, no `sid` references. Transparent.
  Consumed by lottie-android 6.6.x.
- **Player scene** `design/lottie-player/public/projects/flute-island/scene-N/`: the same
  animation plus a `bg` layer driven by the `bgColor` slot (default ink `#0B1F2A`), and a
  `controls.json`. Preview with the text-to-lottie player:

```sh
cd design/lottie-player
npm install
npm run dev        # http://localhost:3030/flute-island/scene-N?frame=N
```

`design/lottie-player/.gitignore` excludes `node_modules/` and `dist/`.

## Files

| Scene | Raw resource | Canvas | Frames @60fps | Playback | Tintable |
|---|---|---|---|---|---|
| 1 | `lottie_equalizer` | 48x48 | 120 (2 s) | loop | yes, pure white |
| 2 | `lottie_weather_sun` | 96x96 | 240 (4 s) | loop | no |
| 3 | `lottie_weather_moon` | 96x96 | 240 (4 s) | loop | no |
| 4 | `lottie_weather_partly` | 96x96 | 240 (4 s) | loop | no |
| 5 | `lottie_weather_cloud` | 96x96 | 240 (4 s) | loop | no |
| 6 | `lottie_weather_rain` | 96x96 | 120 (2 s) | loop | no |
| 7 | `lottie_weather_storm` | 96x96 | 150 (2.5 s) | loop | no |
| 8 | `lottie_weather_snow` | 96x96 | 240 (4 s) | loop | no |
| 9 | `lottie_ticket_reopen` | 96x96 | 96 (1.6 s) | play once, holds final pose | no |
| 10 | `lottie_success_check` | 96x96 | 72 (1.2 s) | play once, holds final pose | no |
| 11 | `lottie_loading` | 48x48 | 72 (1.2 s) | loop | yes, pure white |
| 12 | `lottie_message` | 64x64 | 96 (1.6 s) | loop (pop in, hold, pop out) | bubble white, dots teal |
| 13 | `lottie_splash` | 400x400 | 204 (3.4 s) | play once, holds final pose from frame 156 (2.60 s) | no |

Tintable means every fill is pure `#FFFFFF`, so one colour filter recolours the whole animation.

## Splash timing (`lottie_splash`)

| Frames | Seconds | Motion |
|---|---|---|
| 0-36 | 0.00-0.60 | Dot pops in with a spring; teal ring pulses out once |
| 27-72 | 0.45-1.20 | Dot stretches into the 220x64 black pill with overshoot; equaliser bars 42-90 |
| 57-96 | 0.95-1.60 | Sand island rises with a squash; palm trunk grows 76-104; 5 fronds fan out 92-130 |
| 60-124 | 1.00-2.07 | Two sky ripple arcs spread under the island |
| 102-156 | 1.70-2.60 | Sun peeks behind the palm; 4 sparkles twinkle; pill bobs once |
| 156-204 | 2.60-3.40 | Hold. Nothing moves; final pose reached at frame 156 |

The pill is at y 104 (the dot start), so the composition spans roughly y 72-312 and x 96-312,
centred in the 400 px canvas. Title text can sit under the island, around y 330+.

## Draw order

Within a layer the first `shapes` item is drawn on top (After Effects order), and so is the first
layer in the `layers` array. Keep this in mind when stacking groups: the bars come before the body
in the pill layer, and the dome comes before the underside and trunk in the island layer.

## Constraints kept

- Shape layers (`ty` 4) only. Groups, rect, ellipse, path, fill, stroke, radial and linear
  gradient fill, trim paths, repeater, transforms. No expressions, text, images, effects,
  blend modes, mattes or 3D.
- Every file has a root `nm`. Transparent background in the export.
- Loops are periodic: every looping property returns to its frame-0 value at `op`.
- Motion uses bezier easing (`io`, `out`, `in`, `back` overshoot) or slope-matched sampling
  for sine-like motion. Linear is used only for spinners and continuous rotation.
- Export sizes are all under 25 KB (largest: `lottie_splash` 17 KB, `lottie_weather_snow` 21 KB).
