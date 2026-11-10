// Run with: node --test tests/web
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  SHIPS, canPlace, cells, checkFairPlay, commitment, decodeFleet, encodeFleet, fleetCells, isComplete, label, newSalt,
  pickTarget, placement, randomFleet, resolve, rotated, sha256Hex, sq, withShip,
} from '../../docs/play/js/rules.js';

/** A small seeded generator so failures can be replayed. */
function seeded(seed) {
  let x = seed >>> 0 || 1;
  return () => {
    x ^= x << 13;
    x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5;
    x >>>= 0;
    return x / 0x100000000;
  };
}

test('SHA-256 matches the standard one', () => {
  const inputs = ['', 'abc', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(64), 'a'.repeat(119), 'ünïcødé ⚓ fleet', 'x'.repeat(1000)];
  for (const s of inputs) assert.equal(sha256Hex(s), createHash('sha256').update(s, 'utf8').digest('hex'), JSON.stringify(s.slice(0, 20)));
});

test('labels and the text form match the Android app', () => {
  assert.equal(label(sq(2, 6)), 'C7');
  assert.equal(label(sq(9, 9)), 'J10');
  const fleet = {
    DESTROYER: placement('DESTROYER', 9, 0, false),
    CARRIER: placement('CARRIER', 0, 0, false),
    BATTLESHIP: placement('BATTLESHIP', 2, 2, true),
    CRUISER: placement('CRUISER', 4, 5, false),
    SUBMARINE: placement('SUBMARINE', 6, 9, true),
  };
  const text = encodeFleet(fleet);
  assert.equal(text, 'CARRIER@0,0,H;BATTLESHIP@2,2,V;CRUISER@4,5,H;SUBMARINE@6,9,V;DESTROYER@9,0,H');
  assert.deepEqual(decodeFleet(text), fleet);
  assert.equal(decodeFleet('CARRIER@0,0,H;CARRIER@5,0,H'), null, 'a type twice');
  assert.equal(decodeFleet('CARRIER@0,8,H'), null, 'off the grid');
  assert.equal(decodeFleet('CARRIER@0,0,H;BATTLESHIP@0,3,H'), null, 'overlapping');
});

test('random fleets are complete and legal', () => {
  const rand = seeded(7);
  for (let i = 0; i < 300; i++) {
    const f = randomFleet(rand);
    assert.ok(isComplete(f));
    assert.equal(fleetCells(f).size, 17);
  }
});

test('turning a ship nudges it back onto the grid, or refuses when blocked', () => {
  let f = { CARRIER: placement('CARRIER', 8, 0, false) };
  assert.deepEqual(rotated(f, 'CARRIER'), placement('CARRIER', 5, 0, true));
  f = withShip(f, placement('DESTROYER', 9, 1, true));
  assert.ok(!canPlace(f, placement('DESTROYER', 8, 0, true)));
  f = {
    CRUISER: placement('CRUISER', 0, 0, false),
    BATTLESHIP: placement('BATTLESHIP', 1, 0, false),
  };
  assert.equal(rotated(f, 'CRUISER'), null);
});

test('shots report hits, sinkings and defeat', () => {
  const f = randomFleet(seeded(3));
  const hits = [];
  let last;
  for (const p of SHIPS.map((s) => f[s.type])) {
    for (const c of cells(p)) {
      last = resolve(f, c, hits);
      hits.push(c);
      assert.ok(last.hit);
    }
    assert.equal(last.sunk?.type, p.type);
  }
  assert.ok(last.defeated);
  const water = [...Array(100).keys()].find((c) => !fleetCells(f).has(c));
  assert.deepEqual(resolve(f, water, []), { hit: false, sunk: null, defeated: false });
});

test('the fair-play check passes honest players and catches lies', () => {
  const f = randomFleet(seeded(11));
  const salt = newSalt();
  const promised = commitment(f, salt);
  const shipSq = cells(f.CARRIER)[0];
  const waterSq = [...Array(100).keys()].find((c) => !fleetCells(f).has(c));
  const honest = new Map([[shipSq, true], [waterSq, false]]);
  assert.equal(checkFairPlay(f, salt, promised, honest, []), null);

  assert.match(checkFairPlay(f, 'other salt', promised, honest, []), /doesn't match/);
  const moved = randomFleet(seeded(12));
  assert.match(checkFairPlay(moved, salt, promised, new Map(), []), /doesn't match/);
  assert.match(checkFairPlay(f, salt, promised, new Map([[shipSq, false]]), []), /called .* a miss, but it was a hit/);
  const carrierCells = new Map(cells(f.CARRIER).map((c) => [c, true]));
  assert.match(checkFairPlay(f, salt, promised, carrierCells, []), /never admitted their Carrier/);
});

test('the computer never repeats a shot and finishes every game', () => {
  for (const difficulty of ['EASY', 'NORMAL', 'HARD']) {
    const rand = seeded(difficulty.length * 31);
    let total = 0;
    for (let game = 0; game < 30; game++) {
      const f = randomFleet(rand);
      const shots = new Map();
      const sunk = [];
      let done = false;
      while (!done) {
        const c = pickTarget(difficulty, shots, sunk, rand);
        assert.ok(!shots.has(c), 'repeated a shot');
        const r = resolve(f, c, [...shots].filter(([, h]) => h).map(([s]) => s));
        shots.set(c, r.hit);
        if (r.sunk) sunk.push(r.sunk);
        done = r.defeated;
      }
      total += shots.size;
    }
    const average = total / 30;
    if (difficulty === 'HARD') assert.ok(average < 62, `hard averaged ${average} shots`);
    if (difficulty === 'EASY') assert.ok(average > 62, `easy averaged ${average} shots`);
  }
});
