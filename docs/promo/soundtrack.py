"""Synthesizes the promo soundtrack (122 BPM, A minor, punchy house/garage groove) into soundtrack.wav.

Everything is generated from oscillators and noise: no third-party samples, no licensing questions.
Drums, bass and music are mixed on separate buses so the kick can duck the music (sidechain)
without flattening its own transient. Section boundaries follow the scene timings in promo.html.
usage: python soundtrack.py [out.wav]   (needs numpy)
"""
import sys
import wave

import numpy as np

SR = 48000
BPM = 122
BEAT = 60 / BPM
S16 = BEAT / 4
BAR = 4 * BEAT
DUR = 57.0
N = int(SR * DUR)
rng = np.random.default_rng(11)

BUS = {k: np.zeros((N, 2)) for k in ('drums', 'bass', 'music', 'fx')}

# timeline (seconds), aligned to the scenes
DROP = 3.0               # phone lands
PRIVATE = (18.7, 23.0)   # half-time, filtered
BREAK = (44.6, 48.5)     # licenses: breakdown, then rebuild
END = 52.6               # logo outro impact


def add(bus, sig, t0, gain=1.0, pan=0.0):
    i = int(round(t0 * SR))
    if i >= N or i < 0:
        return
    sig = sig[: N - i] * gain
    BUS[bus][i:i + len(sig), 0] += sig * min(1, 1 - pan)
    BUS[bus][i:i + len(sig), 1] += sig * min(1, 1 + pan)


def tt(n):
    return np.arange(n) / SR


def env(n, a=0.002, d=0.2):
    t = tt(n)
    return np.minimum(t / a, 1) * np.exp(-t / d)


def hz(m):
    return 440 * 2 ** ((m - 69) / 12)


def band_noise(n, lo, hi):
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    spec[(f < lo) | (f > hi)] = 0
    x = np.fft.irfft(spec, n)
    return x / (np.abs(x).max() + 1e-9)


def saw(f, n, harmonics=None, detune=0.0, bright=None):
    """Additive saw; `bright` (array 0..1) scales upper harmonics over time, like a filter envelope."""
    t = tt(n)
    k_max = harmonics or int(min(48, (SR / 2) / f))
    out = np.zeros(n)
    for k in range(1, k_max + 1):
        amp = 1 / k
        if bright is not None:
            amp = amp * np.clip(bright * k_max / k, 0, 1) if k > 1 else amp
        out += np.sin(2 * np.pi * k * f * (1 + detune) * t) * amp
    return out


# ---------------- drums ----------------
def kick(v=1.0):
    n = int(0.5 * SR)
    t = tt(n)
    f = 48 + 170 * np.exp(-t / 0.028) + 40 * np.exp(-t / 0.004)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR)
    body = np.tanh(body * 1.15 * np.exp(-t / 0.3)) * np.minimum(t / 0.0015, 1)
    click = band_noise(n, 2500, 9000) * np.exp(-t / 0.004) * 0.35
    return (body + click) * v


def snare(v=1.0):
    n = int(0.35 * SR)
    t = tt(n)
    tone = np.sin(2 * np.pi * np.cumsum(185 + 60 * np.exp(-t / 0.02)) / SR) * np.exp(-t / 0.07)
    rattle = band_noise(n, 1500, 11000) * np.exp(-t / 0.16)
    return (tone * 0.6 + rattle * 0.7) * 0.5 * v


def clap(v=1.0):
    n = int(0.35 * SR)
    t = tt(n)
    burst = sum(np.exp(-np.maximum(t - o, 0) / 0.008) * (t >= o) for o in (0, 0.009, 0.019, 0.028))
    return band_noise(n, 900, 6000) * (burst * 0.55 + np.exp(-t / 0.14) * 0.8) * 0.4 * v


def hat(v=1.0, open_=False):
    n = int((0.28 if open_ else 0.05) * SR)
    metal = sum(np.sign(np.sin(2 * np.pi * f * tt(n))) for f in (3130, 4630, 5720, 7010, 8350)) / 5
    x = band_noise(n, 7000, 18000) * 0.7 + metal * 0.3
    return x * env(n, 0.0008, 0.1 if open_ else 0.016) * 0.22 * v


def shaker(v=1.0):
    n = int(0.07 * SR)
    t = tt(n)
    return band_noise(n, 5000, 14000) * np.minimum(t / 0.012, 1) * np.exp(-t / 0.02) * 0.12 * v


def rim(v=1.0):
    n = int(0.08 * SR)
    t = tt(n)
    return (np.sin(2 * np.pi * 1700 * t) * 0.6 + band_noise(n, 2000, 7000) * 0.4) * np.exp(-t / 0.012) * 0.3 * v


def tom(m, v=1.0):
    n = int(0.3 * SR)
    t = tt(n)
    f = hz(m) * (1 + 0.6 * np.exp(-t / 0.03))
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.15) * 0.5 * v


def crash(v=1.0):
    n = int(2.2 * SR)
    return band_noise(n, 4000, 16000) * env(n, 0.001, 0.7) * 0.22 * v


# ---------------- music ----------------
def pluck(m, length=0.2, v=1.0):
    n = int(length * SR)
    b = np.exp(-tt(n) / 0.05) * 0.9 + 0.1
    return saw(hz(m), n, harmonics=16, bright=b) * env(n, 0.002, length / 3) * v


def stab(chord, length=0.16, v=1.0):
    n = int(length * SR)
    b = np.exp(-tt(n) / 0.04) * 0.8 + 0.2
    return sum(saw(hz(m), n, harmonics=14, detune=d, bright=b) for m in chord for d in (-0.006, 0.006)) * env(n, 0.002, 0.07) * 0.18 * v


def pad(chord, length, bright=0.25):
    n = int(length * SR)
    t = tt(n)
    x = sum(saw(hz(m), n, harmonics=8, detune=d, bright=np.full(n, bright)) for m in chord for d in (-0.005, 0.0, 0.005))
    return x * np.minimum(t / 0.5, 1) * np.minimum((length - t) / 0.5, 1) * 0.04


def bass(m, length, v=1.0, dark=False):
    n = int(length * SR)
    b = (np.exp(-tt(n) / 0.05) * 0.5 + 0.1) * (0.4 if dark else 1)
    mid = saw(hz(m), n, harmonics=8, bright=b)
    sub = np.sin(2 * np.pi * hz(m) * tt(n)) * 1.1
    e = np.minimum(tt(n) / 0.004, 1) * np.minimum((length - tt(n)) / 0.01, 1)
    return (mid * 0.3 + sub) * e * 0.3 * v   # clean: no saturation, the mids only add definition


def riser(length):
    n = int(length * SR)
    t = tt(n) / length
    noise = band_noise(n, 1500, 14000) * t ** 2 * 0.3
    sweep = np.sin(2 * np.pi * np.cumsum(150 + 2200 * t ** 2) / SR) * t ** 3 * 0.12
    return noise + sweep


def downlifter(length):
    n = int(length * SR)
    t = tt(n) / length
    return band_noise(n, 800, 9000) * (1 - t) ** 2 * 0.25


def impact():
    n = int(4.0 * SR)
    t = tt(n)
    boom = np.sin(2 * np.pi * np.cumsum(36 + 70 * np.exp(-t / 0.07)) / SR) * np.exp(-t / 1.1)
    return boom * 0.8 + band_noise(n, 300, 12000) * np.exp(-t / 0.6) * 0.25


# i - VI - III - VII in A minor, with 7ths for colour
PROG = [(45, [69, 72, 76, 79]), (41, [65, 69, 72, 76]), (48, [67, 72, 76, 79]), (43, [67, 71, 74, 77])]
BASS_PAT = [  # (16th step, semitone offset, length in 16ths, velocity)
    (0, 0, 2, 1.0), (3, 0, 1, .7), (6, 12, 1, .8), (8, 0, 2, .9), (10, 7, 1, .7), (11, 0, 1, .6), (14, 10, 2, .85)]
ARP = [0, 2, 1, 3, 2, 0, 3, 1, 0, 2, 3, 1, 2, 3, 1, 2]
STABS = [3, 6, 10, 14]
SWING = 0.18 * S16   # delay applied to odd 16ths


def sw(step):
    return step * S16 + (SWING if step % 2 else 0)


def in_(t, rng_):
    return rng_[0] <= t < rng_[1]


sidechain = np.ones(N)


def duck(t0, depth=0.8, rel=0.13):
    i = int(t0 * SR)
    n = min(int(BEAT * SR), N - i)
    if n <= 0:
        return
    d = 1 - depth * np.exp(-tt(n) / rel)
    sidechain[i:i + n] = np.minimum(sidechain[i:i + n], d)


def fill(t0, kind):
    """One-bar-end fill on the last two beats before t0 + BAR."""
    base = t0 + 2 * BEAT
    if kind == 'snare':
        for s in range(8):
            add('drums', snare(0.35 + s * 0.08), base + s * S16, 1, (s % 2 - .5) * .3)
    else:
        for s, m in enumerate([50, 50, 47, 47, 45, 43, 41, 38]):
            add('drums', tom(m, 0.8), base + s * S16, 1, 0.5 - s * 0.14)


# ---------------- arrangement ----------------
add('music', pad(PROG[0][1], DROP + 0.3, bright=0.1), 0, 1.6)
add('fx', riser(DROP), 0, 1.0)
for s in range(8):  # snare build into the drop
    add('drums', snare(0.2 + s * 0.1), DROP - BEAT * 2 + s * S16, 0.9)

t = DROP
bar = 0
while t < END - 1e-6:
    root, chord = PROG[bar % 4]
    priv, brk = in_(t, PRIVATE), in_(t, BREAK)
    act2 = t >= 9.0
    act3 = t >= 23.0 and not brk
    last_of_phrase = bar % 4 == 3
    next_t = t + BAR

    if not brk and (bar % 8 == 0 or abs(t - PRIVATE[1]) < BAR / 2):
        add('drums', crash(0.9), t, 1, 0.2)

    add('music', pad(chord, BAR + 0.3, bright=0.12 if (priv or brk) else 0.3), t, 1.3 if (priv or brk) else 1.0)

    # --- drums
    if brk:
        if t >= BREAK[1] - BAR:   # rebuild: kicks on quarters, 16th snare roll
            for b in range(4):
                add('drums', kick(0.8), t + b * BEAT)
            for s in range(16):
                add('drums', snare(0.15 + s * 0.05), t + s * S16, 0.8)
        else:
            for b in range(4):
                add('drums', rim(0.6), t + b * BEAT + 2 * S16, 1, 0.4)
    elif priv:  # half-time
        add('drums', kick(0.9), t)
        add('drums', kick(0.6), t + sw(10))
        add('drums', snare(0.8), t + 2 * BEAT)
        add('drums', clap(0.6), t + 2 * BEAT)
        for s in range(0, 16, 2):
            add('drums', hat(0.5 + 0.2 * (s % 4 == 2)), t + sw(s), 1, -0.3)
        duck(t)
        duck(t + 2 * BEAT, 0.5)
    else:
        for b in range(4):
            add('drums', kick(1.0), t + b * BEAT)
            duck(t + b * BEAT)
        if act2 and bar % 2:
            add('drums', kick(0.55), t + sw(15) - BEAT * 0)  # pickup kick
        for b in (1, 3):
            add('drums', clap(1.0), t + b * BEAT, 1, 0.05)
            add('drums', snare(0.55), t + b * BEAT, 1, -0.05)
        for s in (7, 9, 13) if act2 else ():  # ghost snares
            add('drums', snare(0.18), t + sw(s), 1, 0.25)
        for s in range(16):
            if s % 4 == 2:
                add('drums', hat(1.0, open_=(s == 14 and bar % 2 == 1)), t + sw(s), 1, -0.25)
            elif act3 or (act2 and s % 2 == 0):
                v = 0.55 + 0.25 * rng.random()
                add('drums', hat(v * (0.7 if s % 2 else 1)), t + sw(s), 1, 0.25)
        if act2:
            for s in range(1, 16, 2):
                add('drums', shaker(0.6 + 0.4 * rng.random()), t + sw(s), 1, 0.5)
            for s in (3, 11) if bar % 2 else (6,):
                add('drums', rim(0.7), t + sw(s), 1, -0.45)
        if last_of_phrase and next_t < END - 0.01 and not in_(next_t, BREAK):
            fill(t, 'toms' if bar % 8 == 7 else 'snare')

    # --- bass
    if not brk or t >= BREAK[1] - BAR:
        for step, off, ln, v in BASS_PAT:
            if priv and step % 4:
                continue
            if last_of_phrase and step >= 8 and not priv:
                continue  # leave room for the fill
            add('bass', bass(root + off - (12 if priv else 0), ln * S16 * 0.92, v, dark=priv), t + sw(step))

    # --- stabs & arp
    if act2 and not priv and not brk:
        for s in STABS:
            add('music', stab([m - 12 for m in chord[:3]], v=0.9), t + sw(s), 1, 0.15 if s % 2 else -0.15)
    if (act2 and not priv) or brk:
        for s, idx in enumerate(ARP):
            if brk or act3 or s % 2 == 0:
                add('music', pluck(chord[idx] + 12, 0.18, 0.55 if brk else 0.7), t + sw(s), 0.14, 0.45 if s % 2 else -0.45)
    t = next_t
    bar += 1

add('fx', downlifter(2.0), PRIVATE[0], 0.8)
add('fx', riser(2 * BAR), PRIVATE[1] - 2 * BAR, 0.7)
add('fx', downlifter(2.0), BREAK[0], 0.8)
add('fx', riser(2 * BAR), BREAK[1] - 2 * BAR, 0.9)
add('fx', riser(BAR), END - BAR, 1.0)
add('fx', impact(), END, 1.0)
add('drums', crash(1.2), END)
add('music', pad([57, 64, 69, 72, 76, 79], DUR - END, bright=0.35), END, 1.9)
for k, m in enumerate([81, 84, 88, 91, 93]):
    add('music', pluck(m, 0.6, 0.6), END + 0.25 + k * S16 * 2, 0.2, (k - 2) * 0.2)


# ---------------- mix ----------------
def delay(x, time, fb=0.35, mix=0.25):
    d = int(time * SR)
    out = x.copy()
    tap = x.copy()
    for _ in range(4):
        tap = np.roll(tap, d, axis=0) * fb
        tap[:d] = 0
        tap = tap[:, ::-1]   # ping-pong
        out += tap * mix / fb
    return out


def limiter(x, ceiling=10 ** (-1.0 / 20), look=0.005, release=0.12):
    """Look-ahead peak limiter: clean gain reduction instead of saturation."""
    blk = 64
    nb = -(-len(x) // blk)
    peaks = np.pad(np.abs(x).max(1), (0, nb * blk - len(x))).reshape(nb, blk).max(1)
    w = max(1, int(look * SR / blk))
    ahead = np.array([peaks[i:i + w + 1].max() for i in range(nb)])
    need = np.minimum(1, ceiling / np.maximum(ahead, 1e-9))
    g = np.empty(nb)
    cur = 1.0
    coef = np.exp(-blk / (release * SR))
    for i in range(nb):
        cur = need[i] if need[i] < cur else need[i] + (cur - need[i]) * coef
        g[i] = cur
    gain = np.interp(np.arange(len(x)), np.arange(nb) * blk + blk / 2, g)
    return x * gain[:, None]


music = delay(BUS['music'], 3 * S16) * sidechain[:, None]
bass_bus = BUS['bass'] * (0.05 + 0.95 * sidechain[:, None] ** 2)   # bass fully out of the kick's way
mix = BUS['drums'] * 1.0 + bass_bus * 0.3 + music * 0.85 + BUS['fx'] * 0.7
mix *= 10 ** (-14.5 / 20) / np.sqrt(np.mean(mix ** 2))   # about -14 LUFS after limiting
pre = mix.copy()
mix = limiter(mix)
print(f"pre-limiter peak {20 * np.log10(np.abs(pre).max()):.1f} dBFS, gain reduction on {np.mean(np.abs(mix).max(1) < np.abs(pre).max(1) * 0.99) * 100:.2f}% of samples")
fade = np.minimum(1, (DUR - tt(N)) / 2.5)[:, None]
mix *= fade

out = sys.argv[1] if len(sys.argv) > 1 else 'soundtrack.wav'
with wave.open(out, 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((mix * 32767).astype('<i2').tobytes())
print('wrote', out)
