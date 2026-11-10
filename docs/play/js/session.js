// One player's side of a match, ported from the Android app (session/MatchSession.kt, BotPlayer.kt, net/Protocol.kt).
//
// The two sides talk in the app's protocol: one JSON object per line, with "t" naming the message.
//   hello {app, version, name, bonusShot} · ready {commitment} · start {hostFirst} · fire {row, col}
//   result {row, col, hit, sunk, defeated} · reveal {fleet, salt} · rematch · bye
// Both open with hello. Each player sends ready with a fingerprint of their fleet; once the host has both it sends start
// naming who fires first. Shots go fire → result. When a fleet is wiped out both reveal, and either may ask for a rematch.

import {
  checkFairPlay, commitment, decodeFleet, decodePlacement, encodeFleet, encodePlacement, isComplete, newSalt, pickTarget,
  randomFleet, resolve, sq, inBounds,
} from './rules.js';

export const APP_ID = 'fleetstrike';
export const PROTOCOL_VERSION = 1;
export const MAX_NAME = 20;
export const HANDSHAKE_TIMEOUT_MS = 10_000;

export const Phase = { HANDSHAKE: 'handshake', PLACEMENT: 'placement', BATTLE: 'battle', GAME_OVER: 'gameOver' };
export const Verdict = { PENDING: 'pending', FAIR: 'fair', SUSPICIOUS: 'suspicious' };

/** What the screen may draw: our own fleet, and only what the other side has told us about theirs. */
function freshState(isHost, myName, bonusShot) {
  return {
    isHost,
    myName,
    /** House rule: a hit earns the shooter another shot. Set by the host. */
    bonusShot,
    phase: Phase.HANDSHAKE,
    round: 1,
    opponentName: '',
    fleet: null,
    myReady: false,
    opponentReady: false,
    myTurn: false,
    /** Our shot in the air, waiting for the other side to say what it hit. */
    aiming: null,
    /** Square → hit, both ways. */
    myShots: new Map(),
    shotsAtMe: new Map(),
    /** The most recent shot each way, so the boards can animate it landing. */
    lastMyShot: null,
    lastShotAtMe: null,
    enemySunk: [],
    mySunk: new Set(),
    won: null,
    /** The opponent's whole fleet, shown once the game is over. */
    enemyFleet: null,
    verdict: Verdict.PENDING,
    verdictNote: null,
    iWantRematch: false,
    opponentWantsRematch: false,
    wins: 0,
    losses: 0,
    /** Why the match can't go on (the other player left, the connection dropped…), or null while it can. */
    disconnected: null,
  };
}

export const canFire = (s) => s.phase === Phase.BATTLE && s.myTurn && s.aiming === null && !s.disconnected;

/**
 * Runs one side of a match over a link: { send(line), close(), onLine, onClose }.
 * Subscribe with onChange(state => …) and onEvent(event => …). Events:
 *   {type:'battleStarted', myTurn} · {type:'fired', at} · {type:'ourShot', at, hit, sunk, won}
 *   {type:'theirShot', at, hit, sunk, lost} · {type:'rematchStarted'}
 */
export class MatchSession {
  constructor(link, { isHost, myName, bonusShot, random = Math.random, handshakeTimeout = HANDSHAKE_TIMEOUT_MS }) {
    this.link = link;
    this.isHost = isHost;
    this.random = random;
    this.state = freshState(isHost, myName, bonusShot);
    this.changeListeners = new Set();
    this.eventListeners = new Set();
    this.salt = '';
    this.opponentCommitment = null;
    /** Host only: whether the host fires first next round. Null means toss a coin; after a game the loser starts. */
    this.hostFirstNext = null;
    this.leaving = false;

    link.onLine = (line) => {
      let msg;
      try {
        msg = JSON.parse(line);
      } catch {
        return;
      }
      if (msg && typeof msg.t === 'string') this.handle(msg);
    };
    link.onClose = () => {
      if (!this.leaving) this.dropped(`The connection to ${this.opponentOr('the other player')} was lost.`);
    };
    this.send({ t: 'hello', app: APP_ID, version: PROTOCOL_VERSION, name: myName, bonusShot });
    this.handshakeTimer = setTimeout(() => {
      if (this.state.phase === Phase.HANDSHAKE) {
        this.dropped("The other player didn't answer. Are they still on the Fleet Strike page?");
        link.close();
      }
    }, handshakeTimeout);
  }

  onChange(fn) {
    this.changeListeners.add(fn);
    return () => this.changeListeners.delete(fn);
  }

  onEvent(fn) {
    this.eventListeners.add(fn);
    return () => this.eventListeners.delete(fn);
  }

  update(changes) {
    this.state = { ...this.state, ...(typeof changes === 'function' ? changes(this.state) : changes) };
    for (const fn of [...this.changeListeners]) fn(this.state);
  }

  emit(event) {
    for (const fn of [...this.eventListeners]) fn(event);
  }

  /** Locks in our fleet. Only its fingerprint is sent; the layout stays on this device until the game ends. */
  commitFleet(fleet) {
    const s = this.state;
    if (s.phase !== Phase.PLACEMENT || s.myReady || !isComplete(fleet) || s.disconnected) return false;
    this.salt = newSalt();
    this.update({ fleet, myReady: true });
    this.send({ t: 'ready', commitment: commitment(fleet, this.salt) });
    this.maybeStart();
    return true;
  }

  fire(at) {
    const s = this.state;
    if (!canFire(s) || !(at >= 0 && at < 100) || s.myShots.has(at)) return false;
    this.update({ aiming: at });
    this.send({ t: 'fire', row: Math.floor(at / 10), col: at % 10 });
    this.emit({ type: 'fired', at });
    return true;
  }

  requestRematch() {
    const s = this.state;
    if (s.phase !== Phase.GAME_OVER || s.iWantRematch || s.disconnected) return;
    this.update({ iWantRematch: true });
    this.send({ t: 'rematch' });
    this.maybeRestart();
  }

  leave() {
    if (this.leaving) return;
    this.leaving = true;
    clearTimeout(this.handshakeTimer);
    this.send({ t: 'bye' });
    this.link.close();
  }

  // ---------------------------------------------------------------- incoming

  handle(msg) {
    const s = this.state;
    if (s.disconnected) return;
    switch (msg.t) {
      case 'hello':
        return this.onHello(msg);
      case 'ready':
        if (s.phase === Phase.PLACEMENT && !s.opponentReady && typeof msg.commitment === 'string') {
          this.opponentCommitment = msg.commitment;
          this.update({ opponentReady: true });
          this.maybeStart();
        }
        return;
      case 'start':
        if (!this.isHost && s.phase === Phase.PLACEMENT && s.myReady) this.beginBattle(!msg.hostFirst);
        return;
      case 'fire':
        return this.onFire(msg);
      case 'result':
        return this.onResult(msg);
      case 'reveal':
        return this.onReveal(msg);
      case 'rematch':
        if (s.phase === Phase.GAME_OVER) {
          this.update({ opponentWantsRematch: true });
          this.maybeRestart();
        }
        return;
      case 'bye':
        this.dropped(`${this.opponentOr('The other player')} left the game.`);
        this.link.close();
        return;
      default:
        // Something newer than us, or noise: ignore it.
    }
  }

  onHello(msg) {
    if (msg.app !== APP_ID || msg.version !== PROTOCOL_VERSION) {
      this.dropped('The other player has a different version of Fleet Strike. Reload the page on both devices.');
      this.link.close();
      return;
    }
    if (this.state.phase !== Phase.HANDSHAKE) return;
    clearTimeout(this.handshakeTimer);
    this.update((s) => ({
      phase: Phase.PLACEMENT,
      opponentName: String(msg.name ?? '').trim().slice(0, MAX_NAME) || 'Opponent',
      bonusShot: this.isHost ? s.bonusShot : !!msg.bonusShot,
    }));
  }

  maybeStart() {
    const s = this.state;
    if (!this.isHost || s.phase !== Phase.PLACEMENT || !s.myReady || !s.opponentReady) return;
    const hostFirst = this.hostFirstNext ?? this.random() < 0.5;
    this.send({ t: 'start', hostFirst });
    this.beginBattle(hostFirst);
  }

  beginBattle(myTurn) {
    this.update({ phase: Phase.BATTLE, myTurn });
    this.emit({ type: 'battleStarted', myTurn });
  }

  onFire(msg) {
    const s = this.state;
    if (!s.fleet || s.phase !== Phase.BATTLE || s.myTurn || !inBounds(msg.row, msg.col)) return;
    const at = sq(msg.row, msg.col);
    if (s.shotsAtMe.has(at)) return;
    const earlierHits = [...s.shotsAtMe].filter(([, hit]) => hit).map(([c]) => c);
    const report = resolve(s.fleet, at, earlierHits);
    this.send({
      t: 'result', row: msg.row, col: msg.col, hit: report.hit,
      sunk: report.sunk ? encodePlacement(report.sunk) : null, defeated: report.defeated,
    });
    const theyGoAgain = report.hit && s.bonusShot;
    this.update((st) => ({
      shotsAtMe: new Map(st.shotsAtMe).set(at, report.hit),
      lastShotAtMe: at,
      mySunk: report.sunk ? new Set(st.mySunk).add(report.sunk.type) : st.mySunk,
      myTurn: !theyGoAgain && !report.defeated,
      phase: report.defeated ? Phase.GAME_OVER : st.phase,
      won: report.defeated ? false : st.won,
      losses: report.defeated ? st.losses + 1 : st.losses,
    }));
    this.emit({ type: 'theirShot', at, hit: report.hit, sunk: report.sunk?.type ?? null, lost: report.defeated });
    if (report.defeated) this.gameOver(false);
  }

  onResult(msg) {
    const s = this.state;
    if (!inBounds(msg.row, msg.col)) return;
    const at = sq(msg.row, msg.col);
    if (s.phase !== Phase.BATTLE || s.aiming !== at) return;
    const hit = !!msg.hit;
    const defeated = !!msg.defeated;
    let sunk = msg.sunk ? decodePlacement(msg.sunk) : null;
    if (sunk && s.enemySunk.some((p) => p.type === sunk.type)) sunk = null;
    const again = hit && s.bonusShot && !defeated;
    this.update((st) => ({
      myShots: new Map(st.myShots).set(at, hit),
      lastMyShot: at,
      enemySunk: sunk ? [...st.enemySunk, sunk] : st.enemySunk,
      aiming: null,
      myTurn: again,
      phase: defeated ? Phase.GAME_OVER : st.phase,
      won: defeated ? true : st.won,
      wins: defeated ? st.wins + 1 : st.wins,
    }));
    this.emit({ type: 'ourShot', at, hit, sunk: sunk?.type ?? null, won: defeated });
    if (defeated) this.gameOver(true);
  }

  gameOver(iWon) {
    const hostWon = this.isHost ? iWon : !iWon;
    this.hostFirstNext = !hostWon;
    if (this.state.fleet) this.send({ t: 'reveal', fleet: encodeFleet(this.state.fleet), salt: this.salt });
  }

  onReveal(msg) {
    const s = this.state;
    if (s.phase !== Phase.GAME_OVER || s.verdict !== Verdict.PENDING) return;
    const fleet = decodeFleet(msg.fleet);
    const revealed = fleet && isComplete(fleet) ? fleet : null;
    const problem = checkFairPlay(revealed, String(msg.salt ?? ''), this.opponentCommitment, s.myShots, s.enemySunk);
    this.update({
      enemyFleet: revealed,
      verdict: problem ? Verdict.SUSPICIOUS : Verdict.FAIR,
      verdictNote: problem,
    });
  }

  maybeRestart() {
    const s = this.state;
    if (!s.iWantRematch || !s.opponentWantsRematch) return;
    this.salt = '';
    this.opponentCommitment = null;
    this.update({
      ...freshState(this.isHost, s.myName, s.bonusShot),
      phase: Phase.PLACEMENT,
      round: s.round + 1,
      opponentName: s.opponentName,
      wins: s.wins,
      losses: s.losses,
    });
    this.emit({ type: 'rematchStarted' });
  }

  // ---------------------------------------------------------------- plumbing

  send(msg) {
    this.link.send(JSON.stringify(msg));
  }

  dropped(reason) {
    clearTimeout(this.handshakeTimer);
    if (this.state.disconnected) return;
    this.update({ disconnected: reason, aiming: null });
  }

  opponentOr(fallback) {
    return this.state.opponentName || fallback;
  }
}

/** Two ends of an in-memory connection, for practice games and pass-and-play. Lines arrive on a later tick, like a network. */
export function pipePair() {
  const make = () => ({ onLine: null, onClose: null, closed: false, peer: null });
  const a = make();
  const b = make();
  a.peer = b;
  b.peer = a;
  for (const end of [a, b]) {
    end.send = (line) => {
      if (end.closed) return;
      setTimeout(() => {
        if (!end.peer.closed) end.peer.onLine?.(line);
      }, 0);
    };
    end.close = () => {
      if (end.closed) return;
      end.closed = true;
      setTimeout(() => {
        if (!end.peer.closed) {
          end.peer.closed = true;
          end.peer.onClose?.();
        }
      }, 0);
    };
  }
  return [a, b];
}

/**
 * The practice opponent. It plays a real MatchSession on the far end of an in-memory link, so practice games run
 * through exactly the same rules and messages as games between two people. pace scales its pauses (0 in tests).
 */
export class BotPlayer {
  constructor(session, difficulty, { random = Math.random, pace = 1 } = {}) {
    this.session = session;
    this.difficulty = difficulty;
    this.random = random;
    this.pace = pace;
    this.timer = null;
    this.stopped = false;
    // Like collectLatest in the app: every change cancels the pending move and decides afresh.
    this.unsubscribe = session.onChange((s) => this.think(s));
    this.think(session.state);
  }

  think(s) {
    clearTimeout(this.timer);
    if (this.stopped || s.disconnected) return;
    const later = (ms, fn) => {
      this.timer = setTimeout(fn, ms * this.pace);
    };
    if (s.phase === Phase.PLACEMENT && !s.myReady) {
      later(1200, () => this.session.commitFleet(randomFleet(this.random)));
    } else if (s.phase === Phase.BATTLE && canFire(s)) {
      later(650 + this.random() * 600, () => this.session.fire(pickTarget(this.difficulty, s.myShots, s.enemySunk, this.random)));
    } else if (s.phase === Phase.GAME_OVER && s.opponentWantsRematch && !s.iWantRematch) {
      later(700, () => this.session.requestRematch());
    }
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    this.unsubscribe();
    this.session.leave();
  }
}
