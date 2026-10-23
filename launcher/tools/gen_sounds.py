"""
Generates Plume's three interface sounds into app/src/main/res/raw (they're synthesised, not recordings).

    python3 tools/gen_sounds.py            (needs numpy and scipy)

  nav_tick.wav    focus moved: a short thud, 310 Hz settling to 220 Hz
  nav_edge.wav    pressed past the end of a row: the same thud, lower (170 Hz to 120 Hz)
  nav_select.wav  OK: a warm fifth (262 + 392 Hz) that swells in, with a short dark reverb

Tune by ear on the TV's own speakers: they barely play anything below ~150 Hz.
"""
import os, sys, wave
import numpy as np
from scipy.signal import lfilter, butter, sosfilt

SR = 44100
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "raw")


def write(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())


def db(d):
    return 10 ** (d / 20)


# ---------- the thuds ----------

def env(n, attack, tau):
    t = np.arange(n) / SR
    return np.clip(t / attack, 0, 1) * np.exp(-t / tau)


def lowpass(x, cutoff):
    """One-pole low-pass, run twice: soft, no harsh edges."""
    a = np.exp(-2 * np.pi * cutoff / SR)
    for _ in range(2):
        y = np.zeros_like(x); p = 0.0
        for i, v in enumerate(x):
            p = (1 - a) * v + a * p; y[i] = p
        x = y
    return x


def thud(seconds, f_start, f_drop, glide, attack, decay, cutoff, level_db):
    n = int(SR * seconds); t = np.arange(n) / SR
    f = f_start - f_drop * (1 - np.exp(-t / glide))
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, attack, decay)
    x = lowpass(x, cutoff)
    return x / np.max(np.abs(x)) * db(level_db)


# ---------- the select tone ----------

def norm(x, d):
    return x / np.max(np.abs(x)) * db(d)


def fade(x, ms=30):
    n = int(SR * ms / 1000); x[-n:] *= np.linspace(1, 0, n) ** 2
    return x


def bloom_env(t, attack, tau):
    """A soft S-shaped rise (no click), then an exponential decay."""
    a = np.clip(t / attack, 0, 1); a = a * a * (3 - 2 * a)
    return a * np.exp(-np.maximum(t - attack, 0) / tau)


def voice(t, f, detune_cents=4, h2=0.25, h3=0.08):
    """Two slightly detuned oscillators with a few harmonics: a gentle chorus width."""
    out = 0
    for c in (-detune_cents, detune_cents):
        ff = f * 2 ** (c / 1200); ph = 2 * np.pi * np.cumsum(np.broadcast_to(ff, t.shape)) / SR
        out = out + np.sin(ph) + h2 * np.sin(2 * ph) + h3 * np.sin(3 * ph)
    return out / 2


def bloom_filter(x, t, lo, hi, peak_at, tau):
    """A low-pass that opens to `hi` around `peak_at`, then closes again: the bloom."""
    y = np.zeros_like(x); blk = 256; zi = None
    for i in range(0, len(x), blk):
        tt = t[i]; k = np.clip(tt / peak_at, 0, 1) if tt < peak_at else np.exp(-(tt - peak_at) / tau)
        sos = butter(2, (lo + (hi - lo) * k) / (SR / 2), output="sos")
        if zi is None: zi = np.zeros((sos.shape[0], 2))
        y[i:i + blk], zi = sosfilt(sos, x[i:i + blk], zi=zi)
    return y


def reverb(x, wet=0.22, size=1.0, damp=0.35, fb=0.78):
    """Schroeder-style: four combs in parallel, two all-passes in series, then filtered so the tail stays dark."""
    tail = np.zeros(len(x))
    for d in [int(SR * d * size) for d in (0.0297, 0.0371, 0.0411, 0.0437)]:
        b = np.zeros(d + 1); b[d] = 1
        a = np.zeros(d + 1); a[0] = 1; a[d] = -fb * (1 - damp)
        tail += lfilter(b, a, x)
    for d, g in ((int(SR * 0.005), 0.7), (int(SR * 0.0017), 0.7)):
        b = np.zeros(d + 1); b[0] = -g; b[d] = 1
        a = np.zeros(d + 1); a[0] = 1; a[d] = -g
        tail = lfilter(b, a, tail)
    tail = sosfilt(butter(2, 2200 / (SR / 2), output="sos"), tail)
    tail = sosfilt(butter(1, 150 / (SR / 2), btype="high", output="sos"), tail)
    return x + wet * tail / np.max(np.abs(tail)) * np.max(np.abs(x))


def select():
    t = np.arange(int(SR * 0.6)) / SR
    x = (voice(t, 262, h2=0.35, h3=0.12) + 0.7 * voice(t, 392, h2=0.2)) * bloom_env(t, 0.014, 0.09)
    x = bloom_filter(x, t, 600, 2200, 0.025, 0.09)
    return norm(fade(reverb(x, wet=0.32, size=1.15)), -7)


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else OUT
    os.makedirs(out, exist_ok=True)
    write(os.path.join(out, "nav_tick.wav"), thud(0.07, 310, 90, 0.02, 0.0025, 0.016, 1600, -14))
    write(os.path.join(out, "nav_edge.wav"), thud(0.08, 170, 50, 0.025, 0.003, 0.02, 900, -16))
    write(os.path.join(out, "nav_select.wav"), select())
    print("Wrote nav_tick.wav, nav_edge.wav, nav_select.wav to", os.path.normpath(out))
