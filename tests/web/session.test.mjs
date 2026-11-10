// Run with: node --test tests/web
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { encodeFleet, randomFleet } from '../../docs/play/js/rules.js';
import { BotPlayer, MatchSession, Phase, Verdict, pipePair } from '../../docs/play/js/session.js';

function waitFor(condition, ms = 20000) {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const poll = () => {
      if (condition()) resolve();
      else if (Date.now() - started > ms) reject(new Error('timed out'));
      else setTimeout(poll, 2);
    };
    poll();
  });
}

function botMatch(bonusShot = false) {
  const [a, b] = pipePair();
  const host = new MatchSession(a, { isHost: true, myName: 'Host', bonusShot });
  const guest = new MatchSession(b, { isHost: false, myName: 'Guest', bonusShot: false });
  const bots = [new BotPlayer(host, 'HARD', { pace: 0 }), new BotPlayer(guest, 'NORMAL', { pace: 0 })];
  return { host, guest, bots };
}

const finished = (s) => s.phase === Phase.GAME_OVER && s.verdict !== Verdict.PENDING;

test('two computer players play a whole fair game over the protocol', async () => {
  const { host, guest, bots } = botMatch();
  await waitFor(() => finished(host.state) && finished(guest.state));
  const h = host.state;
  const g = guest.state;
  assert.equal(h.opponentName, 'Guest');
  assert.equal(g.opponentName, 'Host');
  assert.notEqual(h.won, g.won);
  assert.equal(h.verdict, Verdict.FAIR);
  assert.equal(g.verdict, Verdict.FAIR);
  assert.equal(encodeFleet(h.enemyFleet), encodeFleet(g.fleet));
  assert.deepEqual([...h.myShots], [...g.shotsAtMe]);
  bots.forEach((bot) => bot.stop());
});

test('the house rule reaches the guest and a rematch starts with the loser firing first', async () => {
  const { host, guest, bots } = botMatch(true);
  await waitFor(() => guest.state.phase === Phase.PLACEMENT);
  assert.equal(guest.state.bonusShot, true);
  await waitFor(() => finished(host.state) && finished(guest.state));
  const hostLost = host.state.won === false;
  let firstTurnHost = null;
  host.onEvent((e) => {
    if (e.type === 'battleStarted') firstTurnHost = e.myTurn;
  });
  host.requestRematch();
  await waitFor(() => host.state.round === 2 && firstTurnHost !== null);
  assert.equal(firstTurnHost, hostLost);
  assert.equal(host.state.wins + host.state.losses, 1);
  bots.forEach((bot) => bot.stop());
});

test('a player who moves a ship after locking in is caught', async () => {
  const [a, b] = pipePair();
  const honest = new MatchSession(a, { isHost: true, myName: 'Honest', bonusShot: false });
  const cheat = new MatchSession(b, { isHost: false, myName: 'Cheat', bonusShot: false });
  const bots = [new BotPlayer(honest, 'HARD', { pace: 0 }), new BotPlayer(cheat, 'HARD', { pace: 0 })];
  await waitFor(() => cheat.state.phase === Phase.BATTLE);
  // Swap in a different layout behind the fingerprint's back: answers from now on come from the new fleet.
  let other = randomFleet();
  while (encodeFleet(other) === encodeFleet(cheat.state.fleet)) other = randomFleet();
  cheat.state = { ...cheat.state, fleet: other };
  await waitFor(() => finished(honest.state));
  assert.equal(honest.state.verdict, Verdict.SUSPICIOUS);
  assert.match(honest.state.verdictNote, /doesn't match/);
  bots.forEach((bot) => bot.stop());
});

test('a different version is refused, and leaving tells the other player', async () => {
  const [a, b] = pipePair();
  const session = new MatchSession(a, { isHost: true, myName: 'Me', bonusShot: false });
  b.onLine = () => {};
  b.send(JSON.stringify({ t: 'hello', app: 'fleetstrike', version: 99, name: 'Future', bonusShot: false }));
  await waitFor(() => session.state.disconnected);
  assert.match(session.state.disconnected, /different version/);

  const [c, d] = pipePair();
  const one = new MatchSession(c, { isHost: true, myName: 'One', bonusShot: false });
  const two = new MatchSession(d, { isHost: false, myName: 'Two', bonusShot: false });
  await waitFor(() => one.state.phase === Phase.PLACEMENT && two.state.phase === Phase.PLACEMENT);
  two.leave();
  await waitFor(() => one.state.disconnected);
  assert.equal(one.state.disconnected, 'Two left the game.');
});

test('a silent other side times out', async () => {
  const [a, b] = pipePair();
  b.onLine = () => {};
  const session = new MatchSession(a, { isHost: false, myName: 'Me', bonusShot: false, handshakeTimeout: 30 });
  await waitFor(() => session.state.disconnected);
  assert.match(session.state.disconnected, /didn't answer/);
});
