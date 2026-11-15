"""Merge compiled ViaBedrock classes into an isolated, launcher-preserving test jar."""
import argparse
from collections import Counter
from copy import copy
import hashlib
from pathlib import Path
import shutil
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "_m6/gw-data/gateway/ViaProxy-3.4.14.jar"
CLASSES = ROOT / "_m6/gateway-build/ViaBedrock/build/classes/java/main"
JAVA_SOURCES = ROOT / "_m6/gateway-build/ViaBedrock/src/main/java"
OUTPUT = ROOT / "_m6/gw-data/gateway/ViaProxy-mpmc19-test.jar"
PREFIX = "net/raphimc/viabedrock/"
PIN = "bdeab29f84e9bfa81714da15da49e611438d04e71aa93d088ec0854bdf4f78f8"
MANIFEST = "META-INF/MANIFEST.MF"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def entry_digest(archive, name):
    with archive.open(name) as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def unique_names(archive):
    names = archive.namelist()
    duplicates = [name for name, count in Counter(names).items() if count > 1]
    if duplicates:
        raise RuntimeError(f"Duplicate ZIP entries: {duplicates[:10]}")
    return set(names)


def source_snapshot():
    snapshot = {}
    for source in sorted((JAVA_SOURCES / PREFIX).rglob("*.java")):
        if source.name in {"package-info.java", "module-info.java"}:
            continue
        relative = source.relative_to(JAVA_SOURCES)
        compiled = CLASSES / relative.with_suffix(".class")
        if not compiled.is_file() or source.stat().st_mtime_ns > compiled.stat().st_mtime_ns:
            raise RuntimeError(f"Compile before merging; source is newer than or missing its class: {relative}")
        snapshot[relative.as_posix()] = (source.stat().st_mtime_ns, digest(source))
    if not snapshot:
        raise RuntimeError("ViaBedrock Java sources are missing")
    return snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=OUTPUT, help="New absolute D-drive jar path; existing files are never overwritten")
    output = parser.parse_args().output
    if not output.is_absolute() or output.drive.lower() != "d:":
        parser.error("--output must be an absolute D-drive path")
    output = output.resolve()
    if output.drive.lower() != "d:" or not output.parent.is_dir():
        parser.error("--output must resolve to an existing parent directory on D")
    if output.exists():
        raise FileExistsError(f"Refusing to replace existing test artifact: {output}")
    sources_before = source_snapshot()
    if digest(SOURCE) != PIN:
        raise RuntimeError("Source gateway does not match the pinned .18 artifact")
    namespace = CLASSES / PREFIX
    replacements = {
        path.relative_to(CLASSES).as_posix(): path
        for path in sorted(namespace.rglob("*.class"))
        if path.is_file()
    }
    if not replacements or PREFIX + "protocol/BedrockProtocol.class" not in replacements:
        raise RuntimeError("Compiled ViaBedrock classes are missing or incomplete")
    expected_classes = {name: digest(path) for name, path in replacements.items()}
    temporary = None
    try:
        with zipfile.ZipFile(SOURCE) as original:
            original_names = unique_names(original)
            manifest = original.read(MANIFEST)
            if b"Launcher-Agent-Class: net.raphimc.viaproxy.ViaProxy" not in manifest:
                raise RuntimeError("Expected ViaProxy launcher agent is absent")
            with tempfile.NamedTemporaryFile(prefix=output.name + ".", suffix=".tmp", dir=output.parent, delete=False) as handle:
                temporary = Path(handle.name)
            with zipfile.ZipFile(temporary, "w", allowZip64=True) as merged:
                merged.comment = original.comment
                for info in original.infolist():
                    replacement = replacements.get(info.filename)
                    metadata = copy(info)
                    if replacement is not None:
                        metadata.file_size = replacement.stat().st_size
                    with merged.open(metadata, "w") as destination:
                        if replacement is not None:
                            with replacement.open("rb") as source:
                                shutil.copyfileobj(source, destination, 1024 * 1024)
                        else:
                            with original.open(info) as source:
                                shutil.copyfileobj(source, destination, 1024 * 1024)
                for name in sorted(replacements.keys() - original_names):
                    metadata = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                    metadata.compress_type = zipfile.ZIP_DEFLATED
                    metadata.file_size = replacements[name].stat().st_size
                    with replacements[name].open("rb") as source, merged.open(metadata, "w") as destination:
                        shutil.copyfileobj(source, destination, 1024 * 1024)
            with zipfile.ZipFile(temporary) as merged:
                if unique_names(merged) != original_names | replacements.keys():
                    raise RuntimeError("Unexpected output entries")
                if merged.read(MANIFEST) != manifest or merged.comment != original.comment:
                    raise RuntimeError("Manifest or archive comment changed")
                for name in original_names - replacements.keys():
                    if entry_digest(original, name) != entry_digest(merged, name):
                        raise RuntimeError(f"Non-ViaBedrock entry changed: {name}")
                for name, expected in expected_classes.items():
                    if entry_digest(merged, name) != expected:
                        raise RuntimeError(f"Compiled class changed during merge: {name}")
        if source_snapshot() != sources_before:
            raise RuntimeError("Java sources changed during merge; compile again")
        if output.exists():
            raise FileExistsError(f"Output appeared during merge: {output}")
        temporary.rename(output)
        temporary = None
        print(f"TEST ARTIFACT: {output}")
        print(f"SHA256: {digest(output)}")
        print(f"ViaBedrock classes replaced: {len(replacements.keys() & original_names)}")
        print(f"ViaBedrock classes added: {len(replacements.keys() - original_names)}")
        print("Verified: no duplicates; original manifest, launcher agent and other entries preserved")
        print("Isolated test artifact only; release pins and installed gateway unchanged")
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
