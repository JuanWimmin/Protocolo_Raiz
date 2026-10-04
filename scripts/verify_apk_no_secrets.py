#!/usr/bin/env python3
"""Verifica que un APK (o cualquier archivo/carpeta) no contiene claves privadas de Stellar.

Evidencia D1 del SOW Instaward: "release APK with zero embedded secrets, verifiable by
decompilation". Un APK es un zip: este script lo abre, recorre TODOS sus archivos
(bytecode `classes*.dex`, `assets/`, `res/`, `resources.arsc`, librerías nativas…) y busca
texto con forma de clave privada de Stellar.

Una clave privada de Stellar ("secret seed") son 56 caracteres que empiezan por `S`, en
base32, con un byte de versión (0x90) y un checksum CRC16 al final. Por eso no basta un
`grep`: cualquier tramo de 56 letras mayúsculas que empiece por `S` (los hay en binarios
como `libmapbox-common.so`) "parece" una clave. Aquí cada candidato se decodifica y solo
cuenta si el byte de versión y el checksum son correctos, es decir, si ES una clave real.

Busca en ASCII/UTF-8 (como están las constantes en el bytecode) y en UTF-16 (como puede
guardarlas `resources.arsc`), con coincidencias solapadas, y entra en zips anidados.

Uso:
    python scripts/verify_apk_no_secrets.py <ruta.apk | URL https | carpeta | archivo | ->
    python scripts/verify_apk_no_secrets.py --self-test
Opciones:
    --public G…      informa en qué archivos aparece esa dirección PÚBLICA (no es secreta)
    --summary RUTA   añade el resultado en Markdown a ese archivo (p. ej. $GITHUB_STEP_SUMMARY)

Código de salida: 0 si hay 0 claves privadas válidas; 1 si hay alguna; 2 si falló la ejecución.
Nunca imprime una clave: solo dónde está. Solo usa la biblioteca estándar de Python 3.8+.
"""
import argparse
import base64
import hashlib
import io
import os
import re
import sys
import tempfile
import urllib.request
import zipfile

# Candidato amplio: el mismo patrón del `grep` documentado (S + 55 de [A-Z0-9]).
BROAD = re.compile(rb"S[A-Z0-9]{55}")
# Candidato estricto, con solapamiento: S + 55 caracteres del alfabeto base32 (A-Z, 2-7).
STRICT = re.compile(rb"(?=(S[A-Z2-7]{55}))")
SEED_VERSION = 18 << 3  # 0x90: byte de versión de una secret seed ed25519 (StrKey)


def crc16_xmodem(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) if crc & 0x8000 else (crc << 1)
            crc &= 0xFFFF
    return crc


def is_valid_seed(candidate: bytes) -> bool:
    """True si los 56 caracteres decodifican a una secret seed de Stellar con checksum correcto."""
    try:
        raw = base64.b32decode(candidate)
    except Exception:  # noqa: BLE001 — no es base32
        return False
    if len(raw) != 35 or raw[0] != SEED_VERSION:
        return False
    return crc16_xmodem(raw[:33]) == int.from_bytes(raw[33:], "little")


def encode_seed(payload32: bytes) -> bytes:
    """Construye una secret seed válida a partir de 32 bytes (solo para la autoprueba)."""
    body = bytes([SEED_VERSION]) + payload32
    return base64.b32encode(body + crc16_xmodem(body).to_bytes(2, "little"))


class Report:
    def __init__(self):
        self.files = 0
        self.bytes = 0
        self.broad = {}      # archivo -> nº de cadenas con FORMA de clave (grep amplio)
        self.valid = []      # (archivo, codificación, offset) de claves REALES
        self.public = []     # archivos donde aparece la dirección pública pedida

    def scan(self, name: str, data: bytes, public: bytes = b"", depth: int = 0):
        self.files += 1
        self.bytes += len(data)
        n = len(BROAD.findall(data))
        if n:
            self.broad[name] = n
        views = (("ascii/utf-8", data), ("utf-16 (par)", data[0::2]), ("utf-16 (impar)", data[1::2]))
        for label, view in views:
            for m in STRICT.finditer(view):
                if is_valid_seed(m.group(1)):
                    self.valid.append((name, label, m.start()))
        if public and (public in data or public in data[0::2] or public in data[1::2]):
            self.public.append(name)
        if depth < 3 and data[:4] == b"PK\x03\x04":  # zip anidado (jar/aar/apk dentro del APK)
            try:
                with zipfile.ZipFile(io.BytesIO(data)) as inner:
                    for info in inner.infolist():
                        if not info.is_dir():
                            self.scan(f"{name}!{info.filename}", inner.read(info), public, depth + 1)
            except zipfile.BadZipFile:
                pass


def fetch(target: str) -> str:
    """Devuelve una ruta local; descarga si es una URL."""
    if not target.startswith(("http://", "https://")):
        return target
    if not target.startswith("https://"):
        raise SystemExit("Solo se descargan URLs https.")
    fd, path = tempfile.mkstemp(suffix=".apk")
    req = urllib.request.Request(target, headers={"User-Agent": "raiz-verify-apk/1.0"})
    with urllib.request.urlopen(req, timeout=120) as resp, os.fdopen(fd, "wb") as out:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            out.write(chunk)
    return path


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def scan_target(path: str, public: bytes) -> Report:
    rep = Report()
    if path == "-":
        rep.scan("<stdin>", sys.stdin.buffer.read(), public)
    elif os.path.isdir(path):
        for root, dirs, names in os.walk(path):
            dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", "node_modules", "target")]
            for n in names:
                full = os.path.join(root, n)
                with open(full, "rb") as f:
                    rep.scan(os.path.relpath(full, path).replace(os.sep, "/"), f.read(), public)
    elif zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for info in z.infolist():
                if not info.is_dir():
                    rep.scan(info.filename, z.read(info), public)
    else:
        with open(path, "rb") as f:
            rep.scan(os.path.basename(path), f.read(), public)
    return rep


def self_test() -> int:
    """Demuestra que el buscador SÍ encuentra una clave cuando la hay (y solo cuando la hay)."""
    seed = encode_seed(os.urandom(32))          # clave aleatoria, se descarta al terminar
    decoy = b"SACTIONA" + b"B" * 48               # 56 mayúsculas con forma de clave, checksum inválido
    assert is_valid_seed(seed) and not is_valid_seed(decoy)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("classes.dex", b"\x00dex\n035" + b"relleno" * 9 + seed + b"\x00mas")
        z.writestr("resources.arsc", b"\x02\x00" + seed.decode().encode("utf-16-le") + b"\x00\x00")
        z.writestr("lib/arm64-v8a/libmapbox-common.so", b"\x7fELF" + decoy * 3)
        z.writestr("assets/pegada.txt", b"XXXXS" + seed)   # precedida de mayúsculas: exige solapamiento
        inner = io.BytesIO()
        with zipfile.ZipFile(inner, "w") as zi:
            zi.writestr("dentro.txt", seed)
        z.writestr("assets/anidado.jar", inner.getvalue())
        z.writestr("assets/limpio.json", b'{"admin":"GAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAWHF"}')
    rep = Report()
    with zipfile.ZipFile(io.BytesIO(buf.getvalue())) as z:
        for info in z.infolist():
            rep.scan(info.filename, z.read(info))
    found = {name for name, _, _ in rep.valid}
    expected = {"classes.dex", "resources.arsc", "assets/pegada.txt", "assets/anidado.jar!dentro.txt"}
    ok = expected <= found and "lib/arm64-v8a/libmapbox-common.so" not in found \
        and "assets/limpio.json" not in found and rep.broad.get("lib/arm64-v8a/libmapbox-common.so") == 3
    print("Autoprueba:", "OK" if ok else "FALLO",
          f"— detectó la clave plantada en {len(found & expected)}/4 sitios (dex, UTF-16 en arsc, "
          "pegada a mayúsculas, zip anidado) y descartó el señuelo con checksum inválido.")
    return 0 if ok else 2


def main() -> int:
    ap = argparse.ArgumentParser(description="Verifica que un APK no contiene claves privadas de Stellar.")
    ap.add_argument("target", nargs="?", help="ruta al APK, URL https, carpeta, archivo o - (stdin)")
    ap.add_argument("--public", default="", help="dirección pública G… cuya presencia se informa")
    ap.add_argument("--summary", default="", help="archivo al que añadir el resultado en Markdown")
    ap.add_argument("--self-test", action="store_true", help="comprueba que el buscador detecta una clave plantada")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not args.target:
        ap.error("falta el APK (ruta o URL)")

    path = fetch(args.target)
    rep = scan_target(path, args.public.encode())
    is_file = path != "-" and os.path.isfile(path)
    digest = sha256_file(path) if is_file else "-"
    size = os.path.getsize(path) if is_file else rep.bytes
    broad_total = sum(rep.broad.values())
    ok = not rep.valid

    print(f"Objetivo:  {args.target}")
    print(f"SHA-256:   {digest}")
    print(f"Tamaño:    {size} bytes · {rep.files} archivos examinados")
    print(f"Cadenas con FORMA de clave (S + 55 de [A-Z0-9]): {broad_total}")
    for name, n in sorted(rep.broad.items()):
        print(f"    {n:4d}  {name}")
    print(f"Claves privadas de Stellar VÁLIDAS (versión + checksum correctos): {len(rep.valid)}")
    for name, enc, off in rep.valid:
        print(f"    CLAVE REAL en {name} ({enc}, posición {off}) — no se imprime")
    if args.public:
        where = ", ".join(sorted(set(rep.public))) or "ningún archivo"
        print(f"Dirección pública {args.public[:8]}…{args.public[-4:]} (no es secreta) aparece en: {where}")
    print("RESULTADO:", "OK — cero claves privadas" if ok else "FALLO — el archivo contiene claves privadas")

    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as out:
            out.write("### Verificación del APK: claves privadas de Stellar\n\n")
            out.write("| Dato | Valor |\n|---|---|\n")
            out.write(f"| APK | `{args.target}` |\n| SHA-256 | `{digest}` |\n")
            out.write(f"| Tamaño | {size} bytes, {rep.files} archivos examinados |\n")
            out.write(f"| Cadenas con forma de clave (`S` + 55 de `[A-Z0-9]`) | {broad_total} |\n")
            out.write(f"| **Claves privadas válidas** (byte de versión y checksum correctos) | **{len(rep.valid)}** |\n")
            if args.public:
                where = ", ".join(f"`{p}`" for p in sorted(set(rep.public))) or "ningún archivo"
                out.write(f"| Dirección pública del admin `{args.public[:8]}…` (no secreta) | {where} |\n")
            out.write(f"\n**Resultado: {'✅ cero claves privadas' if ok else '❌ contiene claves privadas'}**\n")
            if rep.broad:
                out.write("\nDónde están las cadenas con forma de clave (ninguna pasa el checksum):\n\n")
                for name, n in sorted(rep.broad.items()):
                    out.write(f"- `{name}`: {n}\n")
    return 0 if ok else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(2)
