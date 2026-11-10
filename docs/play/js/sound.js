// Sound effects, synthesised in the browser from the same recipes as the app (audio/SoundFx.kt). No audio files to load.

const RATE = 22050;

/** A tiny seeded generator, so each effect's noise is the same every time, as in the app. */
function noise(seed) {
  let x = seed * 2654435761 >>> 0 || 1;
  return () => {
    x ^= x << 13;
    x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5;
    x >>>= 0;
    return (x / 0x100000000) * 2 - 1;
  };
}

const render = (seconds, f) => Float32Array.from({ length: Math.floor(seconds * RATE) }, (_, i) => f(i / RATE));

function lowPass(samples, k) {
  let y = 0;
  const out = samples.map((s) => (y += k * (s - y)));
  const peak = out.reduce((m, s) => Math.max(m, Math.abs(s)), 1e-4);
  return out.map((s) => (s / peak) * 0.9);
}

function notes(freqs, each, last) {
  const parts = freqs.map((f, n) => {
    const len = n === freqs.length - 1 ? last : each;
    return render(len, (t) => {
      const env = (1 - Math.exp(-60 * t)) * Math.exp((-3 * t) / len);
      return 0.35 * env * (Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(4 * Math.PI * f * t));
    });
  });
  const out = new Float32Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

const { sin, exp, PI, max } = Math;

const RECIPES = {
  launch: (rnd) => render(0.35, (t) => 0.25 * sin(2 * PI * max(900 - 1800 * t, 150) * t) * exp(-6 * t) + 0.35 * rnd() * exp(-9 * t)),
  splash: (rnd) => lowPass(render(0.6, (t) => 0.5 * sin(2 * PI * (320 - 260 * t) * t) * exp(-14 * t) + 0.8 * rnd() * exp(-7 * t) * (1 - exp(-80 * t))), 0.25),
  boom: (rnd) => lowPass(render(0.9, (t) => 0.9 * sin(2 * PI * (75 - 35 * t) * t) * exp(-4.5 * t) + 0.9 * rnd() * exp(-5 * t)), 0.12),
  sink: (rnd) => lowPass(render(1.4, (t) => 0.4 * sin(2 * PI * (220 - 140 * t) * t) * exp(-1.6 * t) + 0.9 * rnd() * exp(-3.5 * t) + 0.6 * sin(2 * PI * 55 * t) * exp(-3 * t)), 0.1),
  win: () => notes([523.25, 659.25, 783.99, 1046.5], 0.14, 0.45),
  lose: () => notes([392, 329.63, 261.63, 196], 0.2, 0.6),
  click: () => render(0.05, (t) => 0.5 * sin(2 * PI * 1400 * t) * exp(-70 * t)),
};

/** Plays effects by name. Browsers only allow sound after the player has tapped something, so it starts lazily. */
export class Sound {
  constructor(enabled) {
    this.enabled = enabled;
    this.ctx = null;
    this.buffers = {};
  }

  /** Call from a tap or click: creates (or wakes) the audio context. */
  unlock() {
    if (!this.enabled) return;
    try {
      if (!this.ctx) {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        this.ctx = new Ctx();
        Object.keys(RECIPES).forEach((name, i) => {
          const samples = RECIPES[name](noise(i + 7));
          const buffer = this.ctx.createBuffer(1, samples.length, RATE);
          buffer.copyToChannel(samples, 0);
          this.buffers[name] = buffer;
        });
      }
      if (this.ctx.state === 'suspended') this.ctx.resume();
    } catch {
      this.ctx = null;
    }
  }

  play(name, volume = 0.9) {
    if (!this.enabled || !this.ctx || !this.buffers[name]) return;
    try {
      const source = this.ctx.createBufferSource();
      source.buffer = this.buffers[name];
      const gain = this.ctx.createGain();
      gain.gain.value = volume;
      source.connect(gain).connect(this.ctx.destination);
      source.start();
    } catch {
      // A blocked or closed audio context just means no sound.
    }
  }
}
