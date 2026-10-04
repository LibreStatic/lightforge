"""Synthesizes the promo soundtrack (bright electro-pop, 120 BPM, F major) into soundtrack.wav.

Everything is generated from oscillators and noise: no third-party samples, no licensing questions.
Four-on-the-floor drums, a bouncing octave bass and pumping supersaw chords carry I-V-vi-IV
(Fmaj7 - Cadd9 - Dm7 - Bbadd9); a sparkly arpeggio, a two-bar hook and, in the second half,
formant "vocal chops" sit on top. Section changes follow the scene timings in promo.html.
usage: python soundtrack.py [out.wav]   (needs numpy)
"""
import sys
import wave
from functools import lru_cache

import numpy as np

SR = 48000
BPM = 120
BEAT = 60 / BPM
S16 = BEAT / 4
BAR = 4 * BEAT
DUR = 84.8
N = int(SR * DUR)
rng = np.random.default_rng(5)

BUS = {k: np.zeros((N, 2)) for k in ('drums', 'bass', 'music', 'lead', 'fx')}

# timeline (seconds), aligned to the scenes; the groove runs on bars from DROP
DROP = 3.0               # phone lands
PRIVATE = (19.0, 23.0)   # private album: breakdown
LIFT = 45.0              # second run of feature scenes: chops, open hats, extra claps
BREAK = (73.0, 77.0)     # licenses: breakdown, then rebuild
END = 81.0               # logo outro impact


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
    k_max = harmonics or int(min(40, (SR / 2) / f))
    out = np.zeros(n)
    for k in range(1, k_max + 1):
        amp = 1 / k
        if bright is not None and k > 1:
            amp = amp * np.clip(bright * k_max / k, 0, 1)
        out += np.sin(2 * np.pi * k * f * (1 + detune) * t) * amp
    return out


# ---------------- drums (cached: identical hits are reused) ----------------
@lru_cache(None)
def kick():
    n = int(0.45 * SR)
    t = tt(n)
    f = 50 + 190 * np.exp(-t / 0.025) + 60 * np.exp(-t / 0.004)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR)
    body = np.tanh(body * 1.6 * np.exp(-t / 0.22)) * np.minimum(t / 0.0012, 1)
    click = band_noise(n, 2500, 10000) * np.exp(-t / 0.003) * 0.4
    return body + click


@lru_cache(None)
def snare():
    n = int(0.3 * SR)
    t = tt(n)
    tone = np.sin(2 * np.pi * np.cumsum(200 + 70 * np.exp(-t / 0.02)) / SR) * np.exp(-t / 0.06)
    rattle = band_noise(n, 1800, 12000) * np.exp(-t / 0.12)
    return (tone * 0.6 + rattle * 0.75) * 0.5


@lru_cache(None)
def clap():
    n = int(0.32 * SR)
    t = tt(n)
    burst = sum(np.exp(-np.maximum(t - o, 0) / 0.007) * (t >= o) for o in (0, 0.008, 0.017, 0.026))
    return band_noise(n, 1000, 7000) * (burst * 0.6 + np.exp(-t / 0.12) * 0.8) * 0.45


@lru_cache(None)
def snap():
    n = int(0.12 * SR)
    t = tt(n)
    return band_noise(n, 1500, 5000) * np.exp(-t / 0.018) * 0.35


@lru_cache(None)
def hat(open_=False):
    n = int((0.3 if open_ else 0.045) * SR)
    metal = sum(np.sign(np.sin(2 * np.pi * f * tt(n))) for f in (3130, 4630, 5720, 7010, 8350)) / 5
    x = band_noise(n, 7500, 18000) * 0.7 + metal * 0.3
    return x * env(n, 0.0008, 0.11 if open_ else 0.014) * 0.22


@lru_cache(None)
def tom(m):
    n = int(0.3 * SR)
    t = tt(n)
    f = hz(m) * (1 + 0.6 * np.exp(-t / 0.03))
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.15) * 0.5


@lru_cache(None)
def crash():
    n = int(2.2 * SR)
    return band_noise(n, 4000, 16000) * env(n, 0.001, 0.7) * 0.2


# ---------------- music ----------------
@lru_cache(None)
def supersaw(chord, length, bright):
    """Seven detuned saws per note, spread in stereo by the caller; `bright` 0..1 opens the filter."""
    n = int(length * SR)
    t = tt(n)
    b = np.full(n, bright)
    x = np.zeros(n)
    for m in chord:
        for d in (-0.012, -0.007, -0.003, 0.0, 0.003, 0.007, 0.012):
            x += saw(hz(m), n, harmonics=12, detune=d, bright=b)
    e = np.minimum(t / 0.01, 1) * np.minimum((length - t) / 0.08, 1)
    return x * e * 0.022


@lru_cache(None)
def pluck(m, length=0.16):
    n = int(length * SR)
    b = np.exp(-tt(n) / 0.035) * 0.85 + 0.15
    return saw(hz(m), n, harmonics=18, bright=b) * env(n, 0.001, length / 3)


@lru_cache(None)
def lead(m, length):
    """Bright square/saw lead with a quick filter blip and a touch of vibrato."""
    n = int(length * SR)
    t = tt(n)
    vib = 1 + 0.004 * np.sin(2 * np.pi * 5.5 * t) * np.minimum(t / 0.25, 1)
    ph = np.cumsum(hz(m) * vib) / SR
    sq = sum(np.sin(2 * np.pi * k * ph) / k for k in (1, 3, 5, 7, 9, 11))
    sw = sum(np.sin(2 * np.pi * k * ph) / k for k in range(1, 13))
    x = sq * 0.55 + sw * 0.45
    e = np.minimum(t / 0.006, 1) * (0.75 + 0.25 * np.exp(-t / 0.08)) * np.minimum((length - t) / 0.03, 1)
    return x * e * 0.3


@lru_cache(None)
def chop(m, length=0.22):
    """Formant 'vocal chop' ("ah"): harmonics weighted by two vowel formants, with a small scoop up."""
    n = int(length * SR)
    t = tt(n)
    f0 = hz(m) * (1 - 0.03 * np.exp(-t / 0.03))
    ph = np.cumsum(f0) / SR
    x = np.zeros(n)
    for k in range(1, 30):
        fk = k * hz(m)
        w = np.exp(-((fk - 800) / 180) ** 2) + 0.7 * np.exp(-((fk - 1250) / 220) ** 2) + 0.15 * np.exp(-((fk - 2600) / 400) ** 2)
        x += np.sin(2 * np.pi * k * ph) * w
    return x * np.minimum(t / 0.008, 1) * np.exp(-t / 0.11) * 0.5


@lru_cache(None)
def bass(m, length):
    n = int(length * SR)
    b = np.exp(-tt(n) / 0.06) * 0.55 + 0.12
    mid = saw(hz(m), n, harmonics=10, bright=b)
    sub = np.sin(2 * np.pi * hz(m) * tt(n)) * 1.1
    e = np.minimum(tt(n) / 0.003, 1) * np.minimum((length - tt(n)) / 0.01, 1)
    return (mid * 0.35 + sub) * e * 0.32


def riser(length):
    n = int(length * SR)
    t = tt(n) / length
    noise = band_noise(n, 1500, 14000) * t ** 2 * 0.3
    sweep = np.sin(2 * np.pi * np.cumsum(200 + 2600 * t ** 2) / SR) * t ** 3 * 0.12
    return noise + sweep


def impact():
    n = int(3.5 * SR)
    t = tt(n)
    boom = np.sin(2 * np.pi * np.cumsum(38 + 80 * np.exp(-t / 0.06)) / SR) * np.exp(-t / 0.9)
    return boom * 0.8 + band_noise(n, 300, 12000) * np.exp(-t / 0.5) * 0.25


# I - V - vi - IV in F major: (bass root, chord voicing, arp tones)
PROG = [(41, (65, 69, 72, 76), (77, 81, 84, 88)),
        (36, (64, 67, 72, 74), (76, 79, 84, 86)),
        (38, (62, 65, 69, 72), (74, 77, 81, 84)),
        (34, (62, 65, 70, 72), (74, 77, 82, 84))]
BASS_PAT = [(0, 0, 2, 1.0), (2, 12, 1, .8), (4, 0, 1, .75), (6, 12, 1, .85),
            (8, 0, 2, .95), (10, 12, 1, .8), (12, 0, 1, .75), (14, 12, 2, .9)]
ARP = [0, 1, 2, 3, 2, 1, 2, 3, 0, 1, 2, 3, 2, 3, 1, 2]
# hook: four bars (16th step, midi, length in 16ths), one bar per chord
HOOK = [[(0, 81, 2), (3, 79, 1), (4, 77, 2), (6, 79, 2), (8, 81, 3), (12, 84, 2), (14, 81, 2)],
        [(0, 79, 3), (4, 77, 2), (6, 76, 2), (8, 74, 2), (10, 72, 2), (12, 74, 4)],
        [(0, 81, 2), (3, 79, 1), (4, 77, 2), (6, 79, 2), (8, 81, 3), (12, 86, 2), (14, 84, 2)],
        [(0, 82, 3), (4, 81, 2), (6, 79, 2), (8, 77, 6), (14, 72, 2)]]
CHOPS = [(6, 84), (7, 84), (10, 81), (14, 79)]

sidechain = np.ones(N)


def duck(t0, depth=0.85, rel=0.16):
    i = int(t0 * SR)
    n = min(int(BEAT * SR), N - i)
    if n <= 0:
        return
    d = 1 - depth * np.exp(-tt(n) / rel)
    sidechain[i:i + n] = np.minimum(sidechain[i:i + n], d)


def in_(t, r):
    return r[0] <= t < r[1]


def roll(t0, beats, v0=0.25):
    """Snare roll accelerating from 8ths to 16ths, used for builds."""
    steps = int(beats * 4)
    for s in range(steps):
        if s < steps // 2 and s % 2:
            continue
        add('drums', snare(), t0 + s * S16, (v0 + 0.75 * s / steps) * 0.9, (s % 2 - .5) * .2)


# ---------------- intro ----------------
root, chord, _ = PROG[0]
add('music', supersaw(chord, DROP + 0.2, 0.2), 0, 2.2)
add('fx', riser(DROP), 0, 1.1)
for b in range(2, 6):
    add('drums', snap(), b * BEAT, 0.8, 0.2)
roll(DROP - 2 * BEAT, 2)

# ---------------- groove ----------------
t = DROP
bar = 0
while t < END - 1e-6:
    root, chord, arp = PROG[bar % 4]
    priv, brk = in_(t, PRIVATE), in_(t, BREAK)
    lift = t >= LIFT
    hook_on = t >= DROP + 4 * BAR and not priv
    last_of_phrase = bar % 4 == 3
    next_t = t + BAR
    rebuild = (priv and next_t >= PRIVATE[1]) or (brk and next_t >= BREAK[1])

    if not (priv or brk) and (bar % 4 == 0 or abs(t - PRIVATE[1]) < 1e-6 or abs(t - BREAK[1]) < 1e-6 or abs(t - LIFT) < 1e-6):
        add('drums', crash(), t, 1, 0.2)

    # --- chords: pumping supersaw; filtered in breakdowns
    soft = priv or brk
    add('music', supersaw(chord, BAR + 0.05, 0.22 if soft else 0.55), t, 1.0 if soft else 0.8)

    # --- drums
    if soft:
        add('drums', kick(), t, 0.7)
        for b in (1, 3):
            add('drums', snap(), t + b * BEAT, 1.0, 0.25)
            add('drums', clap(), t + b * BEAT, 0.35, -0.1)
        for s in range(2, 16, 4):
            add('drums', hat(), t + s * S16, 0.7, -0.3)
        duck(t, 0.6)
        if rebuild:
            roll(t + 2 * BEAT, 2, 0.2)
            add('fx', riser(BAR), t, 0.9)
    else:
        for b in range(4):
            add('drums', kick(), t + b * BEAT)
            duck(t + b * BEAT)
        for b in (1, 3):
            add('drums', clap(), t + b * BEAT, 1.0, 0.05)
            add('drums', snare(), t + b * BEAT, 0.5, -0.05)
            if lift:
                add('drums', clap(), t + b * BEAT + 0.012, 0.45, -0.35)
        for s in range(16):
            if s % 4 == 2:
                add('drums', hat(open_=lift or s == 14), t + s * S16, 0.9 if lift else 0.75, -0.25)
            else:
                add('drums', hat(), t + s * S16, (0.55 + 0.3 * (s % 2 == 0)) * (0.9 + 0.2 * rng.random()), 0.25)
        if last_of_phrase and next_t < END - 1e-6 and not in_(next_t, PRIVATE) and not in_(next_t, BREAK):
            for s, m in enumerate([55, 55, 52, 52, 50, 48, 45, 43]):
                add('drums', tom(m), t + 2 * BEAT + s * S16, 0.75, 0.5 - s * 0.14)

    # --- bass
    for step, off, ln, v in BASS_PAT:
        if soft and step % 8:
            continue
        if last_of_phrase and step >= 8 and not soft:
            continue   # room for the fill
        add('bass', bass(root + off, (ln * S16 * 0.9) if not soft else BEAT * 1.8), t + step * S16, v)

    # --- arp, hook, chops
    if not soft or brk:
        for s, idx in enumerate(ARP):
            add('music', pluck(arp[idx]), t + s * S16, 0.12 if not brk else 0.16, 0.5 if s % 2 else -0.5)
    if hook_on or brk:
        for step, m, ln in HOOK[bar % 4]:
            add('lead', lead(m, ln * S16 * 0.92), t + step * S16, 0.55 if brk else 0.8)
    if lift and not brk and bar % 2 == 1:
        for step, m in CHOPS:
            add('lead', chop(m), t + step * S16, 0.55, 0.35 if step % 2 else -0.35)

    t = next_t
    bar += 1

add('fx', impact(), END, 1.0)
add('drums', crash(), END, 1.3)
add('drums', kick(), END, 1.0)
add('music', supersaw((53, 65, 69, 72, 76, 79), DUR - END, 0.4), END, 0.9)
for k, m in enumerate([77, 81, 84, 88, 89]):
    add('lead', lead(m, 0.5), END + 0.25 + k * S16 * 2, 0.45, (k - 2) * 0.2)


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


music = BUS['music'] * sidechain[:, None]
lead_bus = delay(BUS['lead'], 3 * S16, fb=0.3, mix=0.3) * (0.55 + 0.45 * sidechain[:, None])
bass_bus = BUS['bass'] * (0.05 + 0.95 * sidechain[:, None] ** 2)
mix = BUS['drums'] * 1.0 + bass_bus * 0.42 + music * 0.75 + lead_bus * 0.55 + BUS['fx'] * 0.6
mix *= 10 ** (-14.4 / 20) / np.sqrt(np.mean(mix ** 2))   # about -14 LUFS after limiting
pre = mix.copy()
mix = limiter(mix)
print(f"pre-limiter peak {20 * np.log10(np.abs(pre).max()):.1f} dBFS, gain reduction on {np.mean(np.abs(mix).max(1) < np.abs(pre).max(1) * 0.99) * 100:.2f}% of samples")
mix *= np.minimum(1, (DUR - tt(N)) / 2.5)[:, None]

out = sys.argv[1] if len(sys.argv) > 1 else 'soundtrack.wav'
with wave.open(out, 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((mix * 32767).astype('<i2').tobytes())
print('wrote', out)
