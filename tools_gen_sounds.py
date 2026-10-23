import numpy as np, wave, sys
SR = 44100
def write(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
def env(t, attack, tau): return np.clip(t / attack, 0, 1) * np.exp(-t / tau)
def onepole(x, cutoff, high=False):
    a = np.exp(-2*np.pi*cutoff/SR); y = np.zeros_like(x); p = 0.0
    for i, v in enumerate(x): p = (1-a)*v + a*p; y[i] = p
    return x - y if high else y
def sweep(t, f0, f1, tau): # pitch glides from f0 to f1
    f = f1 + (f0 - f1) * np.exp(-t / tau)
    return 2*np.pi*np.cumsum(f)/SR
def fade(x, ms=4): n = int(SR*ms/1000); x[-n:] *= np.linspace(1, 0, n); return x
def norm(x, d): return x / np.max(np.abs(x)) * 10**(d/20)
rng = np.random.default_rng(3)

# Move: a crisp glass tick. A short bright click (filtered noise, 1.5 ms) over a clean 2.9 kHz tone that
# settles from 3.4 kHz, with a faint 6.1 kHz shimmer. Clean sines and a high pitch read as glass, not wood.
t = np.arange(int(SR*0.05))/SR
click = onepole(onepole(rng.standard_normal(len(t)), 2500, high=True), 9000) * env(t, 0.0002, 0.0012)
tone = np.sin(sweep(t, 3400, 2900, 0.004)) * env(t, 0.0004, 0.009)
shim = np.sin(2*np.pi*6100*t) * env(t, 0.0002, 0.004)
tick = fade(0.55*click/np.max(np.abs(click)) + 0.8*tone + 0.18*shim)
tick = norm(tick, -11)

# Select: a rounded "pop", the pitch rising like a droplet (iOS toggles and AirDrop have this lift),
# with a soft octave on top and a tiny click at the front.
t = np.arange(int(SR*0.11))/SR
ph = sweep(t, 620, 1180, 0.012)
pop = (np.sin(ph) + 0.22*np.sin(2*ph)) * env(t, 0.0015, 0.024)
pop += 0.25*onepole(rng.standard_normal(len(t)), 6000) * env(t, 0.0002, 0.001)
pop = norm(fade(onepole(pop, 9000)), -8)

# Edge: a firm double knock, like the iOS "can't go further" bump. 240 Hz falling to 190 Hz, with its
# 2nd and 3rd harmonics so small TV speakers actually play it, lightly saturated for body. Two hits,
# the second softer, so it reads as "blocked" even under conversation.
def knock(n, level):
    t = np.arange(n)/SR
    ph = sweep(t, 240, 190, 0.02)
    k = (np.sin(ph) + 0.55*np.sin(2*ph) + 0.3*np.sin(3*ph)) * env(t, 0.0015, 0.022)
    return level*np.tanh(1.8*k)/np.tanh(1.8)
n = int(SR*0.16); edge = np.zeros(n)
edge[:int(SR*0.09)] += knock(int(SR*0.09), 1.0)
o = int(SR*0.065); edge[o:o+int(SR*0.09)] += knock(int(SR*0.09), 0.6)
edge = norm(fade(onepole(edge, 4000)), -5)

out = sys.argv[1]
write(f"{out}/nav_tick.wav", tick); write(f"{out}/nav_select.wav", pop); write(f"{out}/nav_edge.wav", edge)
# loudness check (rms in dBFS over the first 40 ms)
for nm, x in [("tick", tick), ("select", pop), ("edge", edge)]:
    s = x[:int(SR*0.04)]; print(nm, round(20*np.log10(np.sqrt(np.mean(s**2))),1), "dB rms")
