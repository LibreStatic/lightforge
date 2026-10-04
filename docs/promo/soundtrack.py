"""Synthesizes the promo soundtrack (calm ambient, 78 BPM, C major) into soundtrack.wav.

Everything is generated from oscillators and noise: no third-party samples, no licensing questions.
A slow pad carries Cmaj7 - Gadd9 - Am7 - Fmaj7, a sparse music-box line floats on top, a soft sine bass
and a barely-there pulse keep time, and everything shares one long reverb. Length follows promo.html.
usage: python soundtrack.py [out.wav]   (needs numpy)
"""
import sys
import wave

import numpy as np

SR = 48000
BPM = 78
BEAT = 60 / BPM
BAR = 4 * BEAT
DUR = 84.8
N = int(SR * DUR)
rng = np.random.default_rng(7)

BUS = {k: np.zeros((N, 2)) for k in ('pad', 'keys', 'bass', 'pulse')}
INTRO = 3.0        # phone lands: the groove gently arrives
SECOND = 44.3      # new run of feature scenes: a little more movement
END = 52.6 + 27.8  # logo outro: final chord


def tt(n):
    return np.arange(n) / SR


def hz(m):
    return 440 * 2 ** ((m - 69) / 12)


def add(bus, sig, t0, gain=1.0, pan=0.0):
    i = int(round(t0 * SR))
    if i >= N or i < 0:
        return
    sig = sig[: N - i] * gain
    BUS[bus][i:i + len(sig), 0] += sig * min(1, 1 - pan)
    BUS[bus][i:i + len(sig), 1] += sig * min(1, 1 + pan)


def band_noise(n, lo, hi):
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    spec[(f < lo) | (f > hi)] = 0
    x = np.fft.irfft(spec, n)
    return x / (np.abs(x).max() + 1e-9)


def pad_note(m, length, v=1.0):
    n = int(length * SR)
    t = tt(n)
    x = np.zeros(n)
    for d in (-0.004, 0.0, 0.004):
        f = hz(m) * (1 + d)
        x += np.sin(2 * np.pi * f * t) + 0.25 * np.sin(2 * np.pi * 2 * f * t) + 0.08 * np.sin(2 * np.pi * 3 * f * t)
    a = min(1.6, length / 3)
    e = np.minimum(t / a, 1) * np.minimum((length - t) / a, 1)
    return x * e * 0.05 * v


def bell(m, v=1.0):
    """Music-box pluck: sine with a quiet inharmonic partial and a short, round decay."""
    n = int(2.4 * SR)
    t = tt(n)
    f = hz(m)
    x = np.sin(2 * np.pi * f * t) * np.exp(-t / 0.9) + 0.18 * np.sin(2 * np.pi * f * 2.76 * t) * np.exp(-t / 0.25) \
        + 0.08 * np.sin(2 * np.pi * f * 5.4 * t) * np.exp(-t / 0.08)
    return x * np.minimum(t / 0.004, 1) * 0.5 * v


def sub(m, length, v=1.0):
    n = int(length * SR)
    t = tt(n)
    e = np.minimum(t / 0.05, 1) * np.exp(-t / (length * 0.7)) * np.minimum((length - t) / 0.1, 1)
    return np.sin(2 * np.pi * hz(m) * t) * e * 0.3 * v


def thump(v=1.0):
    n = int(0.35 * SR)
    t = tt(n)
    f = 52 + 40 * np.exp(-t / 0.04)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.11) * 0.35 * v


def brush(v=1.0):
    n = int(0.16 * SR)
    t = tt(n)
    return band_noise(n, 4000, 11000) * np.minimum(t / 0.03, 1) * np.exp(-t / 0.05) * 0.07 * v


# Cmaj7 - Gadd9 - Am7 - Fmaj7 (root, pad voicing, bell scale tones)
PROG = [(36, [60, 64, 67, 71], [72, 76, 79, 83, 84]),
        (43, [59, 62, 67, 69], [74, 76, 79, 81, 86]),
        (45, [60, 64, 67, 72], [72, 76, 79, 81, 84]),
        (41, [60, 65, 69, 72], [72, 74, 77, 81, 84])]

n_bars = int(np.ceil(DUR / BAR)) + 1
for bar in range(n_bars):
    t0 = bar * BAR
    root, chord, scale = PROG[bar % 4]
    for m in chord:                                    # overlapping pads: no gaps between chords
        add('pad', pad_note(m, BAR + 1.6), t0 - 0.4, 1.0, ((m % 5) - 2) * 0.12)
    add('bass', sub(root, BAR * 0.95), t0 + 0.02, 0.85 if t0 >= INTRO else 0.5)
    if t0 >= INTRO - BAR:                              # music box: sparse eighth notes
        dens = 0.7 if t0 >= SECOND else 0.5
        idx = 0
        for s in range(8):
            if rng.random() < dens or s == 0:
                m = scale[[0, 2, 1, 3, 2, 4, 3, 1][idx % 8]]
                add('keys', bell(m, 0.55 + 0.35 * rng.random()), t0 + s * BEAT / 2, 0.5, (rng.random() - .5) * .8)
                idx += 1
    if t0 >= INTRO:                                    # barely-there pulse
        add('pulse', thump(0.8), t0)
        if t0 >= 12:
            add('pulse', thump(0.45), t0 + 2 * BEAT)
        if t0 >= SECOND:
            for s in range(8):
                add('pulse', brush(0.6 + 0.3 * (s % 2 == 0)), t0 + s * BEAT / 2 + BEAT / 4, 1, 0.3)

# arrival and finale
add('keys', bell(84, 1.0), 0.3, 0.5)
add('keys', bell(79, 0.9), 0.9, 0.5)
add('keys', bell(76, 0.8), 1.5, 0.5)
for k, m in enumerate([60, 64, 67, 71, 74, 79]):
    add('pad', pad_note(m, DUR - END + 1.0, 1.4), END - 0.3, 1.0, (k - 2.5) * 0.1)
for k, m in enumerate([84, 88, 91, 95]):
    add('keys', bell(m, 0.8), END + 0.2 + k * 0.45, 0.5, (k - 1.5) * 0.3)
add('bass', sub(36, 7.0), END, 1.0)


# ---------------- mix ----------------
def reverb(x, seconds=3.2, mix=0.35):
    n = int(seconds * SR)
    t = tt(n)
    ir = np.stack([band_noise(n, 250, 7000) * np.exp(-t / (seconds / 4.5)) for _ in range(2)], axis=1)
    ir[: int(0.02 * SR)] *= np.linspace(0, 1, int(0.02 * SR))[:, None]
    size = 1 << (len(x) + n).bit_length()
    out = np.zeros_like(x)
    for c in range(2):
        out[:, c] = np.fft.irfft(np.fft.rfft(x[:, c], size) * np.fft.rfft(ir[:, c], size), size)[: len(x)]
    return x * (1 - mix * 0.4) + out * mix * 0.12


def limiter(x, ceiling=10 ** (-1.5 / 20)):
    peak = np.abs(x).max()
    return x * min(1, ceiling / peak)


wet = reverb(BUS['pad'] * 1.0 + BUS['keys'] * 1.0, mix=0.55)
mix = wet + BUS['bass'] * 0.9 + BUS['pulse'] * 0.6
mix *= 10 ** (-17 / 20) / np.sqrt(np.mean(mix ** 2))    # quiet and even, about -17 dB RMS
mix = limiter(mix)
fade_in = np.minimum(1, tt(N) / 1.2)[:, None]
fade_out = np.minimum(1, (DUR - tt(N)) / 3.0)[:, None]
mix *= fade_in * fade_out

out = sys.argv[1] if len(sys.argv) > 1 else 'soundtrack.wav'
with wave.open(out, 'wb') as w:
    w.setnchannels(2)
    w.setsampwidth(2)
    w.setframerate(SR)
    w.writeframes((mix * 32767).astype('<i2').tobytes())
print('wrote', out, f'peak {20 * np.log10(np.abs(mix).max()):.1f} dBFS')
