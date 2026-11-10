// Drawing: the ocean boards, the ships and the shot markers. Ported from the Android app (ui/board/BoardView.kt, ShipArt.kt).

import { ALL, GRID, SHIP, SHIPS, colOf, rowOf } from './rules.js';

/** The naval-chart palette, the same as the app. */
export const Sea = {
  night: '#0B1A2E',
  deep: '#0F2742',
  panel: '#14304F',
  panelHigh: '#1B3D63',
  oceanTop: '#1A5486',
  oceanBottom: '#0F3A62',
  gridLine: '#4F8CC0',
  label: '#8FB4D8',
  text: '#E8F1FA',
  textDim: '#9DB6CF',
  amber: '#FFB547',
  sonar: '#3DDC97',
  hit: '#FF5A3C',
  flame: '#FFD166',
  miss: '#E6F1FF',
  danger: '#FF6B6B',
};

const rgba = (hex, a) => {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

// ------------------------------------------------------------------ the animation clock

/** Everything that animates registers here; one requestAnimationFrame loop draws them all while any are on screen. */
const animated = new Set();
let frame = 0;

function tick(now) {
  for (const item of animated) item.draw(now);
  frame = animated.size ? requestAnimationFrame(tick) : 0;
}

export function animate(item) {
  animated.add(item);
  if (!frame) frame = requestAnimationFrame(tick);
  return () => animated.delete(item);
}

/** A 0→1→0 pulse every 760 ms, and a slow 0→1 swell every 6 s, like the app's infinite transitions. */
const flickerAt = (now) => {
  const t = (now % 760) / 380;
  return t <= 1 ? t : 2 - t;
};
const swellAt = (now) => (now % 6000) / 6000;

// ------------------------------------------------------------------ canvases

/**
 * Keeps a canvas's backing store matched to its size on screen and the device's pixel density, and hands back a
 * context that draws in CSS pixels.
 */
export function fitCanvas(canvas, onResize) {
  const ctx = canvas.getContext('2d');
  let width = 0;
  let height = 0;
  const sync = () => {
    const r = canvas.getBoundingClientRect();
    const dpr = window.devicePixelRatio || 1;
    width = r.width;
    height = r.height;
    const w = Math.max(1, Math.round(r.width * dpr));
    const h = Math.max(1, Math.round(r.height * dpr));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    onResize?.();
  };
  const observer = new ResizeObserver(sync);
  observer.observe(canvas);
  return {
    ctx,
    size: () => ({ width, height }),
    sync,
    stop: () => observer.disconnect(),
  };
}

// ------------------------------------------------------------------ ships

const LOOKS = {
  normal: { hullTop: '#A4B1BE', hullBottom: '#6D7B89', deck: '#7F8D9B', detail: '#CAD4DE', outline: '#1C2833', alpha: 1 },
  sub: { hullTop: '#6E7C8A', hullBottom: '#465260', deck: '#55626F', detail: '#CAD4DE', outline: '#1C2833', alpha: 1 },
  sunk: { hullTop: '#5E4A47', hullBottom: '#3A2C2B', deck: '#4A3B39', detail: '#7A6461', outline: '#140C0B', alpha: 0.95 },
  revealed: { hullTop: '#B9C4CF', hullBottom: '#8592A0', deck: '#94A2B0', detail: '#E2E8EE', outline: '#1C2833', alpha: 0.6 },
};

function paintFor(look, type) {
  if (look === 'sunk') return LOOKS.sunk;
  if (look === 'revealed') return LOOKS.revealed;
  const base = type === 'SUBMARINE' ? LOOKS.sub : LOOKS.normal;
  return look === 'normal' ? base : { ...base, alpha: 0.75 };
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  ctx.arcTo(x + w, y + h, x, y + h, r);
  ctx.arcTo(x, y + h, x, y, r);
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
}

/**
 * Draws a ship covering its length in cells from (x, y). Ships are drawn bow-right and turned a quarter turn clockwise
 * (bow-down) when vertical. look: normal, sunk, revealed, ghostOk or ghostBad.
 */
export function drawShip(ctx, type, x, y, cell, vertical, look) {
  ctx.save();
  if (vertical) {
    ctx.translate(x + cell, y);
    ctx.rotate(Math.PI / 2);
  } else {
    ctx.translate(x, y);
  }
  drawShipHorizontal(ctx, type, cell, look);
  ctx.restore();
}

function drawShipHorizontal(ctx, type, cell, look) {
  const p = paintFor(look, type);
  const w = SHIP[type].length * cell;
  const cy = cell / 2;
  const stroke = Math.max(cell * 0.045, 1);
  const beam = cell * { CARRIER: 0.74, BATTLESHIP: 0.62, CRUISER: 0.56, SUBMARINE: 0.46, DESTROYER: 0.5 }[type];
  const stern = cell * 0.1;
  const bow = w - cell * 0.06;
  const bowLength = cell * ({ CARRIER: 0.55, SUBMARINE: 0.7 }[type] ?? 1.05);
  const half = beam / 2;

  const hull = new Path2D();
  if (type === 'SUBMARINE') {
    // A cigar: round at the back, gently pointed at the front.
    hull.moveTo(stern + half, cy - half);
    hull.lineTo(bow - bowLength, cy - half);
    hull.bezierCurveTo(bow - bowLength * 0.3, cy - half, bow, cy - half * 0.35, bow, cy);
    hull.bezierCurveTo(bow, cy + half * 0.35, bow - bowLength * 0.3, cy + half, bow - bowLength, cy + half);
    hull.lineTo(stern + half, cy + half);
    hull.bezierCurveTo(stern, cy + half, stern, cy - half, stern + half, cy - half);
  } else {
    const r = half * 0.55;
    hull.moveTo(stern + r, cy - half);
    hull.lineTo(bow - bowLength, cy - half);
    hull.quadraticCurveTo(bow - bowLength * 0.25, cy - half, bow, cy);
    hull.quadraticCurveTo(bow - bowLength * 0.25, cy + half, bow - bowLength, cy + half);
    hull.lineTo(stern + r, cy + half);
    hull.quadraticCurveTo(stern, cy + half, stern, cy + half - r);
    hull.lineTo(stern, cy - half + r);
    hull.quadraticCurveTo(stern, cy - half, stern + r, cy - half);
  }
  hull.closePath();

  // Shadow in the water, then the hull with light from above.
  ctx.save();
  ctx.translate(cell * 0.04, cell * 0.06);
  ctx.fillStyle = `rgba(0,0,0,${0.28 * p.alpha})`;
  ctx.fill(hull);
  ctx.restore();
  const grad = ctx.createLinearGradient(0, cy - half, 0, cy + half);
  grad.addColorStop(0, p.hullTop);
  grad.addColorStop(1, p.hullBottom);
  ctx.globalAlpha = p.alpha;
  ctx.fillStyle = grad;
  ctx.fill(hull);

  const deckBlock = (from, to, height, color = p.detail) => {
    roundRect(ctx, from, cy - height / 2, to - from, height, height * 0.25);
    ctx.fillStyle = color;
    ctx.fill();
    ctx.lineWidth = stroke * 0.8;
    ctx.strokeStyle = p.outline;
    ctx.stroke();
  };
  const turret = (x, facingBow) => {
    const r = beam * 0.2;
    const dir = facingBow ? 1 : -1;
    ctx.beginPath();
    ctx.moveTo(x, cy);
    ctx.lineTo(x + dir * r * 2.3, cy);
    ctx.lineWidth = r * 0.5;
    ctx.lineCap = 'round';
    ctx.strokeStyle = p.outline;
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(x, cy, r, 0, Math.PI * 2);
    ctx.fillStyle = p.detail;
    ctx.fill();
    ctx.lineWidth = stroke * 0.8;
    ctx.stroke();
  };
  const funnel = (x) => {
    ctx.beginPath();
    ctx.ellipse(x, cy, beam * 0.12, beam * 0.16, 0, 0, Math.PI * 2);
    ctx.fillStyle = p.outline;
    ctx.fill();
  };

  switch (type) {
    case 'CARRIER': {
      // Flight deck with a dashed centre line, an island on the starboard side and a few parked aircraft.
      roundRect(ctx, stern + cell * 0.12, cy - half * 0.78, bow - bowLength * 0.6 - stern - cell * 0.12, half * 1.56, cell * 0.06);
      ctx.fillStyle = '#4A535D';
      ctx.fill();
      ctx.save();
      ctx.globalAlpha = 0.8 * p.alpha;
      ctx.setLineDash([cell * 0.18, cell * 0.12]);
      ctx.beginPath();
      ctx.moveTo(stern + cell * 0.3, cy);
      ctx.lineTo(bow - bowLength * 0.8, cy);
      ctx.strokeStyle = '#E8EEF4';
      ctx.lineWidth = stroke;
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.globalAlpha = 0.5 * p.alpha;
      ctx.beginPath();
      ctx.moveTo(stern + cell * 0.4, cy + half * 0.5);
      ctx.lineTo(w * 0.45, cy - half * 0.15);
      ctx.lineWidth = stroke * 0.8;
      ctx.stroke();
      ctx.restore();
      roundRect(ctx, w * 0.58, cy - half * 0.98, cell * 0.62, half * 0.48, cell * 0.05);
      ctx.fillStyle = p.detail;
      ctx.fill();
      ctx.lineWidth = stroke * 0.8;
      ctx.strokeStyle = p.outline;
      ctx.stroke();
      ctx.save();
      ctx.globalAlpha = 0.85 * p.alpha;
      ctx.fillStyle = p.detail;
      for (let i = 0; i < 3; i++) {
        const x = w * 0.2 + i * cell * 0.55;
        const y = cy + half * 0.38;
        const s = cell * 0.13;
        ctx.beginPath();
        ctx.moveTo(x + s * 1.4, y);
        ctx.lineTo(x - s, y - s);
        ctx.lineTo(x - s * 0.5, y);
        ctx.lineTo(x - s, y + s);
        ctx.closePath();
        ctx.fill();
      }
      ctx.restore();
      break;
    }
    case 'BATTLESHIP':
      deckBlock(w * 0.3, w * 0.56, beam * 0.56);
      deckBlock(w * 0.36, w * 0.47, beam * 0.32, p.deck);
      funnel(w * 0.52);
      turret(w * 0.17, false);
      turret(w * 0.66, true);
      turret(w * 0.79, true);
      break;
    case 'CRUISER':
      deckBlock(w * 0.34, w * 0.6, beam * 0.55);
      funnel(w * 0.53);
      turret(w * 0.18, false);
      turret(w * 0.74, true);
      break;
    case 'SUBMARINE':
      deckBlock(w * 0.5, w * 0.68, beam * 0.5, p.deck);
      ctx.beginPath();
      ctx.moveTo(stern + cell * 0.05, cy - half * 1.2);
      ctx.lineTo(stern + cell * 0.05, cy + half * 1.2);
      ctx.lineWidth = stroke * 1.6;
      ctx.lineCap = 'round';
      ctx.strokeStyle = p.outline;
      ctx.stroke();
      break;
    case 'DESTROYER':
      deckBlock(w * 0.3, w * 0.55, beam * 0.55);
      funnel(w * 0.4);
      turret(w * 0.7, true);
      break;
    default:
  }

  ctx.lineWidth = stroke;
  ctx.strokeStyle = p.outline;
  ctx.stroke(hull);
  if (look === 'ghostOk' || look === 'ghostBad') {
    ctx.globalAlpha = 0.9;
    ctx.lineWidth = stroke * 2.5;
    ctx.strokeStyle = look === 'ghostOk' ? Sea.sonar : Sea.danger;
    ctx.stroke(hull);
  }
  ctx.globalAlpha = 1;
}

// ------------------------------------------------------------------ shot markers

function drawHit(ctx, x, y, cell, flicker, alpha) {
  const r = cell * (0.42 + 0.06 * flicker);
  const g = ctx.createRadialGradient(x, y, 0, x, y, r);
  g.addColorStop(0, rgba(Sea.flame, 0.95));
  g.addColorStop(0.33, 'rgba(255,138,61,0.8)');
  g.addColorStop(0.66, rgba(Sea.hit, 0.35));
  g.addColorStop(1, 'rgba(255,90,60,0)');
  ctx.globalAlpha = alpha;
  ctx.fillStyle = g;
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.fill();
  ctx.beginPath();
  ctx.arc(x, y, cell * 0.14, 0, Math.PI * 2);
  ctx.fillStyle = Sea.hit;
  ctx.fill();
  ctx.lineWidth = cell * 0.03;
  ctx.strokeStyle = '#5A1208';
  ctx.stroke();
  ctx.globalAlpha = 1;
}

function drawMiss(ctx, x, y, cell, alpha) {
  ctx.globalAlpha = alpha;
  ctx.lineWidth = cell * 0.035;
  for (const [radius, a] of [[0.34, 0.22], [0.22, 0.45]]) {
    ctx.beginPath();
    ctx.arc(x, y, cell * radius, 0, Math.PI * 2);
    ctx.strokeStyle = rgba(Sea.miss, a);
    ctx.stroke();
  }
  ctx.beginPath();
  ctx.arc(x, y, cell * 0.1, 0, Math.PI * 2);
  ctx.fillStyle = Sea.miss;
  ctx.fill();
  ctx.globalAlpha = 1;
}

/** A shell dropping onto the square (its shadow closing in), then a splash or a burst of fire. t runs 0→1. */
function drawLanding(ctx, x, y, cell, hit, t, flicker) {
  const drop = 0.4;
  if (t < drop) {
    const k = t / drop;
    ctx.beginPath();
    ctx.arc(x, y, cell * (0.55 - 0.4 * k), 0, Math.PI * 2);
    ctx.fillStyle = `rgba(0,0,0,${0.15 + 0.35 * k})`;
    ctx.fill();
    ctx.beginPath();
    ctx.arc(x, y, cell * 0.07, 0, Math.PI * 2);
    ctx.fillStyle = rgba(Sea.amber, 0.4 + 0.6 * k);
    ctx.fill();
    return;
  }
  const k = (t - drop) / (1 - drop);
  ctx.beginPath();
  ctx.arc(x, y, cell * (0.2 + 0.9 * k), 0, Math.PI * 2);
  ctx.strokeStyle = rgba(hit ? Sea.flame : Sea.miss, (1 - k) * 0.9);
  ctx.lineWidth = cell * 0.07 * (1 - k) + 1;
  ctx.stroke();
  if (hit) {
    ctx.beginPath();
    ctx.arc(x, y, cell * 0.45 * (1 - k * 0.5), 0, Math.PI * 2);
    ctx.fillStyle = `rgba(255,255,255,${(1 - k) * 0.8})`;
    ctx.fill();
    drawHit(ctx, x, y, cell, flicker, Math.max(k, 0.3));
  } else {
    drawMiss(ctx, x, y, cell, Math.max(k, 0.3));
  }
}

function drawCrosshair(ctx, x, y, cell, pulse, alpha = 1) {
  const r = cell * (0.32 + 0.08 * pulse);
  const arm = cell * 0.5;
  ctx.globalAlpha = alpha;
  ctx.strokeStyle = Sea.amber;
  ctx.lineWidth = cell * 0.06;
  ctx.beginPath();
  ctx.arc(x, y, r, 0, Math.PI * 2);
  ctx.stroke();
  ctx.lineWidth = cell * 0.05;
  ctx.beginPath();
  ctx.moveTo(x - arm, y);
  ctx.lineTo(x - r * 0.5, y);
  ctx.moveTo(x + r * 0.5, y);
  ctx.lineTo(x + arm, y);
  ctx.moveTo(x, y - arm);
  ctx.lineTo(x, y - r * 0.5);
  ctx.moveTo(x, y + r * 0.5);
  ctx.lineTo(x, y + arm);
  ctx.stroke();
  ctx.globalAlpha = 1;
}

// ------------------------------------------------------------------ the board

/** The board is square: a strip for the row/column labels, then the grid, then a little breathing room. */
export function geometryFor(width) {
  const cell = width / (GRID + 0.75);
  return { cell, margin: cell * 0.6 };
}

const LANDING_MS = 750;

/**
 * One player's ocean: grid, labels, ships, and the pegs where shots have landed.
 *
 * Set ships ([{p, look}]), shots (Map square → hit), aiming (a shot in the air), highlight (your turn here), dimmed,
 * and ghost ({p, ok}) for a ship being dragged over it. newest(square) animates a shot landing. onTap makes squares
 * tappable (and reachable with the arrow keys and Enter); hover shows where a tap would land.
 */
export class Board {
  constructor({ onTap = null, label = 'Board' } = {}) {
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'board';
    this.canvas.setAttribute('role', 'img');
    this.canvas.setAttribute('aria-label', label);
    this.ships = [];
    this.shots = new Map();
    this.aiming = null;
    this.highlight = false;
    this.dimmed = false;
    this.ghost = null;
    this.hover = null;
    this.newestSquare = null;
    this.landingStart = -Infinity;
    this.onTap = onTap;
    this.fit = fitCanvas(this.canvas);
    this.stopAnimating = animate(this);

    if (onTap) {
      this.canvas.classList.add('tappable');
      let down = null;
      this.canvas.addEventListener('pointerdown', (e) => {
        down = this.squareAt(e.clientX, e.clientY);
      });
      this.canvas.addEventListener('pointerup', (e) => {
        const at = this.squareAt(e.clientX, e.clientY);
        if (at !== null && at === down) this.onTap?.(at);
        down = null;
      });
      this.canvas.addEventListener('pointermove', (e) => {
        this.hover = e.pointerType === 'mouse' ? this.squareAt(e.clientX, e.clientY) : null;
      });
      this.canvas.addEventListener('pointerleave', () => {
        this.hover = null;
      });
      this.canvas.tabIndex = 0;
      this.canvas.addEventListener('keydown', (e) => {
        const moves = { ArrowUp: [-1, 0], ArrowDown: [1, 0], ArrowLeft: [0, -1], ArrowRight: [0, 1] };
        if (moves[e.key]) {
          const at = this.hover ?? 44;
          const row = Math.min(GRID - 1, Math.max(0, rowOf(at) + moves[e.key][0]));
          const col = Math.min(GRID - 1, Math.max(0, colOf(at) + moves[e.key][1]));
          this.hover = row * GRID + col;
          e.preventDefault();
        } else if ((e.key === 'Enter' || e.key === ' ') && this.hover !== null) {
          this.onTap?.(this.hover);
          e.preventDefault();
        }
      });
      this.canvas.addEventListener('blur', () => {
        this.hover = null;
      });
    }
  }

  /** Starts the landing animation when a new shot arrives (or just marks it, with animate false). */
  newest(square, animate = true) {
    if (square === this.newestSquare) return;
    this.newestSquare = square;
    this.landingStart = square === null || !animate ? -Infinity : performance.now();
  }

  /** The square under a point on the page, or null. */
  squareAt(clientX, clientY) {
    const r = this.canvas.getBoundingClientRect();
    const { cell, margin } = geometryFor(r.width);
    const col = Math.floor((clientX - r.left - margin) / cell);
    const row = Math.floor((clientY - r.top - margin) / cell);
    return row >= 0 && row < GRID && col >= 0 && col < GRID ? row * GRID + col : null;
  }

  /** Where a square's top-left corner is on the page, and how big squares are. */
  squareOnPage(row, col) {
    const r = this.canvas.getBoundingClientRect();
    const { cell, margin } = geometryFor(r.width);
    return { x: r.left + margin + col * cell, y: r.top + margin + row * cell, cell };
  }

  draw(now) {
    const { ctx, size } = this.fit;
    const { width } = size();
    if (!width) return;
    const { cell, margin } = geometryFor(width);
    const gridSize = cell * GRID;
    const flicker = flickerAt(now);
    const swell = swellAt(now);
    ctx.clearRect(0, 0, width, width);

    roundRect(ctx, 0, 0, width, width, cell * 0.3);
    ctx.fillStyle = Sea.deep;
    ctx.fill();
    const ocean = ctx.createLinearGradient(0, margin, 0, margin + gridSize);
    ocean.addColorStop(0, Sea.oceanTop);
    ocean.addColorStop(1, Sea.oceanBottom);
    ctx.fillStyle = ocean;
    ctx.fillRect(margin, margin, gridSize, gridSize);

    // Gently moving swell, so the sea never looks frozen.
    ctx.lineWidth = cell * 0.035;
    ctx.lineCap = 'round';
    for (const c of ALL) {
      const t = (swell + rowOf(c) * 0.11 + colOf(c) * 0.067) % 1;
      const a = 0.03 + 0.07 * ((Math.sin(2 * Math.PI * t) + 1) / 2);
      const x = margin + colOf(c) * cell;
      const y = margin + rowOf(c) * cell;
      ctx.beginPath();
      ctx.moveTo(x + cell * 0.18, y + cell * 0.66);
      ctx.quadraticCurveTo(x + cell * 0.34, y + cell * 0.56, x + cell * 0.5, y + cell * 0.66);
      ctx.quadraticCurveTo(x + cell * 0.66, y + cell * 0.76, x + cell * 0.82, y + cell * 0.66);
      ctx.strokeStyle = `rgba(255,255,255,${a})`;
      ctx.stroke();
    }

    ctx.strokeStyle = rgba(Sea.gridLine, 0.45);
    ctx.lineWidth = 1.2;
    ctx.beginPath();
    for (let i = 0; i <= GRID; i++) {
      const o = margin + i * cell;
      ctx.moveTo(margin, o);
      ctx.lineTo(margin + gridSize, o);
      ctx.moveTo(o, margin);
      ctx.lineTo(o, margin + gridSize);
    }
    ctx.stroke();

    ctx.fillStyle = Sea.label;
    ctx.font = `700 ${cell * 0.34}px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    for (let i = 0; i < GRID; i++) {
      ctx.fillText('ABCDEFGHIJ'[i], margin / 2, margin + i * cell + cell / 2);
      ctx.fillText(String(i + 1), margin + i * cell + cell / 2, margin / 2);
    }

    for (const { p, look } of this.ships) drawShip(ctx, p.type, margin + p.col * cell, margin + p.row * cell, cell, p.vertical, look);
    if (this.ghost) {
      const { p, ok } = this.ghost;
      drawShip(ctx, p.type, margin + p.col * cell, margin + p.row * cell, cell, p.vertical, ok ? 'ghostOk' : 'ghostBad');
    }

    const land = (now - this.landingStart) / LANDING_MS;
    for (const [c, hit] of this.shots) {
      const x = margin + colOf(c) * cell + cell / 2;
      const y = margin + rowOf(c) * cell + cell / 2;
      if (c === this.newestSquare && land < 1) drawLanding(ctx, x, y, cell, hit, Math.max(land, 0), flicker);
      else if (hit) drawHit(ctx, x, y, cell, flicker, 1);
      else drawMiss(ctx, x, y, cell, 1);
    }

    if (this.aiming !== null) {
      drawCrosshair(ctx, margin + colOf(this.aiming) * cell + cell / 2, margin + rowOf(this.aiming) * cell + cell / 2, cell, flicker);
    } else if (this.highlight && this.hover !== null) {
      const taken = this.shots.has(this.hover);
      drawCrosshair(ctx, margin + colOf(this.hover) * cell + cell / 2, margin + rowOf(this.hover) * cell + cell / 2, cell, 0, taken ? 0.25 : 0.6);
    }

    if (this.dimmed) {
      ctx.fillStyle = 'rgba(5,13,24,0.35)';
      ctx.fillRect(margin, margin, gridSize, gridSize);
    }
    if (this.highlight) {
      ctx.strokeStyle = rgba(Sea.amber, 0.45 + 0.4 * flicker);
      ctx.lineWidth = cell * 0.06;
      ctx.strokeRect(margin, margin, gridSize, gridSize);
    }
  }

  destroy() {
    this.stopAnimating();
    this.fit.stop();
  }
}

// ------------------------------------------------------------------ small pictures

/** A single ship on its own canvas, for the deployment tray. Redraws when resized. */
export function shipPicture(type, cell) {
  const canvas = document.createElement('canvas');
  canvas.className = 'ship-pic';
  canvas.style.width = `${SHIP[type].length * cell}px`;
  canvas.style.height = `${cell}px`;
  const fit = fitCanvas(canvas, () => {
    const { width, height } = fit.size();
    fit.ctx.clearRect(0, 0, width, height);
    drawShip(fit.ctx, type, 0, 0, height, false, 'normal');
  });
  return { canvas, stop: fit.stop };
}

/** The five ships in a row, sunk ones darkened: the fleet tally under each board. */
export class FleetTally {
  constructor() {
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'tally';
    this.sunk = new Set();
    this.fit = fitCanvas(this.canvas, () => this.draw());
  }

  set(sunk) {
    this.sunk = new Set(sunk);
    this.draw();
  }

  draw() {
    const { ctx, size } = this.fit;
    const { width, height } = size();
    if (!width) return;
    ctx.clearRect(0, 0, width, height);
    const gaps = 0.6 * (SHIPS.length - 1);
    const squares = SHIPS.reduce((n, s) => n + s.length, 0);
    const cell = Math.min(height, width / (squares + gaps));
    let x = (width - cell * (squares + gaps)) / 2;
    for (const { type, length } of SHIPS) {
      drawShip(ctx, type, x, (height - cell) / 2, cell, false, this.sunk.has(type) ? 'sunk' : 'normal');
      x += (length + 0.6) * cell;
    }
  }

  destroy() {
    this.fit.stop();
  }
}

/** A sonar screen: range rings and a sweeping beam that lights up blips as it passes. */
export class Radar {
  constructor() {
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'radar';
    this.canvas.setAttribute('aria-hidden', 'true');
    this.fit = fitCanvas(this.canvas);
    this.stopAnimating = animate(this);
  }

  draw(now) {
    const { ctx, size } = this.fit;
    const { width, height } = size();
    if (!width) return;
    ctx.clearRect(0, 0, width, height);
    const r = Math.min(width, height) / 2 - 2;
    const cx = width / 2;
    const cy = height / 2;
    const angle = ((now % 4000) / 4000) * 360;
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.fillStyle = '#0A2A22';
    ctx.fill();
    ctx.strokeStyle = rgba(Sea.sonar, 0.28);
    ctx.lineWidth = 1.5;
    for (let i = 1; i <= 4; i++) {
      ctx.beginPath();
      ctx.arc(cx, cy, (r * i) / 4, 0, Math.PI * 2);
      ctx.stroke();
    }
    ctx.strokeStyle = rgba(Sea.sonar, 0.25);
    ctx.beginPath();
    ctx.moveTo(cx - r, cy);
    ctx.lineTo(cx + r, cy);
    ctx.moveTo(cx, cy - r);
    ctx.lineTo(cx, cy + r);
    ctx.stroke();

    const rad = (angle * Math.PI) / 180;
    if (ctx.createConicGradient) {
      const beam = ctx.createConicGradient(rad - Math.PI * 2 * 0.18, cx, cy);
      beam.addColorStop(0, rgba(Sea.sonar, 0));
      beam.addColorStop(0.18, rgba(Sea.sonar, 0.55));
      beam.addColorStop(0.1801, rgba(Sea.sonar, 0));
      beam.addColorStop(1, rgba(Sea.sonar, 0));
      ctx.beginPath();
      ctx.arc(cx, cy, r, 0, Math.PI * 2);
      ctx.fillStyle = beam;
      ctx.fill();
    }
    ctx.beginPath();
    ctx.moveTo(cx, cy);
    ctx.lineTo(cx + Math.cos(rad) * r, cy + Math.sin(rad) * r);
    ctx.strokeStyle = Sea.sonar;
    ctx.lineWidth = 2.5;
    ctx.stroke();

    for (const [deg, dist] of [[40, 0.55], [155, 0.75], [250, 0.35], [310, 0.82]]) {
      // Brightest just after the beam has passed over the blip, fading as it moves on.
      const since = (((angle - deg) % 360) + 360) % 360;
      const glow = Math.min(Math.max(1 - since / 300, 0), 1);
      const b = (deg * Math.PI) / 180;
      ctx.beginPath();
      ctx.arc(cx + Math.cos(b) * r * dist, cy + Math.sin(b) * r * dist, r * 0.045, 0, Math.PI * 2);
      ctx.fillStyle = rgba(Sea.sonar, 0.15 + 0.85 * glow);
      ctx.fill();
    }
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, Math.PI * 2);
    ctx.strokeStyle = rgba(Sea.sonar, 0.7);
    ctx.lineWidth = 3;
    ctx.stroke();
  }

  destroy() {
    this.stopAnimating();
    this.fit.stop();
  }
}
