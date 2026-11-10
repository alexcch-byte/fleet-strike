// Playing a friend on another device. Browsers can't use Bluetooth or open sockets on the local network the way the
// Android app does, so two pages find each other through PeerJS's free matchmaking server using a short game code, then
// talk directly over WebRTC (relayed through PeerJS's TURN server only if a direct path can't be found).

const PEERJS = {
  src: 'https://cdnjs.cloudflare.com/ajax/libs/peerjs/1.5.4/peerjs.min.js',
  integrity: 'sha512-iFU+yF1keEaLDC9HEwPfLMSRaS0unBHE14GEgx6pQKJXjp5v0tvX8xpfp2lgJ62XEjbYp/M5C3CAmej/PWXMyA==',
};
const QRCODE = {
  src: 'https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js',
  integrity: 'sha512-CNgIRecGo7nphbeZ04Sc13ka07paqdeTu0WR1IM4kNcpmBAUSHSQX0FslNhTDadL4O5SAGapGt4FodqL8My0mA==',
};

/** Keeps our games apart from everyone else's on the shared matchmaking server. */
const ID_PREFIX = 'fleetstrike-web1-';
/** No look-alikes: O/0, I/1/L, S/5, B/8, Z/2 are left out so codes are easy to read across a table. */
const CODE_LETTERS = 'ACDEFGHJKMNPQRTUVWXY34679';
export const CODE_LENGTH = 4;

const BEAT_MS = 1500;
/** Longer than the app's 10 s: phones pause background pages, and a quick look at another app shouldn't end the game. */
const SILENCE_MS = 20000;
const CONNECT_TIMEOUT_MS = 25000;

const loaded = {};

function loadScript({ src, integrity }, global) {
  if (window[global]) return Promise.resolve(window[global]);
  loaded[src] ??= new Promise((resolve, reject) => {
    const s = document.createElement('script');
    s.src = src;
    s.integrity = integrity;
    s.crossOrigin = 'anonymous';
    s.onload = () => (window[global] ? resolve(window[global]) : reject(new Error('missing')));
    s.onerror = () => {
      delete loaded[src];
      reject(new Error("Couldn't load the connection library. Check this device is online."));
    };
    document.head.appendChild(s);
  });
  return loaded[src];
}

export const loadQrCode = () => loadScript(QRCODE, 'QRCode');

export function normaliseCode(text) {
  return String(text ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, CODE_LENGTH);
}

export const isCode = (code) => code.length === CODE_LENGTH && [...code].every((ch) => CODE_LETTERS.includes(ch));

function newCode() {
  const bytes = new Uint8Array(CODE_LENGTH);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => CODE_LETTERS[b % CODE_LETTERS.length]).join('');
}

function describe(err, code) {
  switch (err?.type) {
    case 'peer-unavailable':
      return `No game with code ${code}. Check the code on the host's screen, and that their page is still open.`;
    case 'network':
    case 'server-error':
    case 'socket-error':
    case 'socket-closed':
      return "Couldn't reach the Fleet Strike matchmaker. Check this device is online.";
    case 'browser-incompatible':
      return "This browser can't make direct connections. Try Chrome, Edge, Firefox or Safari.";
    case 'webrtc':
      return "The devices couldn't connect to each other. Try putting both on the same Wi-Fi.";
    case 'unavailable-id':
      return "Couldn't get a game code just now. Try again.";
    default:
      return `Something went wrong connecting (${err?.type ?? err?.message ?? 'unknown'}).`;
  }
}

/**
 * A line-based connection over a PeerJS data channel, shaped like the in-memory one: send(line), close(), onLine,
 * onClose. A heartbeat goes each way so a device that vanishes (closed tab, lost signal) is noticed.
 */
class PeerLink {
  constructor(conn) {
    this.conn = conn;
    this.closed = false;
    this.queue = [];
    this.handler = null;
    this.onClose = null;
    this.heard = Date.now();
    this.beat = null;
    conn.on('data', (data) => {
      if (this.closed) return;
      this.heard = Date.now();
      if (data === '~') return;
      if (this.handler) this.handler(String(data));
      else this.queue.push(String(data));
    });
    conn.on('close', () => this.finish());
    if (conn.open) this.startBeat();
    else conn.on('open', () => this.startBeat());
  }

  /** Lines that arrived before anyone was listening are handed over on the next tick. */
  set onLine(fn) {
    this.handler = fn;
    const waiting = this.queue;
    this.queue = [];
    if (waiting.length) setTimeout(() => waiting.forEach((line) => this.handler?.(line)), 0);
  }

  get onLine() {
    return this.handler;
  }

  startBeat() {
    this.heard = Date.now();
    clearInterval(this.beat);
    this.beat = setInterval(() => {
      if (Date.now() - this.heard > SILENCE_MS) {
        this.finish();
        try {
          this.conn.close();
        } catch {
          // Already gone.
        }
      } else {
        this.raw('~');
      }
    }, BEAT_MS);
  }

  raw(text) {
    if (this.closed || !this.conn.open) return;
    try {
      this.conn.send(text);
    } catch {
      // A failed send shows up as silence and is handled by the heartbeat.
    }
  }

  send(line) {
    this.raw(line);
  }

  /** Closing waits a moment so a last message (like "bye") is on its way first. */
  close() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.beat);
    setTimeout(() => {
      try {
        this.conn.close();
      } catch {
        // Already gone.
      }
    }, 400);
  }

  finish() {
    if (this.closed) return;
    this.closed = true;
    clearInterval(this.beat);
    this.onClose?.();
  }
}

/**
 * Hosts a game: gets a code from the matchmaker and waits for one friend. Calls onCode(code) once others can join,
 * onStatus(text) with news while waiting, onConnected(link) when the friend is through, and onError(text) if it can't
 * go on. close() stops waiting, and after a game also releases the code.
 */
export function hostGame({ onCode, onStatus, onConnected, onError }) {
  let peer = null;
  let stopped = false;
  let joined = false;
  let attempts = 0;

  const start = async () => {
    let Peer;
    try {
      Peer = await loadScript(PEERJS, 'Peer');
    } catch (e) {
      if (!stopped) onError(e.message);
      return;
    }
    if (stopped) return;
    const code = newCode();
    const me = new Peer(ID_PREFIX + code, { debug: 0 });
    peer = me;
    me.on('open', () => {
      if (!stopped && peer === me) onCode(code);
    });
    me.on('connection', (conn) => {
      if (stopped || joined) {
        conn.on('open', () => conn.close());
        return;
      }
      joined = true;
      const link = new PeerLink(conn);
      const giveUp = setTimeout(() => {
        if (conn.open) return;
        joined = false;
        conn.close();
        onStatus?.("Someone tried to join but their device couldn't connect. Still waiting…");
      }, CONNECT_TIMEOUT_MS);
      conn.on('open', () => {
        clearTimeout(giveUp);
        if (!stopped) onConnected(link);
      });
    });
    me.on('disconnected', () => {
      // Lost the matchmaker while still waiting: try to get the same code back. Once playing, it isn't needed.
      if (!stopped && !joined && !me.destroyed) me.reconnect();
    });
    me.on('error', (err) => {
      if (stopped || peer !== me) return;
      if (err.type === 'unavailable-id' && attempts++ < 5) {
        me.destroy();
        start();
        return;
      }
      if (joined) return;
      onError(describe(err));
    });
  };

  start();
  return {
    close() {
      stopped = true;
      peer?.destroy();
    },
  };
}

/** Joins the game with this code. Calls onConnected(link) or onError(text); close() gives up. */
export function joinGame(code, { onConnected, onError }) {
  let peer = null;
  let stopped = false;
  let done = false;
  let timer = null;
  const fail = (text) => {
    if (done || stopped) return;
    done = true;
    clearTimeout(timer);
    peer?.destroy();
    onError(text);
  };

  (async () => {
    let Peer;
    try {
      Peer = await loadScript(PEERJS, 'Peer');
    } catch (e) {
      fail(e.message);
      return;
    }
    if (stopped) return;
    peer = new Peer({ debug: 0 });
    timer = setTimeout(() => fail(`Couldn't connect to game ${code}. Check the code, and that the host's page is still open.`), CONNECT_TIMEOUT_MS);
    peer.on('open', () => {
      if (stopped || done) return;
      const conn = peer.connect(ID_PREFIX + code, { reliable: true, serialization: 'raw' });
      const link = new PeerLink(conn);
      conn.on('open', () => {
        if (stopped || done) return;
        done = true;
        clearTimeout(timer);
        onConnected(link);
      });
    });
    peer.on('error', (err) => fail(describe(err, code)));
  })();

  return {
    close() {
      stopped = true;
      clearTimeout(timer);
      peer?.destroy();
    },
  };
}
