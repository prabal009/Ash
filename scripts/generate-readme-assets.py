#!/usr/bin/env python3
"""
Reproducible visual & motion-graphics generator for Project Ash's GitHub README.

Generates:
  - assets/readme/ash-hero.gif          (1000x320, 90 frames, 30.0 FPS exact avg, seamless 3.0s loop)
  - assets/readme/ash-hero-static.png   (1000x320 crisp static fallback)
  - assets/readme/ash-voice-flow.gif    (1000x310, 150 frames, 30.0 FPS exact avg, seamless 5.0s loop)
  - assets/readme/ash-voice-flow-static.png (1000x310 crisp static fallback)
  - assets/readme/ash-architecture.svg  (pure static SVG, GitHub-safe, no scripts/CSS animation)
  - assets/readme/ash-architecture.png  (1200x580 crisp raster fallback)

Requirements: Python 3, pycairo, pillow, numpy (all standard Linux packages).
"""

from __future__ import annotations

import math
import os
from pathlib import Path

import cairo
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "assets" / "readme"

# System fonts verified on host
FONT_SANS_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_SANS = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_MONO_BOLD = "/usr/share/fonts/truetype/jetbrains-mono/JetBrainsMono-Bold.ttf"
FONT_MONO_SEMI = "/usr/share/fonts/truetype/jetbrains-mono/JetBrainsMono-SemiBold.ttf"
FONT_MONO = "/usr/share/fonts/truetype/jetbrains-mono/JetBrainsMono-Regular.ttf"

# Brand palette (matches app/src/main/res/values/colors.xml)
BG_DARK = (8 / 255, 11 / 255, 16 / 255)
BG_DEEP = (13 / 255, 19 / 255, 29 / 255)
SURFACE = (16 / 255, 23 / 255, 35 / 255)
STROKE = (36 / 255, 48 / 255, 67 / 255)
CYAN = (56 / 255, 189 / 255, 248 / 255)
SKY = (224 / 255, 242 / 255, 254 / 255)
INDIGO = (129 / 255, 140 / 255, 248 / 255)
EMERALD = (52 / 255, 211 / 255, 153 / 255)
AMBER = (245 / 255, 158 / 255, 11 / 255)
TEXT_PRI = (241, 245, 249, 255)
TEXT_SEC = (148, 163, 184, 255)
TEXT_MUTED = (100, 116, 139, 255)
TEXT_CYAN = (125, 211, 252, 255)


def load_font(path: str, size: int) -> ImageFont.FreeTypeFont:
    if os.path.exists(path):
        return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def ease_in_out(x: float) -> float:
    x = max(0.0, min(1.0, x))
    return x * x * (3.0 - 2.0 * x)


def rounded_rect_path(ctx: cairo.Context, x: float, y: float, w: float, h: float, r: float) -> None:
    ctx.new_sub_path()
    ctx.arc(x + w - r, y + r, r, -math.pi / 2, 0)
    ctx.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
    ctx.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
    ctx.arc(x + r, y + r, r, math.pi, 3 * math.pi / 2)
    ctx.close_path()


def surface_to_pil(surface: cairo.ImageSurface) -> Image.Image:
    w, h = surface.get_width(), surface.get_height()
    buf = np.frombuffer(surface.get_data(), dtype=np.uint8).reshape((h, w, 4))
    # Cairo ARGB32 on little-endian is BGRA premultiplied
    rgba = buf[:, :, [2, 1, 0, 3]].copy()
    return Image.fromarray(rgba, mode="RGBA")


def save_gif_30fps(frames: list[Image.Image], out_path: Path, max_colors: int = 64) -> None:
    """
    Encode frames into an optimized 30.0 FPS average GIF89a.
    GIF frame delays use 10ms units, so the repeating [30, 30, 40] ms cadence
    yields an exact 33.333ms average period = 30.00 FPS.
    Uses a shared global palette + ImageMagick frame-delta transparency optimization
    so static areas cost near-zero bytes while preserving crisp 30 FPS motion.
    """
    import subprocess

    sample_indices = np.linspace(0, len(frames) - 1, min(8, len(frames)), dtype=int)
    w, h = frames[0].size
    mosaic = Image.new("RGB", (w, h * len(sample_indices)))
    for row, idx in enumerate(sample_indices):
        mosaic.paste(frames[idx].convert("RGB"), (0, row * h))
    palette_img = mosaic.quantize(colors=max_colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)

    quantized = [
        f.convert("RGB").quantize(palette=palette_img, dither=Image.Dither.NONE)
        for f in frames
    ]
    durations = [30 if (i % 3) != 2 else 40 for i in range(len(quantized))]
    tmp_path = out_path.with_suffix(".raw.gif")
    quantized[0].save(
        tmp_path,
        save_all=True,
        append_images=quantized[1:],
        duration=durations,
        loop=0,
        optimize=True,
        disposal=1,
    )
    # Use ImageMagick frame-difference transparency optimization if available
    res = subprocess.run(
        ["convert", str(tmp_path), "-layers", "OptimizeTransparency", "+map", str(out_path)],
        capture_output=True,
    )
    if res.returncode == 0 and out_path.exists() and out_path.stat().st_size < tmp_path.stat().st_size:
        tmp_path.unlink(missing_ok=True)
    else:
        tmp_path.replace(out_path)


def draw_card_canvas(ctx: cairo.Context, w: int, h: int, pad: float = 8.0, radius: float = 18.0) -> None:
    # Solid dark outer backdrop matching card base so GIF has zero transparent fringe on dark or light GitHub themes.
    ctx.set_source_rgb(*BG_DARK)
    ctx.rectangle(0, 0, w, h)
    ctx.fill()

    # Main obsidian panel with subtle diagonal gradient
    rounded_rect_path(ctx, pad, pad, w - 2 * pad, h - 2 * pad, radius)
    grad = cairo.LinearGradient(0, 0, w, h)
    grad.add_color_stop_rgb(0.0, 9 / 255, 13 / 255, 20 / 255)
    grad.add_color_stop_rgb(0.55, 13 / 255, 19 / 255, 30 / 255)
    grad.add_color_stop_rgb(1.0, 10 / 255, 15 / 255, 24 / 255)
    ctx.set_source(grad)
    ctx.fill_preserve()

    ctx.save()
    ctx.clip()

    # Subtle architectural micro-grid in upper-right quadrant
    ctx.set_source_rgba(56 / 255, 189 / 255, 248 / 255, 0.032)
    ctx.set_line_width(1.0)
    step = 32
    for gx in range(int(w * 0.45), w, step):
        ctx.move_to(gx + 0.5, pad)
        ctx.line_to(gx + 0.5, h - pad)
    for gy in range(int(pad), h, step):
        ctx.move_to(w * 0.45, gy + 0.5)
        ctx.line_to(w - pad, gy + 0.5)
    ctx.stroke()
    ctx.restore()

    # Crisp 1px hairline border
    rounded_rect_path(ctx, pad + 0.5, pad + 0.5, w - 2 * pad - 1.0, h - 2 * pad - 1.0, radius)
    ctx.set_source_rgba(*STROKE, 0.95)
    ctx.set_line_width(1.2)
    ctx.stroke()


def render_hero_frame(frame_idx: int, total_frames: int, scale: int = 2) -> Image.Image:
    W, H = 1000, 320
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, W * scale, H * scale)
    ctx = cairo.Context(surf)
    ctx.scale(scale, scale)

    t = frame_idx / total_frames  # [0, 1) periodic
    theta = 2.0 * math.pi * t

    draw_card_canvas(ctx, W, H, pad=6.0, radius=18.0)

    # Clip interior subtle ambient glows
    ctx.save()
    rounded_rect_path(ctx, 6.0, 6.0, W - 12.0, H - 12.0, 18.0)
    ctx.clip()

    # Ambient breathing light behind acoustic emblem
    orb_x, orb_y = 178.0, 160.0
    breathe = 0.5 + 0.5 * math.sin(theta)
    glow = cairo.RadialGradient(orb_x, orb_y, 12.0, orb_x, orb_y, 175.0)
    glow.add_color_stop_rgba(0.0, *CYAN, 0.20 + 0.06 * breathe)
    glow.add_color_stop_rgba(0.45, *INDIGO, 0.08 + 0.03 * breathe)
    glow.add_color_stop_rgba(1.0, *BG_DARK, 0.0)
    ctx.set_source(glow)
    ctx.arc(orb_x, orb_y, 175.0, 0, 2 * math.pi)
    ctx.fill()

    # Soft secondary glow under right waveform
    wglow = cairo.RadialGradient(640.0 + 40.0 * math.sin(theta), 248.0, 10.0, 640.0, 248.0, 260.0)
    wglow.add_color_stop_rgba(0.0, *CYAN, 0.09)
    wglow.add_color_stop_rgba(1.0, *BG_DARK, 0.0)
    ctx.set_source(wglow)
    ctx.rectangle(310, 180, 660, 130)
    ctx.fill()
    ctx.restore()

    # ---- Left Acoustic Emblem ----
    # Outer architectural guide rings
    for r_guide, alpha in [(92.0, 0.14), (66.0, 0.24)]:
        ctx.set_source_rgba(*CYAN, alpha)
        ctx.set_line_width(1.1)
        ctx.arc(orb_x, orb_y, r_guide, 0, 2 * math.pi)
        ctx.stroke()

    # Smooth expanding sonar rings (2 continuous periodic pulses)
    for k in range(2):
        p = (t + k * 0.5) % 1.0
        radius = 36.0 + 74.0 * p
        alpha = (1.0 - p) ** 1.6 * 0.52
        c = CYAN if k == 0 else INDIGO
        ctx.set_source_rgba(*c, alpha)
        ctx.set_line_width(1.7 * (1.0 - 0.35 * p))
        ctx.arc(orb_x, orb_y, radius, 0, 2 * math.pi)
        ctx.stroke()

    # Precision orbital arc (rotates 360 deg per 3s loop)
    arc_r = 66.0
    ctx.set_source_rgba(*SKY, 0.72)
    ctx.set_line_width(2.0)
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    ctx.arc(orb_x, orb_y, arc_r, theta - 0.55, theta + 0.45)
    ctx.stroke()
    ctx.set_source_rgba(*INDIGO, 0.45)
    ctx.set_line_width(1.6)
    ctx.arc(orb_x, orb_y, arc_r, theta + math.pi - 0.35, theta + math.pi + 0.35)
    ctx.stroke()

    # Core luminous sphere
    core_r = 34.0 + 1.8 * math.sin(theta)
    core_grad = cairo.RadialGradient(orb_x - 8, orb_y - 10, 3.0, orb_x, orb_y, core_r * 1.15)
    core_grad.add_color_stop_rgba(0.0, *SKY, 1.0)
    core_grad.add_color_stop_rgba(0.55, *CYAN, 0.96)
    core_grad.add_color_stop_rgba(1.0, *INDIGO, 0.85)
    ctx.set_source(core_grad)
    ctx.arc(orb_x, orb_y, core_r, 0, 2 * math.pi)
    ctx.fill()

    # Minimal inner acoustic bars inside core (5 subtle vertical bars pulsing softly)
    bar_offsets = [-12.0, -6.0, 0.0, 6.0, 12.0]
    bar_weights = [0.42, 0.76, 1.0, 0.76, 0.42]
    ctx.set_source_rgba(7 / 255, 19 / 255, 28 / 255, 0.82)
    for idx, (dx, bw) in enumerate(zip(bar_offsets, bar_weights)):
        amp = 6.0 + 13.0 * bw * (0.58 + 0.42 * math.sin(theta * 2.0 + idx * 0.95))
        rounded_rect_path(ctx, orb_x + dx - 1.6, orb_y - amp / 2.0, 3.2, amp, 1.6)
        ctx.fill()

    # Subtle vertical hairline divider between emblem and editorial content
    div_grad = cairo.LinearGradient(312.0, 48.0, 312.0, 272.0)
    div_grad.add_color_stop_rgba(0.0, *STROKE, 0.0)
    div_grad.add_color_stop_rgba(0.25, *STROKE, 0.85)
    div_grad.add_color_stop_rgba(0.75, *STROKE, 0.85)
    div_grad.add_color_stop_rgba(1.0, *STROKE, 0.0)
    ctx.set_source(div_grad)
    ctx.set_line_width(1.0)
    ctx.move_to(312.5, 48.0)
    ctx.line_to(312.5, 272.0)
    ctx.stroke()

    # ---- Smooth Flowing Voice Waveform (Bottom Right) ----
    wave_x0, wave_x1 = 352.0, 946.0
    wave_w = wave_x1 - wave_x0
    wave_cy = 248.0
    samples = 160

    # Baseline hairline
    ctx.set_source_rgba(*STROKE, 0.65)
    ctx.set_line_width(1.0)
    ctx.move_to(wave_x0, wave_cy)
    ctx.line_to(wave_x1, wave_cy)
    ctx.stroke()

    waves = [
        (INDIGO, 0.42, 1.5, 2.0, 1.0, 18.0, 0.6),
        (CYAN, 0.85, 2.1, 3.0, 2.0, 23.0, 0.0),
        (SKY, 0.52, 1.2, 4.0, 1.0, 12.0, 1.4),
    ]
    for color, alpha, lw, freq, speed, amp, phase_off in waves:
        ctx.set_source_rgba(*color, alpha)
        ctx.set_line_width(lw)
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        for s in range(samples + 1):
            u = s / samples
            x = wave_x0 + u * wave_w
            env = math.sin(math.pi * u) ** 1.7
            y_off = env * amp * (
                0.65 * math.sin(2.0 * math.pi * (freq * u - speed * t) + phase_off)
                + 0.35 * math.sin(2.0 * math.pi * ((freq + 2.0) * u + speed * t) - phase_off * 0.5)
            )
            if s == 0:
                ctx.move_to(x, wave_cy + y_off)
            else:
                ctx.line_to(x, wave_cy + y_off)
        ctx.stroke()

    # Live status dot in top-right corner
    dot_x, dot_y = 928.0, 56.0
    ctx.set_source_rgba(*EMERALD, 0.25 + 0.20 * breathe)
    ctx.arc(dot_x, dot_y, 7.5 + 1.5 * breathe, 0, 2 * math.pi)
    ctx.fill()
    ctx.set_source_rgba(*EMERALD, 0.95)
    ctx.arc(dot_x, dot_y, 3.6, 0, 2 * math.pi)
    ctx.fill()

    # Composite crisp PIL typography at 2x scale, then downsample with Lanczos
    img = surface_to_pil(surf)
    draw = ImageDraw.Draw(img)

    f_eyebrow = load_font(FONT_MONO_SEMI, 12 * scale)
    f_title = load_font(FONT_SANS_BOLD, 54 * scale)
    f_tag = load_font(FONT_SANS, 20 * scale)
    f_sub = load_font(FONT_MONO, 12 * scale)
    f_badge = load_font(FONT_MONO_SEMI, 11 * scale)

    draw.text((352 * scale, 48 * scale), "ANDROID 8–15 · NATIVE KOTLIN · ON-DEVICE SPEECH", font=f_eyebrow, fill=TEXT_CYAN)
    draw.text((796 * scale, 49 * scale), "KWS + ASR LOCAL", font=f_badge, fill=TEXT_MUTED)

    # Tracked ASH wordmark
    x_cursor = 350 * scale
    for ch in "ASH":
        draw.text((x_cursor, 76 * scale), ch, font=f_title, fill=TEXT_PRI)
        bbox = draw.textbbox((0, 0), ch, font=f_title)
        x_cursor += (bbox[2] - bbox[0]) + 10 * scale

    draw.text((352 * scale, 148 * scale), "Native Android AI Voice Assistant", font=f_tag, fill=(226, 232, 240, 255))
    draw.text(
        (352 * scale, 180 * scale),
        'Offline "Hey Ash" & "Hello Ash" wake · Bluetooth HFP/BLE · Accessibility control',
        font=f_sub,
        fill=TEXT_SEC,
    )

    return img.resize((W, H), Image.Resampling.LANCZOS)


VOICE_STAGES = [
    ("Standby", "Local KWS", "VoiceInput · sherpa-onnx Zipformer monitoring phone mic offline (no cloud audio)", CYAN),
    ("Wake Hit", "Hey Ash", 'WakeModel · "@HEY_ASH" / "@HELLO_ASH" matched · 1.5s pre-roll handoff + Kokoro warm-up', SKY),
    ("Listening", "ASR + Buds", "SpeechModel · Kroko streaming ASR · BluetoothAudioRouteController (HFP/BLE or phone mic)", CYAN),
    ("Routing", "Router / AI", "FastCommandRouter (instant offline) or PhoneAgent / AiRouter (Claude, Groq, Gemini, local)", INDIGO),
    ("Executing", "Tools / A11y", "FastActionExecutor (TelecomManager / Media) or JarvisAccessibilityService + OneUseApproval", AMBER),
    ("Speaking", "Kokoro TTS", "Speaker · Kokoro-82M paced audio output (or CloudVoice) with echo-cancelled barge-in", EMERALD),
]


def render_voice_flow_frame(frame_idx: int, total_frames: int, scale: int = 2) -> Image.Image:
    W, H = 1000, 310
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, W * scale, H * scale)
    ctx = cairo.Context(surf)
    ctx.scale(scale, scale)

    t = frame_idx / total_frames  # [0, 1) over 5.0 seconds
    n_stages = len(VOICE_STAGES)
    # 6 stages + smooth return pulse in last 1/7th segment -> total 7 equal slots
    slot_pos = t * 7.0
    slot_idx = int(slot_pos)
    slot_frac = slot_pos - slot_idx
    returning = slot_idx == 6
    active_stage = 0 if returning else slot_idx

    draw_card_canvas(ctx, W, H, pad=6.0, radius=18.0)

    # Header divider hairline
    ctx.set_source_rgba(*STROKE, 0.75)
    ctx.set_line_width(1.0)
    ctx.move_to(28.0, 56.0)
    ctx.line_to(W - 28.0, 56.0)
    ctx.stroke()

    # "ILLUSTRATIVE FLOW" pill in top-right
    rounded_rect_path(ctx, W - 178.0, 21.0, 148.0, 24.0, 12.0)
    ctx.set_source_rgba(25 / 255, 34 / 255, 48 / 255, 0.95)
    ctx.fill_preserve()
    ctx.set_source_rgba(*STROKE, 1.0)
    ctx.set_line_width(1.0)
    ctx.stroke()

    # Pipeline geometry
    node_y = 128.0
    node_xs = [96.0 + i * 161.6 for i in range(n_stages)]
    node_w, node_h = 126.0, 68.0

    # Base connecting rail behind nodes
    ctx.set_source_rgba(*STROKE, 0.95)
    ctx.set_line_width(2.0)
    ctx.move_to(node_xs[0], node_y)
    ctx.line_to(node_xs[-1], node_y)
    ctx.stroke()

    # Return loop rail underneath (Speaking -> Standby)
    loop_y = 182.0
    ctx.set_source_rgba(*STROKE, 0.65)
    ctx.set_line_width(1.4)
    ctx.set_dash([5.0, 5.0])
    ctx.move_to(node_xs[-1], node_y + node_h / 2)
    ctx.line_to(node_xs[-1], loop_y)
    ctx.line_to(node_xs[0], loop_y)
    ctx.line_to(node_xs[0], node_y + node_h / 2)
    ctx.stroke()
    ctx.set_dash([])

    # Active progress highlight along rail
    if not returning:
        # Travelling highlight from node_xs[0] to current animated frontier
        if active_stage < n_stages - 1:
            e = ease_in_out(max(0.0, (slot_frac - 0.55) / 0.45))
            frontier_x = node_xs[active_stage] + (node_xs[active_stage + 1] - node_xs[active_stage]) * e
        else:
            frontier_x = node_xs[-1]
        rgrad = cairo.LinearGradient(node_xs[0], node_y, max(node_xs[0] + 1.0, frontier_x), node_y)
        rgrad.add_color_stop_rgba(0.0, *CYAN, 0.45)
        rgrad.add_color_stop_rgba(1.0, *CYAN, 0.95)
        ctx.set_source(rgrad)
        ctx.set_line_width(2.6)
        ctx.move_to(node_xs[0], node_y)
        ctx.line_to(frontier_x, node_y)
        ctx.stroke()

        # Crisp travelling signal dot between current and next node
        if active_stage < n_stages - 1 and slot_frac > 0.55:
            ctx.set_source_rgba(*SKY, 0.95)
            ctx.arc(frontier_x, node_y, 4.0, 0, 2 * math.pi)
            ctx.fill()
    else:
        # Smooth return pulse travelling along bottom rail back to Standby
        e = ease_in_out(slot_frac)
        px = node_xs[-1] - (node_xs[-1] - node_xs[0]) * e
        ctx.set_source_rgba(*EMERALD, 0.55)
        ctx.set_line_width(2.0)
        ctx.move_to(node_xs[-1], loop_y)
        ctx.line_to(px, loop_y)
        ctx.stroke()
        ctx.set_source_rgba(*EMERALD, 0.95)
        ctx.arc(px, loop_y, 4.2, 0, 2 * math.pi)
        ctx.fill()

    # Draw 6 stage cards
    for i, (title, sub, _desc, accent) in enumerate(VOICE_STAGES):
        nx = node_xs[i] - node_w / 2.0
        ny = node_y - node_h / 2.0
        is_active = (i == active_stage) and (not returning or slot_frac > 0.72)
        is_done = (not returning) and (i < active_stage)

        if is_active:
            pulse = 0.5 + 0.5 * math.sin(slot_frac * math.pi)
            glow = cairo.RadialGradient(node_xs[i], node_y, 10.0, node_xs[i], node_y, 82.0)
            glow.add_color_stop_rgba(0.0, *accent, 0.24 + 0.08 * pulse)
            glow.add_color_stop_rgba(1.0, *BG_DARK, 0.0)
            ctx.set_source(glow)
            ctx.arc(node_xs[i], node_y, 82.0, 0, 2 * math.pi)
            ctx.fill()

        rounded_rect_path(ctx, nx, ny, node_w, node_h, 12.0)
        if is_active:
            ctx.set_source_rgba(22 / 255, 32 / 255, 48 / 255, 1.0)
        else:
            ctx.set_source_rgba(*SURFACE, 0.98)
        ctx.fill_preserve()

        if is_active:
            ctx.set_source_rgba(*accent, 0.95)
            ctx.set_line_width(1.8)
        elif is_done:
            ctx.set_source_rgba(*CYAN, 0.45)
            ctx.set_line_width(1.2)
        else:
            ctx.set_source_rgba(*STROKE, 0.95)
            ctx.set_line_width(1.1)
        ctx.stroke()

        # Small top-left indicator dot inside each card
        dot_c = accent if is_active else (CYAN if is_done else STROKE)
        ctx.set_source_rgba(*dot_c, 0.95 if (is_active or is_done) else 0.8)
        ctx.arc(nx + 15.0, ny + 18.0, 3.5, 0, 2 * math.pi)
        ctx.fill()

    # Bottom Live State Readout Bar
    bar_x, bar_y, bar_w, bar_h = 28.0, 206.0, W - 56.0, 74.0
    rounded_rect_path(ctx, bar_x, bar_y, bar_w, bar_h, 12.0)
    ctx.set_source_rgba(13 / 255, 19 / 255, 30 / 255, 0.98)
    ctx.fill_preserve()
    stage_accent = EMERALD if returning else VOICE_STAGES[active_stage][3]
    ctx.set_source_rgba(*stage_accent, 0.42)
    ctx.set_line_width(1.2)
    ctx.stroke()

    # Left accent vertical bar inside readout
    rounded_rect_path(ctx, bar_x + 12.0, bar_y + 15.0, 3.5, bar_h - 30.0, 2.0)
    ctx.set_source_rgba(*stage_accent, 0.95)
    ctx.fill()

    # Mini animated waveform bars on the right of readout during Wake/Listening/Speaking
    if active_stage in (1, 2, 5) and not returning:
        for b in range(7):
            bx = bar_x + bar_w - 64.0 + b * 7.0
            bh = 8.0 + 20.0 * (0.4 + 0.6 * abs(math.sin(t * 2.0 * math.pi * 6.0 + b * 0.85)))
            rounded_rect_path(ctx, bx, bar_y + bar_h / 2.0 - bh / 2.0, 3.2, bh, 1.6)
            ctx.set_source_rgba(*stage_accent, 0.85)
            ctx.fill()

    # Composite crisp PIL typography
    img = surface_to_pil(surf)
    draw = ImageDraw.Draw(img)

    f_hdr = load_font(FONT_MONO_SEMI, 12 * scale)
    f_pill = load_font(FONT_MONO_SEMI, 10 * scale)
    f_node_t = load_font(FONT_SANS_BOLD, 14 * scale)
    f_node_s = load_font(FONT_MONO, 10 * scale)
    f_state_k = load_font(FONT_MONO_BOLD, 12 * scale)
    f_state_v = load_font(FONT_SANS, 14 * scale)

    draw.text((28 * scale, 26 * scale), "ASH VOICE PIPELINE · STANDBY → WAKE → EXECUTE → REPLY", font=f_hdr, fill=TEXT_CYAN)
    draw.text((W * scale - 165 * scale, 26 * scale), "ILLUSTRATIVE FLOW", font=f_pill, fill=TEXT_SEC)

    for i, (title, sub, _desc, _accent) in enumerate(VOICE_STAGES):
        nx = node_xs[i] - node_w / 2.0
        ny = node_y - node_h / 2.0
        is_active = (i == active_stage) and (not returning or slot_frac > 0.72)
        draw.text((int((nx + 24) * scale), int((ny + 10) * scale)), title, font=f_node_t, fill=TEXT_PRI if is_active else (203, 213, 225, 255))
        draw.text((int((nx + 14) * scale), int((ny + 39) * scale)), sub, font=f_node_s, fill=TEXT_CYAN if is_active else TEXT_MUTED)

    if returning:
        label = "STATE 7 / 7 · RETURNING TO STANDBY"
        detail = "AudioOut & SCO/BLE communication route released · WakeModel resumes offline listening"
    else:
        st_title, _st_sub, st_desc, _ = VOICE_STAGES[active_stage]
        label = f"STATE {active_stage + 1} / 7 · {st_title.upper()}"
        detail = st_desc

    draw.text((int((bar_x + 28) * scale), int((bar_y + 15) * scale)), label, font=f_state_k, fill=TEXT_CYAN if not returning else (52, 211, 153, 255))
    draw.text((int((bar_x + 28) * scale), int((bar_y + 39) * scale)), detail, font=f_state_v, fill=TEXT_PRI)

    return img.resize((W, H), Image.Resampling.LANCZOS)


def write_architecture_svg_and_png(svg_path: Path, png_path: Path) -> None:
    """Writes a pure static GitHub-safe SVG architecture diagram and matching PNG fallback."""
    svg = """<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="460" viewBox="0 0 1000 460" role="img" aria-label="Project Ash System Architecture">
  <defs>
    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#090D14"/>
      <stop offset="100%" stop-color="#0D131E"/>
    </linearGradient>
    <linearGradient id="cyanLine" x1="0%" y1="0%" x2="100%" y2="0%">
      <stop offset="0%" stop-color="#38BDF8"/>
      <stop offset="100%" stop-color="#818CF8"/>
    </linearGradient>
    <marker id="arrowCyan" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 1 L 10 5 L 0 9 z" fill="#38BDF8"/>
    </marker>
    <marker id="arrowIndigo" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 1 L 10 5 L 0 9 z" fill="#818CF8"/>
    </marker>
    <marker id="arrowEmerald" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 1 L 10 5 L 0 9 z" fill="#34D399"/>
    </marker>
  </defs>

  <!-- Outer Card -->
  <rect x="4" y="4" width="992" height="452" rx="18" fill="url(#bg)" stroke="#243043" stroke-width="1.2"/>

  <!-- Header -->
  <text x="28" y="38" fill="#7DD3FC" font-family="ui-monospace, SFMono-Regular, Menlo, Consolas, monospace" font-size="12" font-weight="600" letter-spacing="1.2">SYSTEM ARCHITECTURE · NATIVE ANDROID KOTLIN (API 26–35)</text>
  <line x1="28" y1="52" x2="972" y2="52" stroke="#1E293B" stroke-width="1"/>

  <!-- Column 1: Audio & Input -->
  <rect x="28" y="76" width="208" height="154" rx="12" fill="#101723" stroke="#243043" stroke-width="1.2"/>
  <circle cx="46" cy="98" r="4" fill="#38BDF8"/>
  <text x="58" y="102" fill="#F1F5F9" font-family="system-ui, -apple-system, Segoe UI, sans-serif" font-size="14" font-weight="700">Audio &amp; Bluetooth</text>
  <text x="42" y="128" fill="#7DD3FC" font-family="ui-monospace, monospace" font-size="10.5">BluetoothAudioRouteController</text>
  <text x="42" y="148" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Phone, Wired, HFP SCO &amp; BLE</text>
  <text x="42" y="167" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Turn-scoped route negotiation</text>
  <text x="42" y="186" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• HeadsetButtonBridge fallback</text>
  <text x="42" y="205" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• RecordingCallback recovery</text>

  <!-- Column 2: On-Device Speech -->
  <rect x="272" y="76" width="212" height="154" rx="12" fill="#101723" stroke="#243043" stroke-width="1.2"/>
  <circle cx="290" cy="98" r="4" fill="#38BDF8"/>
  <text x="302" y="102" fill="#F1F5F9" font-family="system-ui, -apple-system, Segoe UI, sans-serif" font-size="14" font-weight="700">On-Device Speech</text>
  <text x="286" y="128" fill="#7DD3FC" font-family="ui-monospace, monospace" font-size="10.5">VoiceInput · sherpa-onnx</text>
  <text x="286" y="148" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Zipformer KWS (3.3M int8)</text>
  <text x="286" y="167" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• "Hey Ash" &amp; "Hello Ash"</text>
  <text x="286" y="186" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Kroko streaming ASR + MicGain</text>
  <text x="286" y="205" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• 1.5s pre-roll wake handoff</text>

  <!-- Column 3: Orchestration & Routing -->
  <rect x="520" y="76" width="212" height="154" rx="12" fill="#101723" stroke="#38BDF8" stroke-opacity="0.55" stroke-width="1.4"/>
  <circle cx="538" cy="98" r="4" fill="#818CF8"/>
  <text x="550" y="102" fill="#F1F5F9" font-family="system-ui, -apple-system, Segoe UI, sans-serif" font-size="14" font-weight="700">Orchestration</text>
  <text x="534" y="128" fill="#A5B4FC" font-family="ui-monospace, monospace" font-size="10.5">JarvisService (FGS mic)</text>
  <text x="534" y="148" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• FastCommandRouter (offline)</text>
  <text x="534" y="167" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• AiRouter (Auto/Online/Offline)</text>
  <text x="534" y="186" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• TaskBoard + LockedCommands</text>
  <text x="534" y="205" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• AshVoiceInteractionService</text>

  <!-- Column 4: AI Providers -->
  <rect x="768" y="76" width="204" height="154" rx="12" fill="#101723" stroke="#243043" stroke-width="1.2"/>
  <circle cx="786" cy="98" r="4" fill="#818CF8"/>
  <text x="798" y="102" fill="#F1F5F9" font-family="system-ui, -apple-system, Segoe UI, sans-serif" font-size="14" font-weight="700">AI Reasoning</text>
  <text x="782" y="128" fill="#A5B4FC" font-family="ui-monospace, monospace" font-size="10.5">PhoneAgent &amp; Llm</text>
  <text x="782" y="148" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Anthropic Claude SDK</text>
  <text x="782" y="167" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• OpenAI, Groq, Gemini, Router</text>
  <text x="782" y="186" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Ollama, LM Studio, local LLM</text>
  <text x="782" y="205" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Encrypted Keystore secrets</text>

  <!-- Bottom Left: Android Execution & Safety -->
  <rect x="28" y="276" width="456" height="152" rx="12" fill="#101723" stroke="#F59E0B" stroke-opacity="0.45" stroke-width="1.2"/>
  <circle cx="46" cy="298" r="4" fill="#F59E0B"/>
  <text x="58" y="302" fill="#F1F5F9" font-family="system-ui, -apple-system, Segoe UI, sans-serif" font-size="14" font-weight="700">Android Execution &amp; Authorization Layer</text>
  <text x="42" y="328" fill="#FCD34D" font-family="ui-monospace, monospace" font-size="10.5">FastActionExecutor · JarvisAccessibilityService · ScreenReader</text>
  <text x="42" y="350" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• DirectCallPolicy + TelecomManager.placeCall / ACTION_DIAL + cooldown</text>
  <text x="42" y="370" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Verified UI tree taps, editable fields, clickable-ancestor &amp; gestures</text>
  <text x="42" y="390" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Blocks banking/wallet/installer apps, password fields &amp; FLAG_SECURE</text>
  <text x="42" y="410" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• OneUseApproval spoken confirmation gate before sensitive controls</text>

  <!-- Bottom Right: Voice Output & UI -->
  <rect x="520" y="276" width="452" height="152" rx="12" fill="#101723" stroke="#34D399" stroke-opacity="0.45" stroke-width="1.2"/>
  <circle cx="538" cy="298" r="4" fill="#34D399"/>
  <text x="550" y="302" fill="#F1F5F9" font-family="system-ui, -apple-system, Segoe UI, sans-serif" font-size="14" font-weight="700">Spoken Output &amp; Live Feedback</text>
  <text x="534" y="328" fill="#6EE7B7" font-family="ui-monospace, monospace" font-size="10.5">Speaker · Kokoro · ReplyStreamer · AudioOut · TaskIsland</text>
  <text x="534" y="350" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Kokoro-82M int8 neural TTS (clause streaming + wake pre-warm)</text>
  <text x="534" y="370" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Cloud TTS fallbacks (OpenAI, ElevenLabs, Fish) &amp; SystemVoice</text>
  <text x="534" y="390" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Transient audio-focus ducking (resumes music) &amp; barge-in</text>
  <text x="534" y="410" fill="#94A3B8" font-family="system-ui, sans-serif" font-size="11">• Dynamic TaskIsland overlay + local SQLite FTS4 MemoryStore</text>

  <!-- Connectors -->
  <line x1="236" y1="153" x2="268" y2="153" stroke="#38BDF8" stroke-width="2" marker-end="url(#arrowCyan)"/>
  <line x1="484" y1="153" x2="516" y2="153" stroke="#38BDF8" stroke-width="2" marker-end="url(#arrowCyan)"/>
  <line x1="732" y1="143" x2="764" y2="143" stroke="#818CF8" stroke-width="1.8" marker-end="url(#arrowIndigo)"/>
  <line x1="768" y1="165" x2="736" y2="165" stroke="#818CF8" stroke-width="1.8" marker-end="url(#arrowIndigo)"/>

  <!-- Down connectors from Orchestration -->
  <path d="M 590 230 L 590 253 L 260 253 L 260 272" fill="none" stroke="#38BDF8" stroke-width="1.8" marker-end="url(#arrowCyan)"/>
  <path d="M 656 230 L 656 272" fill="none" stroke="#34D399" stroke-width="1.8" marker-end="url(#arrowEmerald)"/>

  <!-- Labels on connectors -->
  <text x="392" y="248" fill="#64748B" font-family="ui-monospace, monospace" font-size="10">verified actions</text>
  <text x="668" y="256" fill="#64748B" font-family="ui-monospace, monospace" font-size="10">streamed speech</text>
</svg>
"""
    svg_path.write_text(svg, encoding="utf-8")

    # Also draw crisp PNG version directly using Cairo + PIL (no external rsvg-convert needed)
    W, H, scale = 1000, 460, 2
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, W * scale, H * scale)
    ctx = cairo.Context(surf)
    ctx.scale(scale, scale)
    draw_card_canvas(ctx, W, H, pad=4.0, radius=18.0)

    ctx.set_source_rgba(*STROKE, 0.8)
    ctx.set_line_width(1.0)
    ctx.move_to(28, 52)
    ctx.line_to(972, 52)
    ctx.stroke()

    boxes = [
        (28, 76, 208, 154, STROKE, CYAN),
        (272, 76, 212, 154, STROKE, CYAN),
        (520, 76, 212, 154, CYAN, INDIGO),
        (768, 76, 204, 154, STROKE, INDIGO),
        (28, 276, 456, 152, AMBER, AMBER),
        (520, 276, 452, 152, EMERALD, EMERALD),
    ]
    for bx, by, bw, bh, bcol, dotc in boxes:
        rounded_rect_path(ctx, bx, by, bw, bh, 12.0)
        ctx.set_source_rgba(*SURFACE, 0.98)
        ctx.fill_preserve()
        ctx.set_source_rgba(*bcol, 0.55 if bcol != STROKE else 0.95)
        ctx.set_line_width(1.3)
        ctx.stroke()
        ctx.set_source_rgba(*dotc, 0.95)
        ctx.arc(bx + 18, by + 22, 4.0, 0, 2 * math.pi)
        ctx.fill()

    def draw_arrow(x1: float, y1: float, x2: float, y2: float, col: tuple[float, float, float]) -> None:
        ctx.set_source_rgba(*col, 0.9)
        ctx.set_line_width(1.9)
        ctx.move_to(x1, y1)
        ctx.line_to(x2, y2)
        ctx.stroke()
        ang = math.atan2(y2 - y1, x2 - x1)
        size = 6.5
        ctx.move_to(x2, y2)
        ctx.line_to(x2 - size * math.cos(ang - 0.45), y2 - size * math.sin(ang - 0.45))
        ctx.line_to(x2 - size * math.cos(ang + 0.45), y2 - size * math.sin(ang + 0.45))
        ctx.close_path()
        ctx.fill()

    draw_arrow(236, 153, 270, 153, CYAN)
    draw_arrow(484, 153, 518, 153, CYAN)
    draw_arrow(732, 143, 766, 143, INDIGO)
    draw_arrow(768, 165, 734, 165, INDIGO)

    # Down elbows
    ctx.set_source_rgba(*CYAN, 0.9)
    ctx.set_line_width(1.8)
    ctx.move_to(590, 230)
    ctx.line_to(590, 253)
    ctx.line_to(260, 253)
    ctx.stroke()
    draw_arrow(260, 253, 260, 274, CYAN)
    draw_arrow(656, 230, 656, 274, EMERALD)

    img = surface_to_pil(surf)
    draw = ImageDraw.Draw(img)
    f_hdr = load_font(FONT_MONO_SEMI, 12 * scale)
    f_title = load_font(FONT_SANS_BOLD, 14 * scale)
    f_sub = load_font(FONT_MONO_SEMI, 9 * scale)
    f_body = load_font(FONT_SANS, 10 * scale)
    f_tiny = load_font(FONT_MONO, 10 * scale)

    draw.text((28 * scale, 26 * scale), "SYSTEM ARCHITECTURE · NATIVE ANDROID KOTLIN (API 26–35)", font=f_hdr, fill=TEXT_CYAN)

    content = [
        (28, 76, "Audio & Bluetooth", "BluetoothAudioRouteController", TEXT_CYAN, [
            "• Phone, Wired, HFP SCO & BLE",
            "• Turn-scoped route negotiation",
            "• HeadsetButtonBridge fallback",
            "• RecordingCallback recovery",
        ]),
        (272, 76, "On-Device Speech", "VoiceInput · sherpa-onnx", TEXT_CYAN, [
            "• Zipformer KWS (3.3M int8)",
            '• "Hey Ash" & "Hello Ash"',
            "• Kroko streaming ASR + MicGain",
            "• 1.5s pre-roll wake handoff",
        ]),
        (520, 76, "Orchestration", "JarvisService (FGS mic)", (165, 180, 252, 255), [
            "• FastCommandRouter (offline)",
            "• AiRouter (Auto/Online/Offline)",
            "• TaskBoard + LockedCommands",
            "• AshVoiceInteractionService",
        ]),
        (768, 76, "AI Reasoning", "PhoneAgent & Llm", (165, 180, 252, 255), [
            "• Anthropic Claude SDK",
            "• OpenAI, Groq, Gemini, Router",
            "• Ollama, LM Studio, local LLM",
            "• Encrypted Keystore secrets",
        ]),
        (28, 276, "Android Execution & Authorization Layer", "FastActionExecutor · JarvisAccessibilityService · ScreenReader", (252, 211, 77, 255), [
            "• DirectCallPolicy + TelecomManager.placeCall / ACTION_DIAL + cooldown",
            "• Verified UI tree taps, editable fields, clickable-ancestor & gestures",
            "• Blocks banking/wallet/installer apps, password fields & FLAG_SECURE",
            "• OneUseApproval spoken confirmation gate before sensitive controls",
        ]),
        (520, 276, "Spoken Output & Live Feedback", "Speaker · Kokoro · ReplyStreamer · AudioOut · TaskIsland", (110, 231, 183, 255), [
            "• Kokoro-82M int8 neural TTS (clause streaming + wake pre-warm)",
            "• Cloud TTS fallbacks (OpenAI, ElevenLabs, Fish) & SystemVoice",
            "• Transient audio-focus ducking (resumes music) & barge-in",
            "• Dynamic TaskIsland overlay + local SQLite FTS4 MemoryStore",
        ]),
    ]
    for bx, by, title, sub, scol, bullets in content:
        draw.text((int((bx + 30) * scale), int((by + 13) * scale)), title, font=f_title, fill=TEXT_PRI)
        draw.text((int((bx + 14) * scale), int((by + 40) * scale)), sub, font=f_sub, fill=scol)
        for idx, line in enumerate(bullets):
            draw.text((int((bx + 14) * scale), int((by + 63 + idx * 20) * scale)), line, font=f_body, fill=TEXT_SEC)

    draw.text((385 * scale, 238 * scale), "verified actions", font=f_tiny, fill=TEXT_MUTED)
    draw.text((666 * scale, 244 * scale), "streamed speech", font=f_tiny, fill=TEXT_MUTED)

    img.resize((W, H), Image.Resampling.LANCZOS).save(png_path, format="PNG", optimize=True)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    print("Rendering hero animation (90 frames @ 30.0 FPS)...")
    hero_frames = [render_hero_frame(i, 90) for i in range(90)]
    hero_frames[18].save(OUT_DIR / "ash-hero-static.png", format="PNG", optimize=True)
    save_gif_30fps(hero_frames, OUT_DIR / "ash-hero.gif", max_colors=64)

    print("Rendering voice interaction flow (150 frames @ 30.0 FPS)...")
    flow_frames = [render_voice_flow_frame(i, 150) for i in range(150)]
    flow_frames[28].save(OUT_DIR / "ash-voice-flow-static.png", format="PNG", optimize=True)
    save_gif_30fps(flow_frames, OUT_DIR / "ash-voice-flow.gif", max_colors=64)

    print("Rendering architecture SVG + PNG...")
    write_architecture_svg_and_png(OUT_DIR / "ash-architecture.svg", OUT_DIR / "ash-architecture.png")

    for p in sorted(OUT_DIR.glob("*")):
        if p.is_file():
            print(f"  {p.name}: {p.stat().st_size / 1024:.1f} KB")


if __name__ == "__main__":
    main()
