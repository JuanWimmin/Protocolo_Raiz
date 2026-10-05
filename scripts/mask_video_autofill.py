#!/usr/bin/env python3
"""Tapa los datos personales que el teclado enseña mientras se rellena el formulario del anchor.

Al grabar un depósito SEP-24 (D3), el formulario del anchor de prueba se abre en una Custom Tab de
Chrome. Con el teclado abierto aparecen dos franjas que muestran, por instantes, datos guardados en
el teléfono (nombre, correos):

  - la barra de sugerencias de autocompletar de Chrome, justo encima del teclado;
  - la fila de predicciones del propio teclado.

El script detecta en qué tramos de la grabación hay un teclado abierto sobre la Custom Tab, pinta
esas dos franjas de un color liso solo en esos tramos y, al terminar, comprueba fotograma a
fotograma que en la salida quedaron lisas (si no, sale con error). No corta nada por su cuenta.

    python scripts/mask_video_autofill.py ENTRADA.mp4 SALIDA.mp4
        [--start S] [--end S]   recorta al tramo [S, S] de la entrada
        [--speed 1.0]           acelera de forma uniforme
        [--tail S]              mantiene el último fotograma S segundos más
        [--crf 28]              calidad de x264

Requiere `ffmpeg` en el PATH. La geometría es la de una grabación de 720x1612 (Motorola G04) con
Chrome y el teclado del teléfono de pruebas: en otro dispositivo hay que volver a medir las
constantes de abajo, y revisar SIEMPRE el resultado a ojo antes de publicar.
"""
import argparse
import subprocess
import sys

W, H = 720, 1612
SCALE = 4                      # la detección corre sobre fotogramas de 180x403
SW, SH = W // SCALE, H // SCALE
STRIP_Y, STRIP_H = 918, 84     # barra de sugerencias de Chrome con el teclado abierto
FILL = "0xFDF8FA"              # su color de fondo
IME_Y, IME_H = 1064, 72        # fila de predicciones del propio teclado (también sugiere correos)
IME_FILL = "0x267A9C"          # tono medio del degradado del teclado
SCAN_FPS = 10
PAD = 0.12                     # margen a cada lado del tramo detectado (s)


def near(c, ref, tol=28):
    return all(abs(a - b) <= tol for a, b in zip(c, ref))


def scan(path):
    """Tramos (inicio, fin) en segundos en los que hay teclado abierto sobre la Custom Tab."""
    cmd = ["ffmpeg", "-v", "error", "-i", path, "-vf", f"fps={SCAN_FPS},scale={SW}:{SH}",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    size = SW * SH * 3
    flags = []
    while True:
        buf = proc.stdout.read(size)
        if len(buf) < size:
            break

        def px(x, y):
            i = ((y // SCALE) * SW + (x // SCALE)) * 3
            return buf[i], buf[i + 1], buf[i + 2]

        keyboard = near(px(700, 1035), (42, 120, 150))       # fila de herramientas del teclado
        custom_tab = max(px(400, 60)) < 70                     # barra de estado negra de la Custom Tab
        flags.append(keyboard and custom_tab)
    proc.wait()
    spans, start = [], None
    for i, f in enumerate(flags + [False]):
        if f and start is None:
            start = i
        if not f and start is not None:
            spans.append((max(0.0, start / SCAN_FPS - PAD), i / SCAN_FPS + PAD))
            start = None
    return spans, len(flags) / SCAN_FPS


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src")
    ap.add_argument("dst")
    ap.add_argument("--start", type=float, default=None)
    ap.add_argument("--end", type=float, default=None)
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--crf", type=int, default=28)
    ap.add_argument("--tail", type=float, default=0.0, help="segundos del último fotograma que se añaden al final")
    args = ap.parse_args()

    spans, duration = scan(args.src)
    print(f"duración {duration:.1f} s · teclado sobre la web en:", ", ".join(f"{a:.1f}–{b:.1f}" for a, b in spans) or "ningún tramo")
    filters = []
    for a, b in spans:
        for y, h, color in ((STRIP_Y, STRIP_H, FILL), (IME_Y, IME_H, IME_FILL)):
            filters.append(
                f"drawbox=x=0:y={y}:w={W}:h={h}:color={color}@1:t=fill:enable='between(t,{a:.2f},{b:.2f})'"
            )
    # fps ANTES del recorte: la grabación es de fotogramas variables y una pantalla quieta no
    # emite fotogramas; sin esto el recorte terminaría en el último cambio de pantalla.
    filters.append("fps=30")
    if args.start is not None or args.end is not None:
        trim = "trim=" + ":".join(
            p for p in (f"start={args.start}" if args.start is not None else "",
                        f"end={args.end}" if args.end is not None else "") if p)
        filters.append(trim)
    filters.append(f"setpts=(PTS-STARTPTS)/{args.speed}")
    if args.speed != 1.0:
        filters.append("fps=30")
    if args.tail > 0:
        filters.append(f"tpad=stop_mode=clone:stop_duration={args.tail}")
    cmd = ["ffmpeg", "-v", "error", "-y", "-i", args.src, "-vf", ",".join(filters), "-an",
           "-c:v", "libx264", "-preset", "slow", "-crf", str(args.crf), "-pix_fmt", "yuv420p",
           "-movflags", "+faststart", args.dst]
    subprocess.run(cmd, check=True)

    # Verificación: en la salida, con teclado sobre la web, las dos franjas deben ser de un solo color.
    out_spans, out_dur = scan(args.dst)
    bad = []
    for name, y, h in (("sugerencias", STRIP_Y, STRIP_H), ("predicciones", IME_Y, IME_H)):
        ch = h - 12
        cmd = ["ffmpeg", "-v", "error", "-i", args.dst, "-vf", f"fps={SCAN_FPS},crop={W}:{ch}:0:{y + 6}",
               "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
        size = W * ch * 3
        i = 0
        while True:
            buf = proc.stdout.read(size)
            if len(buf) < size:
                break
            t = i / SCAN_FPS
            if any(a + PAD <= t <= b - PAD for a, b in out_spans):
                ref = (buf[0], buf[1], buf[2])
                odd = sum(1 for k in range(0, size, 3 * 5)
                          if max(abs(buf[k] - ref[0]), abs(buf[k + 1] - ref[1]), abs(buf[k + 2] - ref[2])) > 14)
                if odd > 0:
                    bad.append((name, round(t, 1), odd))
            i += 1
        proc.wait()
    print(f"salida {out_dur:.1f} s · fotogramas con contenido en las franjas tapadas: {bad if bad else 'ninguno'}")
    if bad:
        sys.exit(2)


if __name__ == "__main__":
    main()
