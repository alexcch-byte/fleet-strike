// The rules of Fleet Strike, ported from the Android app (game/Fleet.kt, FairPlay.kt, Gunner.kt) so both play the same game.

/** Rows and columns on each player's ocean. */
export const GRID = 10;

export const SHIPS = [
  { type: 'CARRIER', length: 5, title: 'Carrier' },
  { type: 'BATTLESHIP', length: 4, title: 'Battleship' },
  { type: 'CRUISER', length: 3, title: 'Cruiser' },
  { type: 'SUBMARINE', length: 3, title: 'Submarine' },
  { type: 'DESTROYER', length: 2, title: 'Destroyer' },
];
export const SHIP = Object.fromEntries(SHIPS.map((s) => [s.type, s]));

// ------------------------------------------------------------------ squares

/** Squares are numbered row * 10 + col. Rows are lettered A–J, columns numbered 1–10. */
export const sq = (row, col) => row * GRID + col;
export const rowOf = (s) => Math.floor(s / GRID);
export const colOf = (s) => s % GRID;
export const inBounds = (row, col) => row >= 0 && row < GRID && col >= 0 && col < GRID;
export const ALL = Array.from({ length: GRID * GRID }, (_, i) => i);

/** "C7" style name, as players call out shots. */
export const label = (s) => 'ABCDEFGHIJ'[rowOf(s)] + (colOf(s) + 1);

export function neighbors(s) {
  const r = rowOf(s);
  const c = colOf(s);
  return [[r - 1, c], [r + 1, c], [r, c - 1], [r, c + 1]].filter(([a, b]) => inBounds(a, b)).map(([a, b]) => sq(a, b));
}

// ------------------------------------------------------------------ placements

/** A ship laid on the grid: row/col is its stern (left end, or top end when vertical). */
export const placement = (type, row, col, vertical) => ({ type, row, col, vertical });

export function fits(p) {
  const n = SHIP[p.type].length;
  if (p.row < 0 || p.col < 0) return false;
  return p.vertical ? p.row + n <= GRID && p.col < GRID : p.col + n <= GRID && p.row < GRID;
}

/** The squares a placement covers. Only meaningful when it fits. */
export function cells(p) {
  return Array.from({ length: SHIP[p.type].length }, (_, i) => (p.vertical ? sq(p.row + i, p.col) : sq(p.row, p.col + i)));
}

export const samePlacement = (a, b) => !!a && !!b && a.type === b.type && a.row === b.row && a.col === b.col && a.vertical === b.vertical;

export const encodePlacement = (p) => `${p.type}@${p.row},${p.col},${p.vertical ? 'V' : 'H'}`;

export function decodePlacement(text) {
  const m = /^([A-Z]+)@(-?\d+),(-?\d+),([HV])$/.exec(String(text));
  if (!m || !SHIP[m[1]]) return null;
  return placement(m[1], Number(m[2]), Number(m[3]), m[4] === 'V');
}

// ------------------------------------------------------------------ fleets
// A fleet is a plain object from ship type to placement. Ships may touch but never overlap.

/** In bounds, no overlaps, and every entry is filed under its own type. */
export function isLegal(fleet) {
  const seen = new Set();
  for (const [type, p] of Object.entries(fleet)) {
    if (!p || p.type !== type || !fits(p)) return false;
    for (const c of cells(p)) {
      if (seen.has(c)) return false;
      seen.add(c);
    }
  }
  return true;
}

export const isComplete = (fleet) => SHIPS.every((s) => fleet[s.type]) && isLegal(fleet);

export const fleetCells = (fleet) => new Set(Object.values(fleet).flatMap(cells));

export const shipAt = (fleet, s) => Object.values(fleet).find((p) => cells(p).includes(s)) ?? null;

/** True if p fits: on the grid and clear of every other ship (its own old spot doesn't count). */
export function canPlace(fleet, p) {
  if (!fits(p)) return false;
  const mine = new Set(cells(p));
  return Object.values(fleet).every((o) => o.type === p.type || !cells(o).some((c) => mine.has(c)));
}

export const withShip = (fleet, p) => ({ ...fleet, [p.type]: p });

export function without(fleet, type) {
  const f = { ...fleet };
  delete f[type];
  return f;
}

/** Where a ship can turn to when tapped: the same stern if it fits, otherwise nudged back so it stays on the grid. */
export function rotated(fleet, type) {
  const p = fleet[type];
  if (!p) return null;
  const vertical = !p.vertical;
  for (let back = 0; back < SHIP[type].length; back++) {
    const candidate = vertical ? placement(type, p.row - back, p.col, true) : placement(type, p.row, p.col - back, false);
    if (canPlace(fleet, candidate)) return candidate;
  }
  return null;
}

/** Applies a shot to a fleet, given the squares already hit before it. */
export function resolve(fleet, shot, earlierHits) {
  const ship = shipAt(fleet, shot);
  if (!ship) return { hit: false, sunk: null, defeated: false };
  const hits = new Set(earlierHits);
  hits.add(shot);
  const sunk = cells(ship).every((c) => hits.has(c)) ? ship : null;
  const defeated = [...fleetCells(fleet)].every((c) => hits.has(c));
  return { hit: true, sunk, defeated };
}

/** A stable text form: one placement per ship in a fixed order. Matches the Android app. */
export const encodeFleet = (fleet) => SHIPS.filter((s) => fleet[s.type]).map((s) => encodePlacement(fleet[s.type])).join(';');

export function decodeFleet(text) {
  if (typeof text !== 'string') return null;
  if (!text.trim()) return {};
  const fleet = {};
  for (const part of text.split(';')) {
    const p = decodePlacement(part);
    if (!p || fleet[p.type]) return null;
    fleet[p.type] = p;
  }
  return isLegal(fleet) ? fleet : null;
}

const randInt = (rand, n) => Math.floor(rand() * n);

/** A complete fleet laid out at random, keeping whatever is already in start. */
export function randomFleet(rand = Math.random, start = {}) {
  let fleet = { ...start };
  for (const { type } of SHIPS) {
    if (fleet[type]) continue;
    for (;;) {
      const p = placement(type, randInt(rand, GRID), randInt(rand, GRID), rand() < 0.5);
      if (canPlace(fleet, p)) {
        fleet = withShip(fleet, p);
        break;
      }
    }
  }
  return fleet;
}

// ------------------------------------------------------------------ fair play
//
// When a player locks in their fleet they send only a fingerprint of it (a SHA-256 hash of the layout plus a secret salt).
// When the game ends each side reveals its layout and salt; the other side re-computes the fingerprint and replays every
// hit/miss/sunk report against the revealed fleet. Moving a ship mid-game or answering "miss" on a hit is caught.

export function newSalt() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export const commitment = (fleet, salt) => sha256Hex(`${encodeFleet(fleet)}#${salt}`);

/**
 * Checks the opponent's revealed fleet against what they told us during the game.
 * Returns null when everything adds up, otherwise a sentence saying what didn't.
 */
export function checkFairPlay(revealed, salt, promised, myShots, reportedSunk) {
  if (!revealed || !isComplete(revealed)) return "Their revealed fleet isn't a legal fleet.";
  if (!promised || commitment(revealed, salt) !== promised) return "Their fleet doesn't match the one they locked in before the battle.";
  for (const [c, hit] of myShots) {
    const really = shipAt(revealed, c) !== null;
    if (really !== hit) return `They called ${label(c)} a ${hit ? 'hit' : 'miss'}, but it was a ${really ? 'hit' : 'miss'}.`;
  }
  for (const p of reportedSunk) {
    if (!samePlacement(revealed[p.type], p)) return `They misreported where their ${SHIP[p.type].title} was.`;
  }
  const hits = new Set([...myShots].filter(([, hit]) => hit).map(([c]) => c));
  for (const p of Object.values(revealed)) {
    if (cells(p).every((c) => hits.has(c)) && !reportedSunk.some((s) => s.type === p.type)) {
      return `They never admitted their ${SHIP[p.type].title} was sunk.`;
    }
  }
  return null;
}

// SHA-256, written out so the fingerprint works on any page (crypto.subtle needs https and is async).
const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const ror = (x, n) => (x >>> n) | (x << (32 - n));

export function sha256Hex(text) {
  const bytes = new TextEncoder().encode(text);
  const padded = new Uint8Array(((bytes.length + 9 + 63) >> 6) << 6);
  padded.set(bytes);
  padded[bytes.length] = 0x80;
  const view = new DataView(padded.buffer);
  const bits = bytes.length * 8;
  view.setUint32(padded.length - 8, Math.floor(bits / 0x100000000));
  view.setUint32(padded.length - 4, bits >>> 0);

  const h = new Uint32Array([0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19]);
  const w = new Uint32Array(64);
  for (let off = 0; off < padded.length; off += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(off + i * 4);
    for (let i = 16; i < 64; i++) {
      const a = w[i - 15];
      const b = w[i - 2];
      w[i] = w[i - 16] + (ror(a, 7) ^ ror(a, 18) ^ (a >>> 3)) + w[i - 7] + (ror(b, 17) ^ ror(b, 19) ^ (b >>> 10));
    }
    let [A, B, C, D, E, F, G, H] = h;
    for (let i = 0; i < 64; i++) {
      const t1 = (H + (ror(E, 6) ^ ror(E, 11) ^ ror(E, 25)) + ((E & F) ^ (~E & G)) + K[i] + w[i]) | 0;
      const t2 = ((ror(A, 2) ^ ror(A, 13) ^ ror(A, 22)) + ((A & B) ^ (A & C) ^ (B & C))) | 0;
      H = G;
      G = F;
      F = E;
      E = (D + t1) | 0;
      D = C;
      C = B;
      B = A;
      A = (t1 + t2) | 0;
    }
    h[0] += A;
    h[1] += B;
    h[2] += C;
    h[3] += D;
    h[4] += E;
    h[5] += F;
    h[6] += G;
    h[7] += H;
  }
  return Array.from(h, (x) => x.toString(16).padStart(8, '0')).join('');
}

// ------------------------------------------------------------------ the computer's gunner

export const DIFFICULTIES = [
  { id: 'EASY', title: 'Easy' },
  { id: 'NORMAL', title: 'Normal' },
  { id: 'HARD', title: 'Hard' },
];

const pickFrom = (list, rand) => list[randInt(rand, list.length)];

function nextTo(openHits, shots, rand) {
  const options = [...openHits].flatMap(neighbors).filter((c) => !shots.has(c));
  return options.length ? pickFrom(options, rand) : null;
}

/**
 * The computer's targeting officer. It only knows what a human would: where it has fired (shots: Map square → hit), and
 * which enemy ships have been reported sunk.
 *
 * - Easy fires at random until it hits something, then pokes at the squares next to the hit.
 * - Hard counts, for every square, how many ways the surviving enemy ships could still be lying across it, and fires at
 *   the likeliest square. Once it has a hit that isn't part of a sunk ship it only counts layouts through that hit.
 * - Normal plays like Hard but takes a random pot-shot about a third of the time while it's hunting.
 */
export function pickTarget(difficulty, shots, sunk, rand = Math.random) {
  const unknown = ALL.filter((c) => !shots.has(c));
  if (!unknown.length) throw new Error('No squares left to fire at');
  const sunkCells = new Set(sunk.flatMap(cells));
  const openHits = new Set([...shots].filter(([c, hit]) => hit && !sunkCells.has(c)).map(([c]) => c));

  if (difficulty === 'EASY') return nextTo(openHits, shots, rand) ?? pickFrom(unknown, rand);
  if (difficulty === 'NORMAL' && openHits.size === 0 && rand() < 0.35) return pickFrom(unknown, rand);

  const score = new Array(GRID * GRID).fill(0);
  for (const { type } of SHIPS) {
    if (sunk.some((p) => p.type === type)) continue;
    for (const vertical of [false, true]) {
      for (const origin of ALL) {
        const p = placement(type, rowOf(origin), colOf(origin), vertical);
        if (!fits(p)) continue;
        const pc = cells(p);
        if (pc.some((c) => shots.get(c) === false || sunkCells.has(c))) continue;
        const through = pc.filter((c) => openHits.has(c)).length;
        if (openHits.size && through === 0) continue;
        const weight = openHits.size ? 1 + 25 * through : 1;
        for (const c of pc) if (!shots.has(c)) score[c] += weight;
      }
    }
  }
  const best = Math.max(...unknown.map((c) => score[c]));
  if (best === 0) return nextTo(openHits, shots, rand) ?? pickFrom(unknown, rand);
  return pickFrom(unknown.filter((c) => score[c] === best), rand);
}
