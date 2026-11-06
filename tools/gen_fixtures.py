#!/usr/bin/env python3
"""Generates the audio/photo fixtures the on-device tests use into tools/.fixtures (ignored by git)."""
import math, os, random, struct, wave
from PIL import Image, ImageDraw
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".fixtures"); os.makedirs(out, exist_ok=True)

def write_wav(name, seconds, rate, mono, sample):
    w = wave.open(os.path.join(out, name), "wb"); w.setnchannels(1 if mono else 2); w.setsampwidth(2); w.setframerate(rate)
    frames = bytearray()
    for i in range(rate * seconds):
        v = sample(i, rate)
        frames += struct.pack("<h", v) if mono else struct.pack("<hh", v, v)
    w.writeframes(bytes(frames)); w.close()

write_wav("tone.wav", 6, 44100, False, lambda i, r: int(8000 * math.sin(2 * math.pi * 440 * i / r)))

# 124 BPM click track with a 180 ms offset, for the beat-sync tests
rnd = random.Random(5); bpm, secs, rate = 124.0, 24, 44100
buf = [int((rnd.random() - .5) * 1200) for _ in range(rate * secs)]
t = 0.18
while t < secs:
    s = int(t * rate)
    for k in range(1800):
        if s + k < len(buf): buf[s + k] = max(-32768, min(32767, buf[s + k] + int(math.sin(2 * math.pi * 900 * k / rate) * 22000 * math.exp(-k / 260.0))))
    t += 60.0 / bpm
w = wave.open(os.path.join(out, "beats.wav"), "wb"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
w.writeframes(b"".join(struct.pack("<h", v) for v in buf)); w.close()

for n, c in enumerate([(220, 60, 60), (60, 160, 220), (80, 200, 100)]):
    im = Image.new("RGB", (1200, 800), c); ImageDraw.Draw(im).ellipse((400, 200, 800, 600), fill=(255, 255, 255)); im.save(os.path.join(out, f"p{n}.jpg"))
print("fixtures in", out)
