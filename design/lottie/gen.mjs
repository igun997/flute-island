#!/usr/bin/env node
/*
 * Flute Island Lottie generator. Plain Node, no dependencies.
 *   node design/lottie/gen.mjs
 *
 * Per animation it writes:
 *   app/src/main/res/raw/<name>.json                                   app export: shapes only, minified, no sid/slots/bg layer
 *   design/lottie-player/public/projects/flute-island/scene-N/lottie.json   player scene: export + bg layer + bgColor slot
 *   design/lottie-player/public/projects/flute-island/scene-N/controls.json
 *
 * Only shape layers (ty 4) with rc/el/sh/fl/st/gf/tm/rp/tr items. No expressions, text, images, effects or mattes.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const RAW_DIR = resolve(HERE, '../../app/src/main/res/raw');
const PLAYER_DIR = resolve(HERE, '../lottie-player/public/projects/flute-island');
const FR = 60;
const PLAYER_BG = [0.043, 0.122, 0.165, 1]; // ink #0B1F2A, player-only backdrop

// ---------------------------------------------------------------- numbers, colour, time
const TAU = Math.PI * 2;
const r3 = (n) => Math.round(n * 1000) / 1000;
const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
const frac = (x) => x - Math.floor(x);
const smooth = (x) => x * x * (3 - 2 * x);
const sec = (s) => Math.round(s * FR);
const hex = (h) => {
  const n = parseInt(h.slice(1), 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255, 1].map(r3);
};

// ---------------------------------------------------------------- easing
// Segment handles: `o` leaves the key it is set on, `i` enters the next key.
const EASE = {
  io: { o: [0.42, 0], i: [0.58, 1] },
  out: { o: [0.33, 1], i: [0.68, 1] },
  in: { o: [0.32, 0], i: [0.67, 0] },
  back: { o: [0.34, 1.56], i: [0.64, 1] }, // overshoot (spring-like)
};

// ---------------------------------------------------------------- animated properties
const S = (k) => ({ a: 0, k });
const P = (v) => (v && typeof v === 'object' && 'a' in v ? v : S(v));

/** Explicit keys: [{ t, v:number[], e }]. `e` is the easing of the segment starting at that key. */
function track(keys) {
  const dim = keys[0].v.length;
  const rep = (x) => Array(dim).fill(x);
  return {
    a: 1,
    k: keys.map((key, n) => {
      const out = { t: key.t, s: key.v.map(r3) };
      if (n < keys.length - 1) out.o = { x: rep(key.e.o[0]), y: rep(key.e.o[1]) };
      if (n > 0) {
        const prev = keys[n - 1].e;
        out.i = { x: rep(prev.i[0]), y: rep(prev.i[1]) };
      }
      return out;
    }),
  };
}

/**
 * Periodic keys sampled from fn(t) over [0, L) frames, last key == first key (seamless loop).
 * Each segment gets handles that match the local slope at both ends, so sine-like motion stays
 * smooth through every key instead of stopping at each one.
 */
function sampled(fn, L, N = 16) {
  const h = 0.5;
  const D = L / N;
  const pts = Array.from({ length: N + 1 }, (_, n) => {
    const t = (n * L) / N;
    const v = fn(t);
    const d = v.map((_, i) => (fn(t + h)[i] - fn(t - h)[i]) / (2 * h));
    return { t, v, d };
  });
  const keys = pts.map((p, n) => {
    if (n === N) return { t: p.t, v: p.v };
    const q = pts[n + 1];
    let dd = 0;
    p.v.forEach((_, i) => {
      if (Math.abs(q.v[i] - p.v[i]) > Math.abs(q.v[dd] - p.v[dd])) dd = i;
    });
    const diff = q.v[dd] - p.v[dd];
    const m0 = Math.abs(diff) < 1e-9 ? 0 : clamp((p.d[dd] * D) / diff, -4, 4);
    const m1 = Math.abs(diff) < 1e-9 ? 0 : clamp((q.d[dd] * D) / diff, -4, 4);
    return { t: p.t, v: p.v, e: { o: [1 / 3, m0 / 3], i: [2 / 3, 1 - m1 / 3] } };
  });
  return track(keys);
}

// ---------------------------------------------------------------- shape items
const tr = ({ p = [0, 0], a = [0, 0], s = [100, 100], r = 0, o = 100 } = {}) => ({
  ty: 'tr', p: P(p), a: P(a), s: P(s), r: P(r), o: P(o),
});
const grp = (nm, items, t) => ({ ty: 'gr', nm, it: [...items, tr(t)] });
const rect = (p, s, r = 0) => ({ ty: 'rc', d: 1, nm: 'rect', p: P(p), s: P(s), r: P(r) });
const ell = (p, s) => ({ ty: 'el', d: 1, nm: 'ellipse', p: P(p), s: P(s) });
const fill = (c, o = 100) => ({ ty: 'fl', nm: 'fill', c: P(c), o: P(o), r: 1 });
const stroke = (c, w, o = 100) => ({ ty: 'st', nm: 'stroke', c: P(c), o: P(o), w: P(w), lc: 2, lj: 2 });
const trim = (s, e) => ({ ty: 'tm', nm: 'trim', s: P(s), e: P(e), o: S(0), m: 1 });
const shape = (path) => ({ ty: 'sh', nm: 'path', d: 1, ks: S(path) });
const grad = (type, from, to, stops) => ({
  ty: 'gf', nm: 'gradient', o: S(100), r: 1, s: S(from), e: S(to), t: type,
  g: { p: stops.length, k: S(stops.flatMap(([off, c]) => [off, c[0], c[1], c[2]])) },
});
const repeater = (copies, deg) => ({
  ty: 'rp', nm: 'repeat', c: S(copies), o: S(0), m: 1,
  tr: { ty: 'tr', p: S([0, 0]), a: S([0, 0]), s: S([100, 100]), r: S(deg), so: S(100), eo: S(100) },
});

// ---------------------------------------------------------------- layers and documents
function layer(nm, L, shapes, ks = {}) {
  return {
    ty: 4, nm, sr: 1, ip: 0, op: L, st: 0,
    ks: {
      o: P(ks.o ?? 100), r: P(ks.r ?? 0), p: P(ks.p ?? [0, 0, 0]),
      a: P(ks.a ?? [0, 0, 0]), s: P(ks.s ?? [100, 100, 100]),
    },
    shapes,
  };
}
const makeDoc = (nm, L, w, h, layers) => ({ v: '5.7.0', fr: FR, ip: 0, op: L, w, h, nm, ddd: 0, assets: [], layers });

// ---------------------------------------------------------------- path geometry (bezier, relative to layer origin)
const sub = (a, b) => [a[0] - b[0], a[1] - b[1]];
const norm = (a) => ((a % TAU) + TAU) % TAU;

/** Signed span from a0 to a1 that passes through angle `mid`. */
function spanThrough(a0, a1, mid) {
  const ccw = norm(a1 - a0);
  return norm(mid - a0) <= ccw ? ccw : ccw - TAU;
}

/** Cubic segments approximating a circular arc of signed span (<= 90 deg per segment). */
function arcSegs(cx, cy, r, a0, span) {
  const n = Math.max(1, Math.ceil(Math.abs(span) / (Math.PI / 2) - 1e-9));
  const step = span / n;
  const segs = [];
  for (let j = 0; j < n; j++) {
    const s0 = a0 + j * step;
    const s1 = s0 + step;
    const k = (4 / 3) * Math.tan((s1 - s0) / 4) * r;
    const p0 = [cx + r * Math.cos(s0), cy + r * Math.sin(s0)];
    const p1 = [cx + r * Math.cos(s1), cy + r * Math.sin(s1)];
    segs.push({
      p0, p1,
      c1: [p0[0] - k * Math.sin(s0), p0[1] + k * Math.cos(s0)],
      c2: [p1[0] + k * Math.sin(s1), p1[1] - k * Math.cos(s1)],
    });
  }
  return segs;
}

/** Straight-edged segments through pts. */
const lineSegs = (pts, closed) => {
  const n = closed ? pts.length : pts.length - 1;
  return Array.from({ length: n }, (_, j) => {
    const p0 = pts[j];
    const p1 = pts[(j + 1) % pts.length];
    return { p0, p1, c1: p0, c2: p1 };
  });
};

/** Lottie path object from contiguous segments. */
function toPath(segs, closed) {
  const v = [];
  const i = [];
  const o = [];
  if (closed) {
    segs.forEach((s, j) => {
      const prev = segs[(j - 1 + segs.length) % segs.length];
      v.push(s.p0);
      o.push(sub(s.c1, s.p0));
      i.push(sub(prev.c2, s.p0));
    });
  } else {
    v.push(segs[0].p0);
    i.push([0, 0]);
    segs.forEach((s) => {
      o.push(sub(s.c1, v[v.length - 1]));
      v.push(s.p1);
      i.push(sub(s.c2, s.p1));
    });
    o.push([0, 0]);
  }
  return { c: closed, v, i, o };
}
const poly = (pts, closed = true) => toPath(lineSegs(pts, closed), closed);

/** Four-point sparkle: concave edges pulled toward the centre. */
function sparkle(R) {
  const f = 0.2;
  const pts = [[0, -R], [R, 0], [0, R], [-R, 0]];
  const segs = pts.map((p0, j) => {
    const p1 = pts[(j + 1) % 4];
    return { p0, p1, c1: [p0[0] * f, p0[1] * f], c2: [p1[0] * f, p1[1] * f] };
  });
  return toPath(segs, true);
}

/** Crescent = outer circle (r1 at origin) minus inner circle (r2 at dx,dy). */
function crescent(r1, r2, dx, dy) {
  const d = Math.hypot(dx, dy);
  const ux = dx / d;
  const uy = dy / d;
  const a = (r1 * r1 - r2 * r2 + d * d) / (2 * d);
  const h = Math.sqrt(r1 * r1 - a * a);
  const bx = a * ux;
  const by = a * uy;
  const pp = [bx - h * uy, by + h * ux];
  const pm = [bx + h * uy, by - h * ux];
  const away = Math.atan2(-uy, -ux);
  const ang = (p, cx = 0, cy = 0) => Math.atan2(p[1] - cy, p[0] - cx);
  const outer = arcSegs(0, 0, r1, ang(pp), spanThrough(ang(pp), ang(pm), away));
  const inner = arcSegs(dx, dy, r2, ang(pm, dx, dy), spanThrough(ang(pm, dx, dy), ang(pp, dx, dy), away));
  return toPath([...outer, ...inner], true);
}

// ---------------------------------------------------------------- shared art
const COL = {
  cloud: '#E8F1F5', cloudShade: '#C5DCE8',
  stormCloud: '#5B7083', stormShade: '#44566A',
  sky: '#7CC8FF', coral: '#FF7A59', sun: '#FFB547', sunCore: '#FFE08A',
  sand: '#F6E7C8', starSand: '#FFE7A6', snow: '#F4F1EA', bolt: '#FFD23F',
  teal: '#19C3B1', tealLight: '#2DD4BF', white: '#FFFFFF', ink: '#0E3B43',
};

/** Cloud body around bottom-centre origin (0,0), ~60 wide, ~37 tall. Shade is a separate layer behind. */
const cloudBody = (col) => grp('cloud', [
  rect([0, -10], [60, 20], 10),
  ell([-6, -22], [30, 30]),
  ell([14, -18], [22, 22]),
  fill(hex(col)),
]);
const cloudShade = (col) => grp('shade', [rect([0, -7], [60, 20], 10), fill(hex(col))]);

/** Body + shade layers sharing one transform. Shade shows 3px under the body. */
function cloudLayers(name, L, { x, y, s = 100, col = COL.cloud, shade = COL.cloudShade, drift }) {
  const ks = { p: drift ?? [x, y, 0], s: [s, s, 100] };
  return [
    layer(`${name}-body`, L, [cloudBody(col)], ks),
    layer(`${name}-shade`, L, [cloudShade(shade)], ks),
  ];
}

/** Sun = gradient core (gentle breathing) + repeated coral rays (rotating). Layers top to bottom. */
function sunLayers(L, { x, y, core }) {
  const rayD = core + 8;
  const rayLen = 9.5;
  const rayW = 5;
  const breathe = (t) => 100 + 4 * Math.sin((TAU * t) / L);
  return [
    layer('sun-core', L,
      [grp('core', [
        ell([0, 0], [core * 2, core * 2]),
        grad(2, [0, 0], [core, 0], [[0, hex(COL.sunCore)], [1, hex(COL.sun)]]),
      ])],
      { p: [x, y, 0], s: sampled((t) => [breathe(t), breathe(t), 100], L, 12) }),
    layer('sun-rays', L,
      [grp('rays', [
        rect([0, -rayD], [rayW, rayLen], rayW / 2),
        fill(hex(COL.coral)),
        repeater(8, 45),
      ])],
      { p: [x, y, 0], r: sampled((t) => [(45 * t) / L], L, 4) }),
  ];
}

/** A falling streak: falls during the first `win` of each loop, rests hidden at the top the rest of the time. */
function streak(name, L, { x, off, top, bot, win }) {
  return layer(name, L, [grp('streak', [rect([0, 0], [3.2, 12], 1.6), fill(hex(COL.sky))])], {
    p: sampled((t) => {
      const u = frac(t / L + off);
      return [x, top + (bot - top) * Math.pow(Math.min(u / win, 1), 2), 0];
    }, L, 14),
    o: sampled((t) => {
      const u = frac(t / L + off);
      return [100 * Math.sin(Math.PI * Math.min(u / win, 1))];
    }, L, 14),
  });
}

// ---------------------------------------------------------------- animations
function sunScene(L) {
  return sunLayers(L, { x: 48, y: 48, core: 17 });
}

function moonScene(L) {
  const stars = [[78, 22, 6, 0], [85, 50, 3.5, 2.1], [20, 20, 4.5, 4.2]].map(([x, y, R, ph], i) =>
    layer(`star${i}`, L, [grp('star', [shape(sparkle(R)), fill(hex(COL.starSand))])], {
      p: [x, y, 0],
      s: sampled((t) => {
        const v = 55 + 45 * (0.5 + 0.5 * Math.sin((TAU * 2 * t) / L + ph));
        return [v, v, 100];
      }, L, 16),
    }));
  const moon = layer('moon', L, [grp('moon', [shape(crescent(26, 22, 5.66, -5.66)), fill(hex(COL.sand))])], {
    p: sampled((t) => [46, 48 + 1.5 * Math.sin((TAU * t) / L), 0], L, 8),
  });
  return [...stars, moon];
}

function partlyScene(L) {
  return [
    ...cloudLayers('cloud', L, {
      x: 52, y: 80,
      drift: sampled((t) => [52 + 2.5 * Math.sin((TAU * t) / L), 80, 0], L, 8),
    }),
    ...sunLayers(L, { x: 36, y: 36, core: 15 }),
  ];
}

function cloudScene(L) {
  return [
    ...cloudLayers('cloud-a', L, {
      x: 40, y: 68,
      drift: sampled((t) => [40 + 3 * Math.sin((TAU * t) / L), 68, 0], L, 8),
    }),
    ...cloudLayers('cloud-b', L, {
      x: 67, y: 46, s: 74, col: '#D5E8F2', shade: '#B3D3E3',
      drift: sampled((t) => [67 - 3 * Math.sin((TAU * t) / L), 46, 0], L, 8),
    }),
  ];
}

function rainScene(L) {
  const streaks = [[30, 0.0], [41, 0.42], [56, 0.2], [67, 0.66]].map(([x, off], i) =>
    streak(`streak${i}`, L, { x, off, top: 56, bot: 88, win: 0.62 }));
  return [
    ...streaks,
    ...cloudLayers('rain-cloud', L, { x: 48, y: 50 }),
  ];
}

function stormScene(L) {
  const bolt = layer('bolt', L, [grp('bolt', [
    shape(poly([[55, 56], [43, 74], [51, 74], [41, 90], [59, 68], [51, 68], [61, 56]])),
    fill(hex(COL.bolt)),
  ])], {
    o: track([
      { t: 0, v: [0], e: EASE.io },
      { t: 44, v: [0], e: EASE.io },
      { t: 50, v: [100], e: EASE.out },
      { t: 55, v: [35], e: EASE.io },
      { t: 60, v: [100], e: EASE.io },
      { t: 72, v: [0], e: EASE.in },
      { t: L, v: [0] },
    ]),
  });
  const streaks = [[26, 0.1], [35, 0.5], [66, 0.3], [74, 0.8]].map(([x, off], i) =>
    streak(`streak${i}`, L, { x, off, top: 56, bot: 90, win: 0.6 }));
  return [
    bolt,
    ...cloudLayers('storm-cloud', L, { x: 48, y: 50, col: COL.stormCloud, shade: COL.stormShade }),
    ...streaks,
  ];
}

function snowScene(L) {
  const flakes = [[30, 3.6, 0.0, 0.0], [42, 4.6, 0.35, 1.7], [54, 3.2, 0.62, 3.1], [65, 4.2, 0.18, 4.4], [75, 3.0, 0.8, 5.2]]
    .map(([x, d, off, ph], i) => layer(`flake${i}`, L, [grp('flake', [ell([0, 0], [d, d]), fill(hex(COL.snow))])], {
      p: sampled((t) => {
        const u = frac(t / L + off);
        return [x + 2.2 * Math.sin(TAU * u + ph), 50 + 40 * smooth(Math.min(u / 0.8, 1)), 0];
      }, L, 12),
      o: sampled((t) => {
        const u = frac(t / L + off);
        return [100 * Math.sin(Math.PI * Math.min(u / 0.8, 1))];
      }, L, 12),
    }));
  return [...flakes, ...cloudLayers('snow-cloud', L, { x: 48, y: 44 })];
}

function ticket(L) {
  const badge = [70, 62];
  const cardPos = track([
    { t: 0, v: [44, 54, 0], e: EASE.out },
    { t: 22, v: [44, 46, 0], e: EASE.io },
    { t: L, v: [44, 46, 0] },
  ]);
  const cardOpacity = track([
    { t: 0, v: [0], e: EASE.io },
    { t: 16, v: [100], e: EASE.io },
    { t: L, v: [100] },
  ]);
  const pop = track([
    { t: 0, v: [0, 0, 100], e: EASE.back },
    { t: 18, v: [100, 100, 100], e: EASE.io },
    { t: L, v: [100, 100, 100] },
  ]);
  const spin = track([
    { t: 0, v: [-200], e: EASE.out },
    { t: 58, v: [0], e: EASE.io },
    { t: L, v: [0] },
  ]);

  // Reopen arrow: 280 deg arc with an arrowhead at its end.
  const a0 = (-150 * Math.PI) / 180;
  const span = (280 * Math.PI) / 180;
  const R = 5.4;
  const arc = toPath(arcSegs(0, 0, R, a0, span), false);
  const end = a0 + span;
  const ex = R * Math.cos(end);
  const ey = R * Math.sin(end);
  const tan = [-Math.sin(end), Math.cos(end)];
  const rad = [Math.cos(end), Math.sin(end)];
  const head = poly([
    [ex + tan[0] * 2.6, ey + tan[1] * 2.6],
    [ex - tan[0] * 0.9 + rad[0] * 2.3, ey - tan[1] * 0.9 + rad[1] * 2.3],
    [ex - tan[0] * 0.9 - rad[0] * 2.3, ey - tan[1] * 0.9 - rad[1] * 2.3],
  ]);

  // Coral pulse ring: appears as the badge lands, expands once, fades out cleanly.
  const ringSize = track([
    { t: 0, v: [26, 26], e: EASE.io },
    { t: 28, v: [26, 26], e: EASE.out },
    { t: 72, v: [46, 46], e: EASE.io },
    { t: L, v: [46, 46] },
  ]);
  const ringOpacity = track([
    { t: 0, v: [0], e: EASE.io },
    { t: 28, v: [0], e: EASE.io },
    { t: 34, v: [90], e: EASE.out },
    { t: 72, v: [0], e: EASE.io },
    { t: L, v: [0] },
  ]);

  const lines = grp('lines', [
    rect([-7, -7], [28, 3.6], 1.8),
    rect([-11, 0], [18, 3.6], 1.8),
    rect([-9, 7], [22, 3.6], 1.8),
    fill(hex(COL.ink), 28),
  ]);

  return [
    layer('ticket-arrow', L, [
      grp('arc', [shape(arc), stroke(hex(COL.white), 2.6)]),
      grp('head', [shape(head), fill(hex(COL.white))]),
    ], { p: badge, s: pop, r: spin }),
    layer('ticket-badge', L, [grp('badge', [ell([0, 0], [24, 24]), fill(hex(COL.coral))])], { p: badge, s: pop }),
    layer('ticket-ring', L, [grp('ring', [ell([0, 0], ringSize), stroke(hex(COL.coral), 2.2, ringOpacity)])], { p: badge }),
    layer('ticket-lines', L, [lines], { p: cardPos, o: cardOpacity }),
    layer('ticket-card', L, [grp('card', [rect([0, 0], [52, 34], 7), fill(hex(COL.snow))])], { p: cardPos, o: cardOpacity }),
  ];
}

function successScene(L) {
  const scale = track([
    { t: 0, v: [0, 0, 100], e: EASE.out },
    { t: 16, v: [112, 112, 100], e: EASE.io },
    { t: 26, v: [97, 97, 100], e: EASE.io },
    { t: 34, v: [100, 100, 100], e: EASE.io },
    { t: L, v: [100, 100, 100] },
  ]);
  const draw = track([
    { t: 0, v: [0], e: EASE.io },
    { t: 22, v: [0], e: EASE.out },
    { t: 52, v: [100], e: EASE.io },
    { t: L, v: [100] },
  ]);
  const tick = poly([[-14, 1], [-4, 11], [15, -10]], false);
  return [
    layer('success-tick', L, [grp('tick', [
      shape(tick),
      trim(0, draw),
      stroke(hex(COL.white), 6.5),
    ])], { p: [48, 48, 0], s: scale }),
    layer('success-disc', L, [grp('disc', [
      ell([0, 0], [64, 64]),
      grad(1, [-32, -32], [32, 32], [[0, hex(COL.tealLight)], [1, hex(COL.teal)]]),
    ])], { p: [48, 48, 0], s: scale }),
  ];
}

function loadingScene(L) {
  return [
    layer('loading-track', L, [grp('track', [ell([0, 0], [32, 32]), stroke(hex(COL.white), 4, 22)])], { p: [24, 24, 0] }),
    layer('loading-arc', L, [grp('arc', [
      ell([0, 0], [32, 32]),
      trim(0, sampled((t) => [39 + 31 * Math.sin((TAU * t) / L)], L, 16)),
      stroke(hex(COL.white), 4),
    ])], { p: [24, 24, 0], r: sampled((t) => [(360 * t) / L], L, 4) }),
  ];
}

// ---------------------------------------------------------------- build table (scene order)
const SPECS = [
  { name: 'lottie_weather_sun', w: 96, h: 96, L: sec(4), layers: sunScene },
  { name: 'lottie_weather_moon', w: 96, h: 96, L: sec(4), layers: moonScene },
  { name: 'lottie_weather_partly', w: 96, h: 96, L: sec(4), layers: partlyScene },
  { name: 'lottie_weather_cloud', w: 96, h: 96, L: sec(4), layers: cloudScene },
  { name: 'lottie_weather_rain', w: 96, h: 96, L: sec(2), layers: rainScene },
  { name: 'lottie_weather_storm', w: 96, h: 96, L: sec(2.5), layers: stormScene },
  { name: 'lottie_weather_snow', w: 96, h: 96, L: sec(4), layers: snowScene },
  { name: 'lottie_ticket_reopen', w: 96, h: 96, L: sec(1.6), layers: ticket },
  { name: 'lottie_success_check', w: 96, h: 96, L: sec(1.2), layers: successScene },
  { name: 'lottie_loading', w: 48, h: 48, L: sec(1.2), layers: loadingScene },
];

// ---------------------------------------------------------------- output
const roundDeep = (x) => {
  if (Array.isArray(x)) return x.map(roundDeep);
  if (x && typeof x === 'object') return Object.fromEntries(Object.entries(x).map(([k, v]) => [k, roundDeep(v)]));
  return typeof x === 'number' ? r3(x) : x;
};

function playerDoc(exportDoc) {
  const doc = structuredClone(exportDoc);
  doc.slots = { bgColor: { p: { a: 0, k: PLAYER_BG } } };
  doc.layers.push(layer('bg', doc.op, [grp('bg', [
    rect([0, 0], [doc.w, doc.h], 0),
    { ty: 'fl', nm: 'fill', c: { sid: 'bgColor' }, o: S(100), r: 1 },
  ])], { p: [doc.w / 2, doc.h / 2, 0] }));
  return doc;
}

mkdirSync(RAW_DIR, { recursive: true });
const report = [];
SPECS.forEach((spec, idx) => {
  const exportDoc = roundDeep(makeDoc(spec.name, spec.L, spec.w, spec.h, spec.layers(spec.L)));
  const exportText = JSON.stringify(exportDoc);
  if (exportText.includes('"sid"') || exportText.includes('"slots"') || exportText.includes('"nm":"bg"')) {
    throw new Error(`${spec.name}: export leaks sid, slots or bg layer`);
  }
  writeFileSync(join(RAW_DIR, `${spec.name}.json`), exportText);

  const sceneDir = join(PLAYER_DIR, `scene-${idx + 1}`);
  mkdirSync(sceneDir, { recursive: true });
  writeFileSync(join(sceneDir, 'lottie.json'), JSON.stringify(playerDoc(exportDoc)));
  writeFileSync(
    join(sceneDir, 'controls.json'),
    JSON.stringify({ controls: [{ sid: 'bgColor', label: 'Background color' }] }, null, 2) + '\n',
  );
  report.push(`${String(idx + 1).padStart(2)} ${spec.name.padEnd(22)} ${spec.w}x${spec.h} ${spec.L}f ${(exportText.length / 1024).toFixed(1)} KB`);
});
console.log(report.join('\n'));
