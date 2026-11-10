// The screens: home, the host and join lobbies, and the match (deployment, battle, and the pass-and-play curtain).

import {
  DIFFICULTIES, GRID, SHIP, SHIPS, canPlace, cells, isComplete, label, placement, randomFleet, rotated, shipAt, withShip,
  without,
} from './rules.js';
import { BotPlayer, MAX_NAME, MatchSession, Phase, Verdict, canFire, pipePair } from './session.js';
import { Board, FleetTally, Radar, Sea, animate, drawShip, fitCanvas, geometryFor, shipPicture } from './board.js';
import { Sound } from './sound.js';
import { CODE_LENGTH, hostGame, isCode, joinGame, loadQrCode, normaliseCode } from './net.js';

// ------------------------------------------------------------------ settings

const SETTINGS_KEY = 'fleetstrike.settings';

const settings = (() => {
  let saved = {};
  try {
    saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) ?? '{}') ?? {};
  } catch {
    saved = {};
  }
  const s = { name: '', friendName: '', bonusShot: false, difficulty: 'NORMAL', sound: true, ...saved };
  if (typeof s.name !== 'string' || !s.name) s.name = `Captain ${10 + Math.floor(Math.random() * 90)}`;
  if (typeof s.friendName !== 'string') s.friendName = '';
  if (!DIFFICULTIES.some((d) => d.id === s.difficulty)) s.difficulty = 'NORMAL';
  return s;
})();

function saveSettings() {
  try {
    localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
  } catch {
    // Private windows can refuse storage; the settings just won't be remembered.
  }
}

// A made-up name is saved straight away so the player keeps it until they choose their own.
saveSettings();

const myName = () => settings.name.trim().slice(0, MAX_NAME) || 'Captain';

const sound = new Sound(settings.sound);
window.addEventListener('pointerdown', () => sound.unlock(), true);
window.addEventListener('keydown', () => sound.unlock(), true);

const buzz = (pattern) => {
  try {
    navigator.vibrate?.(pattern);
  } catch {
    // Not every device can vibrate.
  }
};

// ------------------------------------------------------------------ DOM helpers

function h(tag, props = {}, ...kids) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(props ?? {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k === 'text') el.textContent = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k in el && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) if (kid !== null && kid !== undefined && kid !== false) el.append(kid);
  return el;
}

const btn = (text, onclick, kind = '') => h('button', { type: 'button', class: `btn ${kind}`.trim(), onclick }, text);

const hint = (text) => h('p', { class: 'hint', text });

function panel(accent, title, ...kids) {
  return h('section', { class: `panel ${accent}`.trim() }, title && h('h2', { text: title }), ...kids);
}

function toggleRow(title, detail, checked, onChange) {
  return h(
    'label',
    { class: 'setting' },
    h('span', { class: 'setting-text' }, h('span', { text: title }), detail && h('small', { text: detail })),
    h('input', { type: 'checkbox', role: 'switch', class: 'switch', checked, onchange: (e) => onChange(e.target.checked) }),
  );
}

// ------------------------------------------------------------------ screens

const root = document.getElementById('app');
let current = null;

function show(view) {
  const old = current;
  current = view;
  old?.destroy?.();
  root.replaceChildren(view.el);
  view.mounted?.();
  window.scrollTo(0, 0);
}

const goHome = () => show(homeView());

function homeView() {
  const radar = new Radar();

  const captain = panel(
    '',
    'Captain',
    h(
      'label',
      { class: 'field' },
      h('span', { text: 'Your name' }),
      h('input', {
        type: 'text', value: settings.name, maxlength: String(MAX_NAME), autocomplete: 'nickname', enterkeyhint: 'done',
        oninput: (e) => {
          settings.name = e.target.value;
          saveSettings();
        },
      }),
    ),
    toggleRow('Extra shot after a hit', 'House rule for games you host', settings.bonusShot, (on) => {
      settings.bonusShot = on;
      saveSettings();
    }),
    toggleRow('Sound', null, settings.sound, (on) => {
      settings.sound = on;
      sound.enabled = on;
      if (on) sound.unlock();
      saveSettings();
    }),
  );

  const friend = panel(
    'sonar',
    'Play a friend nearby',
    hint('Each on your own phone, tablet or computer. One hosts and gets a code; the other scans it or types it in.'),
    h('div', { class: 'row' }, btn('Host game', () => show(hostView())), btn('Join game', () => show(joinView()), 'sonar')),
  );

  const local = panel(
    'blue',
    'Pass and play',
    hint("Two players, one device. Your fleet is hidden whenever it's the other player's turn."),
    h(
      'div',
      { class: 'row' },
      h(
        'label',
        { class: 'field grow' },
        h('span', { text: "Friend's name" }),
        h('input', {
          type: 'text', value: settings.friendName, placeholder: 'Player 2', maxlength: String(MAX_NAME), enterkeyhint: 'done',
          oninput: (e) => {
            settings.friendName = e.target.value;
            saveSettings();
          },
        }),
      ),
      btn('Start', startLocal, 'blue'),
    ),
  );

  const chips = DIFFICULTIES.map((d) => {
    const chip = h('button', { type: 'button', class: 'chip', 'aria-pressed': String(settings.difficulty === d.id) }, d.title);
    chip.addEventListener('click', () => {
      settings.difficulty = d.id;
      saveSettings();
      chips.forEach((c, i) => c.setAttribute('aria-pressed', String(DIFFICULTIES[i].id === d.id)));
    });
    return chip;
  });
  const practice = panel(
    '',
    'Practice vs computer',
    h('div', { class: 'row wrap' }, h('div', { class: 'chips', role: 'group', 'aria-label': 'Difficulty' }, chips), btn('Start', startPractice)),
  );

  const el = h(
    'div',
    { class: 'screen home' },
    h(
      'div',
      { class: 'home-left' },
      h('header', { class: 'masthead' }, radar.canvas, h('h1', { text: 'FLEET STRIKE' }), h('p', { class: 'tagline', text: 'Hide your fleet. Hunt theirs.' })),
      captain,
    ),
    h(
      'div',
      { class: 'home-right' },
      friend,
      local,
      practice,
      h(
        'p',
        { class: 'footnote' },
        'Fleet Strike is also an ',
        h('a', { href: '../', text: 'Android app' }),
        ' that plays over Wi-Fi or Bluetooth with no internet. The app and this browser game play their own kind, not each other.',
      ),
    ),
  );
  return { el, destroy: () => radar.destroy() };
}

const inviteUrl = (code) => `${location.origin}${location.pathname}?join=${code}`;

function hostView() {
  let net = null;
  const code = h('div', { class: 'code', 'aria-live': 'polite', text: '····' });
  const status = h('p', { class: 'status', text: 'Getting a game code…' });
  const error = h('p', { class: 'error', role: 'alert', hidden: true });
  const qr = h('div', { class: 'qr', hidden: true });
  const how = hint('');
  how.hidden = true;
  const copy = btn('Copy invite link', null, 'quiet small');
  const share = navigator.share ? btn('Share…', null, 'quiet small') : null;
  const links = h('div', { class: 'row center', hidden: true }, copy, share);

  const el = h(
    'div',
    { class: 'screen lobby' },
    h('section', { class: 'panel lobby-card' }, h('h2', { text: 'Host a game' }), status, code, qr, how, links, error, btn('Cancel', goHome, 'quiet')),
  );

  net = hostGame({
    onCode: (c) => {
      const url = inviteUrl(c);
      code.textContent = c;
      status.textContent = 'Waiting for your friend to join…';
      how.replaceChildren(
        'On the other device, scan this code with the camera, or open ',
        h('b', { text: `${location.host}${location.pathname}` }),
        ', choose Join game and enter ',
        h('b', { text: c }),
        '.',
      );
      how.hidden = false;
      links.hidden = false;
      copy.onclick = async () => {
        try {
          await navigator.clipboard.writeText(url);
          copy.textContent = 'Copied!';
        } catch {
          copy.textContent = url;
        }
      };
      if (share) share.onclick = () => navigator.share({ title: 'Fleet Strike', text: `Join my Fleet Strike game: code ${c}`, url }).catch(() => {});
      loadQrCode()
        .then((QRCode) => {
          qr.replaceChildren();
          // eslint-disable-next-line no-new
          new QRCode(qr, { text: url, width: 168, height: 168, colorDark: Sea.night, colorLight: '#ffffff', correctLevel: QRCode.CorrectLevel.M });
          qr.title = url;
          qr.hidden = false;
        })
        .catch(() => {});
    },
    onStatus: (text) => {
      status.textContent = text;
    },
    onConnected: (link) => {
      const handle = net;
      net = null; // The match owns the connection now; leaving this screen mustn't close it.
      startFriendMatch(link, true, handle);
    },
    onError: (text) => {
      status.textContent = '';
      error.textContent = text;
      error.hidden = false;
    },
  });

  return { el, destroy: () => net?.close() };
}

function joinView(initialCode = '') {
  let net = null;
  const input = h('input', {
    class: 'code-input', type: 'text', value: initialCode, maxlength: String(CODE_LENGTH), autocomplete: 'off',
    autocapitalize: 'characters', spellcheck: 'false', enterkeyhint: 'go', 'aria-label': 'Game code', placeholder: 'CODE',
  });
  const go = btn('Join', () => join(), 'sonar');
  const status = h('p', { class: 'status' });
  const error = h('p', { class: 'error', role: 'alert', hidden: true });

  const refreshButton = () => {
    go.disabled = !!net || !isCode(normaliseCode(input.value));
  };
  input.addEventListener('input', () => {
    const tidy = normaliseCode(input.value);
    if (tidy !== input.value) input.value = tidy;
    error.hidden = true;
    refreshButton();
  });
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') join();
  });

  function join() {
    const c = normaliseCode(input.value);
    if (net || !isCode(c)) return;
    error.hidden = true;
    status.textContent = `Connecting to game ${c}…`;
    input.disabled = true;
    net = joinGame(c, {
      onConnected: (link) => {
        const handle = net;
        net = null;
        startFriendMatch(link, false, handle);
      },
      onError: (text) => {
        net = null;
        status.textContent = '';
        error.textContent = text;
        error.hidden = false;
        input.disabled = false;
        refreshButton();
      },
    });
    refreshButton();
  }

  const el = h(
    'div',
    { class: 'screen lobby' },
    h(
      'section',
      { class: 'panel lobby-card' },
      h('h2', { text: 'Join a game' }),
      hint(`Enter the ${CODE_LENGTH}-letter code on the host's screen.`),
      h('div', { class: 'row center' }, input, go),
      status,
      error,
      btn('Cancel', goHome, 'quiet'),
    ),
  );
  refreshButton();
  return {
    el,
    mounted() {
      if (isCode(normaliseCode(initialCode))) join();
      else input.focus();
    },
    destroy: () => net?.close(),
  };
}

// ------------------------------------------------------------------ starting matches

function startFriendMatch(link, isHost, net) {
  const session = new MatchSession(link, { isHost, myName: myName(), bonusShot: settings.bonusShot });
  show(matchView({
    mode: 'friend',
    sessions: [session],
    via: 'Friend nearby',
    cleanup: () => {
      session.leave();
      // Give the goodbye a moment to go before the connection is torn down.
      setTimeout(() => net?.close(), 800);
    },
  }));
}

function startPractice() {
  const [a, b] = pipePair();
  const session = new MatchSession(a, { isHost: true, myName: myName(), bonusShot: settings.bonusShot });
  const bot = new BotPlayer(new MatchSession(b, { isHost: false, myName: 'Computer', bonusShot: false }), settings.difficulty);
  const level = DIFFICULTIES.find((d) => d.id === settings.difficulty).title;
  show(matchView({
    mode: 'practice',
    sessions: [session],
    via: `Practice · ${level}`,
    cleanup: () => {
      session.leave();
      bot.stop();
    },
  }));
}

function startLocal() {
  const first = myName();
  let second = settings.friendName.trim().slice(0, MAX_NAME) || 'Player 2';
  if (second === first) second = `${second} 2`.slice(0, MAX_NAME + 2);
  const [a, b] = pipePair();
  const one = new MatchSession(a, { isHost: true, myName: first, bonusShot: settings.bonusShot });
  const two = new MatchSession(b, { isHost: false, myName: second, bonusShot: false });
  show(matchView({
    mode: 'local',
    sessions: [one, two],
    via: 'Pass and play',
    cleanup: () => {
      one.leave();
      two.leave();
    },
  }));
}

// ------------------------------------------------------------------ the match

/**
 * Shows a match. Friend and practice games have one session (ours). Pass-and-play has two, one per player, linked in
 * memory; the screen shows one player's side at a time and drops a curtain whenever the device has to change hands.
 */
function matchView({ mode, sessions, via, cleanup }) {
  const local = mode === 'local';
  let viewing = 0;
  let curtain = null;
  let stage = null;
  let passTimer = null;
  let toastTimer = null;
  let ended = false;
  let gone = false;
  let wakeLock = null;

  const score = h('div', { class: 'score' });
  const soundButton = h('button', { type: 'button', class: 'icon-btn' });
  const paintSound = () => {
    soundButton.textContent = settings.sound ? '🔊' : '🔇';
    soundButton.setAttribute('aria-label', settings.sound ? 'Sound is on' : 'Sound is off');
    soundButton.title = settings.sound ? 'Sound on' : 'Sound off';
  };
  soundButton.addEventListener('click', () => {
    settings.sound = !settings.sound;
    sound.enabled = settings.sound;
    if (settings.sound) sound.unlock();
    saveSettings();
    paintSound();
  });
  paintSound();

  const stageHost = h('main', { class: 'stage' });
  const toast = h('div', { class: 'toast', role: 'status', 'aria-live': 'polite', hidden: true });
  const modalHost = h('div', { class: 'modal-host' });
  const el = h(
    'div',
    { class: 'screen match' },
    h('header', { class: 'topbar' }, h('span', { class: 'brand', text: 'FLEET STRIKE' }), score, h('span', { class: 'via', text: via }), soundButton, btn('Leave', askLeave, 'quiet small')),
    h('div', { class: 'stage-wrap' }, stageHost, toast),
    modalHost,
  );

  function flash(text, color) {
    toast.textContent = text;
    toast.style.setProperty('--accent', color);
    toast.hidden = false;
    toast.classList.remove('show');
    void toast.offsetWidth; // restart the pop-in animation
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toast.hidden = true;
    }, 2600);
  }

  /** Sounds, vibration and a message for the moments that matter, as in the app. */
  function react(e, s) {
    const opp = s.opponentName || 'Your opponent';
    switch (e.type) {
      case 'battleStarted':
        flash(e.myTurn ? 'Battle stations! You fire first.' : `${opp} fires first.`, Sea.amber);
        break;
      case 'fired':
        sound.play('launch', 0.7);
        break;
      case 'ourShot':
        if (e.won) {
          flash(local ? `${s.myName} sank the whole fleet!` : 'You sank their whole fleet!', Sea.sonar);
          sound.play('sink');
          setTimeout(() => sound.play('win'), 700);
        } else if (e.sunk) {
          flash(`You sank their ${SHIP[e.sunk].title}!`, Sea.sonar);
          sound.play('sink');
        } else if (e.hit) {
          flash(`Hit at ${label(e.at)}!`, Sea.flame);
          sound.play('boom');
        } else {
          flash(`Miss at ${label(e.at)}.`, Sea.textDim);
          sound.play('splash');
        }
        break;
      case 'theirShot':
        if (e.lost) {
          flash(`${opp} sank your last ship.`, Sea.danger);
          sound.play('sink');
          setTimeout(() => sound.play('lose'), 700);
          buzz([80, 60, 160]);
        } else if (e.sunk) {
          flash(`${opp} sank your ${SHIP[e.sunk].title}!`, Sea.danger);
          sound.play('sink');
          buzz([60, 50, 120]);
        } else if (e.hit) {
          flash(`${opp} hit you at ${label(e.at)}!`, Sea.hit);
          sound.play('boom');
          buzz(60);
        } else {
          flash(`${opp} missed at ${label(e.at)}.`, Sea.textDim);
          sound.play('splash');
        }
        break;
      case 'rematchStarted':
        flash(local ? `New game! ${sessions[viewing].state.myName}, deploy your fleet.` : 'New game! Deploy your fleet.', Sea.amber);
        break;
      default:
    }
  }

  const unsubscribe = [];
  sessions.forEach((session, seat) => {
    unsubscribe.push(session.onChange(() => refresh()));
    unsubscribe.push(session.onEvent((e) => {
      if (seat === viewing && !curtain) react(e, session.state);
    }));
  });

  function paintScore() {
    // Always from the first seat's side, so pass-and-play reads "first player wins : second player wins".
    const s = sessions[0].state;
    score.replaceChildren(
      h('span', { class: 'who', text: s.myName }),
      h('b', { text: `${s.wins} : ${s.losses}` }),
      h('span', { class: 'who', text: s.opponentName || '…' }),
    );
  }

  function refresh() {
    if (gone) return;
    const s = sessions[viewing].state;
    paintScore();
    if (!local && s.disconnected && !ended) {
      ended = true;
      showModal('Game ended', s.disconnected, [btn('Back to menu', goHome)]);
    }
    let kind = 'battle';
    if (curtain) kind = 'curtain';
    else if (s.phase === Phase.HANDSHAKE) kind = 'connecting';
    else if (s.phase === Phase.PLACEMENT) kind = 'placement';
    const key = `${kind}|${viewing}|${s.round}|${curtain ? curtain.seat : ''}`;
    if (!stage || stage.key !== key) {
      stage?.destroy?.();
      stage = buildStage(kind);
      stage.key = key;
      stageHost.replaceChildren(stage.el);
      stageHost.scrollTop = 0;
      layout();
    }
    stage.update?.(s);
    if (local) planHandover();
  }

  function buildStage(kind) {
    const session = sessions[viewing];
    if (kind === 'connecting') {
      return { el: h('div', { class: 'connecting' }, h('div', { class: 'spinner' }), h('p', { text: 'Connected. Saying hello to the other device…' })) };
    }
    if (kind === 'curtain') return curtainStage();
    if (kind === 'placement') return placementStage(session, local);
    return battleStage(session, {
      local,
      showVerdict: !local,
      onRematch: () => sessions.forEach((x) => x.requestRematch()),
      onMenu: goHome,
    });
  }

  function layout() {
    if (!stage?.layout) return;
    const cs = getComputedStyle(stageHost);
    const w = stageHost.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    const hgt = stageHost.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom);
    stage.layout(w, hgt);
  }
  const resizer = new ResizeObserver(() => layout());

  // ---------------------------------------------------------------- pass and play

  /** When the player holding the device has done their part and it's the other player's go. */
  function handoverNeeded() {
    const me = sessions[viewing].state;
    const them = sessions[1 - viewing].state;
    if (me.phase === Phase.PLACEMENT && me.myReady && !them.myReady) {
      return { title: `${them.myName}, deploy your fleet`, detail: `${me.myName}'s ships are hidden. No peeking!`, delay: 500 };
    }
    if (me.phase === Phase.BATTLE && !me.myTurn && me.aiming === null && them.myTurn) {
      return { title: `${them.myName}, your turn to fire`, detail: incoming(them), delay: 1400 };
    }
    return null;
  }

  function incoming(st) {
    const at = st.lastShotAtMe;
    if (at === null) return 'Battle stations! You fire first.';
    const hit = st.shotsAtMe.get(at);
    const ship = hit ? shipAt(st.fleet, at) : null;
    if (ship && cells(ship).every((c) => st.shotsAtMe.get(c))) return `${st.opponentName} fired at ${label(at)} and sank your ${SHIP[ship.type].title}.`;
    return `${st.opponentName} fired at ${label(at)}: ${hit ? 'a hit' : 'a miss'}.`;
  }

  function planHandover() {
    if (curtain || passTimer) return;
    const next = handoverNeeded();
    if (!next) return;
    passTimer = setTimeout(() => {
      passTimer = null;
      const still = handoverNeeded();
      if (!still || curtain || gone) return;
      curtain = { seat: 1 - viewing, title: still.title, detail: still.detail };
      refresh();
    }, next.delay);
  }

  function curtainStage() {
    const seat = curtain.seat;
    const st = sessions[seat].state;
    const reveal = () => {
      viewing = seat;
      curtain = null;
      refresh();
      const at = st.lastShotAtMe;
      if (st.phase === Phase.BATTLE && at !== null) {
        const hit = sessions[seat].state.shotsAtMe.get(at);
        sound.play(hit ? 'boom' : 'splash');
        flash(incoming(sessions[seat].state), hit ? Sea.hit : Sea.textDim);
        if (hit) buzz(60);
      } else {
        sound.play('click');
      }
    };
    const go = btn(`I'm ${st.myName}, show my board`, reveal, 'big');
    return {
      el: h(
        'div',
        { class: 'curtain' },
        h('section', { class: 'panel curtain-card' }, h('p', { class: 'eyebrow', text: 'Pass the device' }), h('h2', { text: curtain.title }), h('p', { text: curtain.detail }), go),
      ),
    };
  }

  // ---------------------------------------------------------------- leaving

  function showModal(title, text, buttons) {
    const card = h('div', { class: 'modal panel', role: 'dialog', 'aria-modal': 'true', 'aria-label': title }, h('h2', { text: title }), h('p', { text }), h('div', { class: 'row end' }, buttons));
    modalHost.replaceChildren(h('div', { class: 'scrim' }, card));
    buttons[buttons.length - 1]?.focus();
  }

  const closeModal = () => modalHost.replaceChildren();

  function askLeave() {
    const s = sessions[viewing].state;
    if (s.phase === Phase.GAME_OVER || s.disconnected) {
      goHome();
      return;
    }
    let text = 'This game will end.';
    if (mode === 'friend') text = `${s.opponentName || 'The other player'} will be told you left.`;
    showModal('Leave the game?', text, [btn('Keep playing', closeModal, 'quiet'), btn('Leave', goHome, 'danger')]);
  }

  const onKey = (e) => {
    if (e.key === 'Escape' && modalHost.firstChild && !ended) closeModal();
  };
  const onBeforeUnload = (e) => {
    const s = sessions[viewing].state;
    if (mode !== 'practice' && (s.phase === Phase.PLACEMENT || s.phase === Phase.BATTLE) && !s.disconnected) {
      e.preventDefault();
      e.returnValue = '';
    }
  };
  const onPageHide = () => cleanup();

  const wake = async () => {
    try {
      if (document.visibilityState === 'visible' && navigator.wakeLock) wakeLock = await navigator.wakeLock.request('screen');
    } catch {
      // Keeping the screen on is a nicety.
    }
  };

  return {
    el,
    mounted() {
      resizer.observe(stageHost);
      window.addEventListener('keydown', onKey);
      window.addEventListener('beforeunload', onBeforeUnload);
      window.addEventListener('pagehide', onPageHide);
      document.addEventListener('visibilitychange', wake);
      wake();
      refresh();
    },
    destroy() {
      gone = true;
      unsubscribe.forEach((off) => off());
      resizer.disconnect();
      stage?.destroy?.();
      clearTimeout(passTimer);
      clearTimeout(toastTimer);
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('beforeunload', onBeforeUnload);
      window.removeEventListener('pagehide', onPageHide);
      document.removeEventListener('visibilitychange', wake);
      wakeLock?.release?.().catch(() => {});
      cleanup();
    },
  };
}

// ------------------------------------------------------------------ deployment

/** Laying out a fleet: drag ships from the tray onto the grid, tap one to turn it, drag it off to take it back. */
function placementStage(session, local) {
  let fleet = {};
  let drag = null;
  let trayCell = 26;
  let trayPictures = [];

  const board = new Board({ label: 'Your waters' });
  board.canvas.classList.add('drop-target');
  const boardWrap = h('div', { class: 'board-wrap' }, board.canvas);
  const tray = h('div', { class: 'tray' });
  const allSet = h('p', { class: 'ok-line', text: 'All ships deployed.', hidden: true });
  const shuffle = btn('Shuffle', () => {
    fleet = randomFleet();
    sound.play('click');
    sync();
  }, 'quiet');
  const clear = btn('Clear', () => {
    fleet = {};
    sync();
  }, 'quiet');
  const ready = btn('Ready for battle', () => {
    if (session.commitFleet(fleet)) sound.play('click');
  }, 'sonar wide');
  const controls = h(
    'div',
    { class: 'controls' },
    hint('Drag ships onto your waters. Tap a ship on the grid to turn it. Drag it off the grid to take it back.'),
    tray,
    allSet,
    h('div', { class: 'row' }, shuffle, clear),
    ready,
  );
  const opponentLine = h('p', { class: 'opponent-line' });
  const waitLine = h('p', { class: 'wait-line' });
  const waiting = h(
    'div',
    { class: 'waiting', hidden: true },
    waitLine,
    !local && hint("Your layout stays on this device. Only a sealed fingerprint of it was sent, and it's checked when the game ends."),
  );
  const title = h('h2', { text: local ? `${session.state.myName}, deploy your fleet` : 'Deploy your fleet' });
  const deploy = h('section', { class: 'panel deploy' }, title, opponentLine, controls, waiting);

  // The ship being dragged is drawn on a layer over the whole page, under the finger or pointer.
  const layer = h('canvas', { class: 'drag-layer', 'aria-hidden': 'true' });
  const layerFit = fitCanvas(layer);
  const el = h('div', { class: 'placement' }, boardWrap, deploy, layer);
  const stopLayer = animate({
    draw() {
      const { width, height } = layerFit.size();
      layerFit.ctx.clearRect(0, 0, width, height);
      if (!drag || drag.pending) return;
      const { cell } = geometryFor(board.canvas.getBoundingClientRect().width);
      const x = drag.x - (drag.vertical ? 0.5 : drag.grab + 0.5) * cell;
      const y = drag.y - (drag.vertical ? drag.grab + 0.5 : 0.5) * cell;
      layerFit.ctx.globalAlpha = 0.85;
      drawShip(layerFit.ctx, drag.type, x, y, cell, drag.vertical, 'normal');
      layerFit.ctx.globalAlpha = 1;
    },
  });

  function sync() {
    board.ships = Object.values(fleet).map((p) => ({ p, look: 'normal' }));
    trayPictures.forEach((pic) => pic.stop());
    trayPictures = [];
    tray.replaceChildren(
      ...SHIPS.filter((s) => !fleet[s.type] && drag?.type !== s.type).map(({ type, length, title: name }) => {
        const pic = shipPicture(type, trayCell);
        trayPictures.push(pic);
        const item = h('div', { class: 'tray-ship', title: `Drag the ${name} onto your waters` }, pic.canvas, h('span', { class: 'ship-name' }, name, h('small', { text: ` ${length}` })));
        item.addEventListener('pointerdown', (e) => {
          const r = pic.canvas.getBoundingClientRect();
          const grab = Math.min(length - 1, Math.max(0, Math.floor((e.clientX - r.left) / (r.width / length))));
          // The tray item stays put (just faded) while it's dragged: removing the element under a finger can cancel the touch.
          if (startDrag(e, { type, vertical: false, grab, from: null, pending: false })) item.classList.add('lifted');
        });
        return item;
      }),
    );
    const done = isComplete(fleet);
    allSet.hidden = !done;
    ready.disabled = !done;
    clear.disabled = Object.keys(fleet).length === 0;
  }

  /** Where the dragged ship would land: its grabbed square under the pointer, nudged to stay on the grid. */
  function target(d) {
    const r = board.canvas.getBoundingClientRect();
    const { cell, margin } = geometryFor(r.width);
    const row = Math.floor((d.y - r.top - margin) / cell);
    const col = Math.floor((d.x - r.left - margin) / cell);
    if (row < 0 || row >= GRID || col < 0 || col >= GRID) return null;
    const n = SHIP[d.type].length;
    const clamp = (v, max) => Math.min(Math.max(v, 0), max);
    return d.vertical
      ? placement(d.type, clamp(row - d.grab, GRID - n), col, true)
      : placement(d.type, row, clamp(col - d.grab, GRID - n), false);
  }

  function startDrag(e, d) {
    if (session.state.myReady || drag) return false;
    e.preventDefault();
    drag = { ...d, x: e.clientX, y: e.clientY, startX: e.clientX, startY: e.clientY, pointer: e.pointerId };
    if (!d.pending) move(e);
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', drop);
    window.addEventListener('pointercancel', cancel);
    return true;
  }

  function move(e) {
    if (!drag || e.pointerId !== drag.pointer) return;
    drag.x = e.clientX;
    drag.y = e.clientY;
    if (drag.pending) {
      if (Math.hypot(drag.x - drag.startX, drag.y - drag.startY) < 8) return;
      // It's a drag after all: lift the ship off the grid.
      drag.pending = false;
      fleet = without(fleet, drag.type);
      sync();
    }
    const t = target(drag);
    board.ghost = t ? { p: t, ok: canPlace(fleet, t) } : null;
  }

  function endDrag() {
    drag = null;
    board.ghost = null;
    window.removeEventListener('pointermove', move);
    window.removeEventListener('pointerup', drop);
    window.removeEventListener('pointercancel', cancel);
    sync();
  }

  function drop(e) {
    if (!drag || e.pointerId !== drag.pointer) return;
    if (drag.pending) {
      // A tap on a placed ship turns it.
      const turned = rotated(fleet, drag.type);
      if (turned) {
        fleet = withShip(fleet, turned);
        sound.play('click');
      }
      endDrag();
      return;
    }
    move(e);
    const t = target(drag);
    if (t && canPlace(fleet, t)) {
      fleet = withShip(fleet, t);
      sound.play('click');
    } else if (t && drag.from) {
      fleet = withShip(fleet, drag.from); // dropped on a bad spot: back where it was
    }
    // Dropped off the grid: it goes back to the tray.
    endDrag();
  }

  function cancel(e) {
    if (!drag || e.pointerId !== drag.pointer) return;
    if (drag.from && !fleet[drag.type]) fleet = withShip(fleet, drag.from);
    endDrag();
  }

  board.canvas.addEventListener('pointerdown', (e) => {
    const at = board.squareAt(e.clientX, e.clientY);
    const p = at === null ? null : shipAt(fleet, at);
    if (p) startDrag(e, { type: p.type, vertical: p.vertical, grab: cells(p).indexOf(at), from: p, pending: true });
  });

  sync();

  return {
    el,
    update(s) {
      const opp = s.opponentName || 'Your opponent';
      opponentLine.textContent = local ? '' : s.opponentReady ? `${opp} is ready and waiting.` : `${opp} is deploying…`;
      opponentLine.classList.toggle('ready', s.opponentReady);
      opponentLine.hidden = local;
      controls.hidden = s.myReady;
      waiting.hidden = !s.myReady;
      waitLine.textContent = local ? 'Fleet locked in.' : `Fleet locked in. Waiting for ${opp}…`;
      if (s.myReady && s.fleet) board.ships = Object.values(s.fleet).map((p) => ({ p, look: 'normal' }));
    },
    layout(w, hgt) {
      const wide = w >= 700 && w >= hgt * 1.05;
      el.classList.toggle('wide', wide);
      const size = wide ? Math.min(hgt, w - 380, 640) : Math.min(w, Math.max(260, hgt * 0.56), 560);
      boardWrap.style.width = `${Math.floor(size)}px`;
      const cell = w < 420 ? 22 : 26;
      if (cell !== trayCell) {
        trayCell = cell;
        sync();
      }
    },
    destroy() {
      if (drag) endDrag();
      stopLayer();
      layerFit.stop();
      trayPictures.forEach((pic) => pic.stop());
      board.destroy();
    },
  };
}

// ------------------------------------------------------------------ battle

function battleStage(session, { local, showVerdict, onRematch, onMenu }) {
  const enemyBoard = new Board({ label: "Opponent's waters: choose a square to fire at", onTap: (at) => session.fire(at) });
  const myBoard = new Board({ label: 'Your fleet' });
  const enemyTally = new FleetTally();
  const myTally = new FleetTally();
  const enemyTitle = h('b');
  const enemyDetail = h('span');
  const myTitle = h('b');
  const myDetail = h('span');
  const enemyCol = h('section', { class: 'board-col enemy' }, h('div', { class: 'board-head' }, enemyTitle, enemyDetail), enemyBoard.canvas, enemyTally.canvas);
  const myCol = h('section', { class: 'board-col mine' }, h('div', { class: 'board-head' }, myTitle, myDetail), myBoard.canvas, myTally.canvas);
  const boards = h('div', { class: 'boards' }, enemyCol, myCol);
  const turn = h('p', { class: 'turn' });
  const banner = h('div', { class: 'banner' });
  const el = h('div', { class: 'battle' }, banner, boards);
  let bannerKey = '';
  let size = [0, 0];

  // Shots made before this view appeared are shown, not replayed; the last one fired at us does replay.
  enemyBoard.newest(session.state.lastMyShot, false);

  function turnText(s) {
    const opp = s.opponentName || 'Opponent';
    if (s.aiming !== null) return [`Firing at ${label(s.aiming)}…`, Sea.amber];
    if (s.myTurn) return [local ? `${s.myName}, your turn: tap a square in ${opp}'s waters` : `Your turn: tap a square in ${opp}'s waters`, Sea.sonar];
    return [`${opp} is taking aim…`, Sea.textDim];
  }

  function resultPanel(s) {
    const opp = s.opponentName || 'Opponent';
    const won = s.won === true;
    const heading = local ? `${won ? s.myName : opp} wins!` : won ? 'VICTORY' : 'DEFEAT';
    const summary = won
      ? `${local ? s.myName : 'You'} sank ${opp}'s fleet in ${s.myShots.size} shots.`
      : `${opp} sank your fleet. Their remaining ships are now shown.`;
    let verdict = null;
    if (showVerdict) {
      const [text, color] = {
        [Verdict.PENDING]: ['Checking their fleet against their reports…', Sea.textDim],
        [Verdict.FAIR]: ['✓ Fair game: every report matched the fleet they locked in.', Sea.sonar],
        [Verdict.SUSPICIOUS]: [`⚠ ${s.verdictNote}`, Sea.danger],
      }[s.verdict];
      verdict = h('p', { class: 'verdict', text });
      verdict.style.color = color;
    }
    let again = local ? 'Play again' : 'Rematch';
    if (!local && s.iWantRematch) again = `Waiting for ${opp}…`;
    else if (!local && s.opponentWantsRematch) again = 'Accept rematch';
    const rematch = btn(again, onRematch, 'sonar');
    rematch.disabled = !!s.disconnected || (!local && s.iWantRematch);
    return h(
      'div',
      { class: `result ${won ? 'won' : 'lost'}` },
      h('div', { class: 'result-text' }, h('p', { class: 'result-title', text: heading }), h('p', { class: 'summary', text: summary }), verdict),
      h(
        'div',
        { class: 'result-actions' },
        !local && s.opponentWantsRematch && !s.iWantRematch && h('p', { class: 'wants', text: `${opp} wants a rematch!` }),
        h('div', { class: 'row' }, rematch, btn('Menu', onMenu, 'quiet')),
      ),
    );
  }

  function layout(w, hgt) {
    if (w) size = [w, hgt];
    const [W, H] = size;
    if (!W) return;
    const avail = H - banner.offsetHeight - 14;
    // Room each board needs besides its squares: the heading, and the ship tally (hidden on short screens).
    const extras = enemyCol.firstChild.offsetHeight + enemyTally.canvas.offsetHeight + 14;
    const gap = 18;
    const sideBySide = Math.min((W - gap) / 2, avail - extras, 640);
    const room = avail - 2 * extras - gap;
    const stackedEnemy = Math.min(W, room * 0.58, 640);
    const side = sideBySide >= stackedEnemy * 0.9;
    boards.classList.toggle('side', side);
    let enemy = sideBySide;
    let mine = sideBySide;
    if (!side) {
      enemy = Math.max(stackedEnemy, Math.min(W, 300));
      mine = Math.max(Math.min(W, room * 0.42, 520), Math.min(W * 0.7, 210));
    }
    enemyCol.style.width = `${Math.floor(enemy)}px`;
    myCol.style.width = `${Math.floor(mine)}px`;
  }

  return {
    el,
    layout,
    update(s) {
      const over = s.phase === Phase.GAME_OVER;
      const opp = s.opponentName || 'Opponent';
      const fire = canFire(s);
      const shown = over && s.enemyFleet
        ? Object.values(s.enemyFleet).filter((p) => !s.enemySunk.some((k) => k.type === p.type)).map((p) => ({ p, look: 'revealed' }))
        : [];
      enemyBoard.ships = [...s.enemySunk.map((p) => ({ p, look: 'sunk' })), ...shown];
      enemyBoard.shots = s.myShots;
      enemyBoard.aiming = s.aiming;
      enemyBoard.highlight = fire;
      enemyBoard.newest(s.lastMyShot);
      myBoard.ships = s.fleet ? Object.values(s.fleet).map((p) => ({ p, look: s.mySunk.has(p.type) ? 'sunk' : 'normal' })) : [];
      myBoard.shots = s.shotsAtMe;
      myBoard.newest(s.lastShotAtMe);
      myBoard.dimmed = fire;
      enemyTally.set(s.enemySunk.map((p) => p.type));
      myTally.set(s.mySunk);
      enemyCol.classList.toggle('active', fire);

      const hits = [...s.myShots.values()].filter(Boolean).length;
      const n = s.myShots.size;
      enemyTitle.textContent = `${opp}'s waters`;
      enemyDetail.textContent = n ? `Shots ${n} · Hits ${hits} · ${Math.floor((hits * 100) / n)}%` : 'No shots yet';
      myTitle.textContent = local ? `${s.myName}'s fleet` : 'Your fleet';
      myDetail.textContent = `${SHIPS.length - s.mySunk.size} of ${SHIPS.length} afloat`;

      if (!over) {
        const [text, color] = turnText(s);
        turn.textContent = text;
        turn.style.color = color;
        if (bannerKey !== 'turn') {
          bannerKey = 'turn';
          banner.replaceChildren(turn);
          layout();
        }
      } else {
        const key = `over|${s.verdict}|${s.iWantRematch}|${s.opponentWantsRematch}|${!!s.disconnected}`;
        if (key !== bannerKey) {
          bannerKey = key;
          banner.replaceChildren(resultPanel(s));
          layout();
        }
      }
    },
    destroy() {
      enemyBoard.destroy();
      myBoard.destroy();
      enemyTally.destroy();
      myTally.destroy();
    },
  };
}

// ------------------------------------------------------------------ start

const joinCode = normaliseCode(new URLSearchParams(location.search).get('join'));
if (isCode(joinCode)) {
  // Opened from a host's invite: tidy the address so a reload doesn't try to join again, then connect.
  history.replaceState(null, '', location.pathname);
  show(joinView(joinCode));
} else {
  show(homeView());
}
