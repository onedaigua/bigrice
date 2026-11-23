#!/usr/bin/env python3
"""Packs the linked base APK with the dex, the runtime assets and the native libs.

aapt2 link produces an APK that holds only the manifest and resources; this step adds
classes.dex, assets/{dshtool.zip,bigrice-ui.tar,register-bundle.js} and
lib/arm64-v8a/*.so (the node/V8 blob and its friends must live under lib/<abi>/ because
Android only allows exec from nativeLibraryDir).

    python3 assemble.py <work-dir> <output-apk>
"""
import pathlib
import sys
import zipfile

DEFLATE = zipfile.ZIP_DEFLATED
STORED = zipfile.ZIP_STORED


def kind(name: str) -> int:
    if name.endswith((".zip", ".gz", ".png", ".jpg", ".webp")):
        return STORED
    return DEFLATE


def main() -> int:
    work = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2])
    base = work / "apk" / "base.apk"
    dex = work / "dex" / "classes.dex"
    assets = work / "apk" / "assets"
    libs = pathlib.Path(__file__).resolve().parent / "libs" / "arm64-v8a"
    if not base.is_file():
        print(f"missing {base}", file=sys.stderr)
        return 1
    if not dex.is_file():
        print(f"missing {dex}", file=sys.stderr)
        return 1

    out.parent.mkdir(parents=True, exist_ok=True)
    count = 0
    with zipfile.ZipFile(base) as src, zipfile.ZipFile(out, "w", DEFLATE) as dst:
        for info in src.infolist():
            data = src.read(info.filename)
            dst.writestr(info, data, compress_type=kind(info.filename))
            count += 1

        dst.writestr("classes.dex", dex.read_bytes(), compress_type=DEFLATE)
        count += 1

        for path in sorted(assets.rglob("*")):
            if path.is_file():
                name = "assets/" + path.relative_to(assets).as_posix()
                dst.writestr(name, path.read_bytes(), compress_type=kind(name))
                count += 1

        for path in sorted(libs.glob("*.so")):
            name = "lib/arm64-v8a/" + path.name
            dst.writestr(name, path.read_bytes(), compress_type=DEFLATE)
            count += 1

    print(f"    packed {count} entries -> {out} ({out.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
