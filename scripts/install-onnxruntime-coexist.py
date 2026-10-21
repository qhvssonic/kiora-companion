"""Install an ONNX Runtime 1.21.1 AAR whose native library can coexist with sherpa-onnx.

Requires `pip install lief`. Only the ELF SONAME and JNI DT_NEEDED name are changed;
the official Java classes and inference code are retained.
"""

import argparse
import copy
import hashlib
import shutil
import tempfile
import urllib.request
import zipfile
from pathlib import Path

import lief


VERSION = "1.21.1-kiora-coexist"
ORIGINAL_SHA256 = "30e594a4b9246fe3ca25768570e90f71e6d33ceb7b7dd72f92dcd7c267611d3f"
SOURCE_URL = (
    "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/"
    "onnxruntime-android/1.21.1/onnxruntime-android-1.21.1.aar"
)
RENAMED_LIBRARY = "libkiora_onnxruntime_paddle.so"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def patch_elf(data: bytes, name: str, temporary: Path) -> bytes:
    source = temporary / "input.so"
    target = temporary / "output.so"
    source.write_bytes(data)
    binary = lief.parse(str(source))
    if binary is None:
        raise ValueError(f"Cannot parse {name}")
    if name == "libonnxruntime.so":
        entry = next(e for e in binary.dynamic_entries if e.tag == lief.ELF.DynamicEntry.TAG.SONAME)
        if entry.name != "libonnxruntime.so":
            raise ValueError(f"Unexpected SONAME in {name}: {entry.name}")
    else:
        entry = next(e for e in binary.dynamic_entries
                     if e.tag == lief.ELF.DynamicEntry.TAG.NEEDED and e.name == "libonnxruntime.so")
        requirement = next(r for r in binary.symbols_version_requirement
                           if r.name == "libonnxruntime.so")
        requirement.name = RENAMED_LIBRARY
    entry.name = RENAMED_LIBRARY
    binary.write(str(target))
    return target.read_bytes()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Official onnxruntime-android 1.21.1 AAR")
    parser.add_argument("--repository", type=Path,
                        default=Path(__file__).resolve().parents[2] / "artifacts" / "maven")
    args = parser.parse_args()
    artifact_dir = args.repository / "com" / "microsoft" / "onnxruntime" / "onnxruntime-android" / VERSION
    target = artifact_dir / f"onnxruntime-android-{VERSION}.aar"
    pom = artifact_dir / f"onnxruntime-android-{VERSION}.pom"
    artifact_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="kiora-ort-") as temporary_name:
        temporary = Path(temporary_name)
        source = args.source or temporary / "official.aar"
        if args.source is None:
            urllib.request.urlretrieve(SOURCE_URL, source)
        if sha256(source) != ORIGINAL_SHA256:
            raise ValueError("Official ONNX Runtime AAR SHA-256 mismatch")
        generated = temporary / "coexist.aar"
        patched = set()
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(generated, "w") as output:
            for info in original.infolist():
                data = original.read(info.filename)
                parts = info.filename.split("/")
                if len(parts) == 3 and parts[0] == "jni" and parts[2] in (
                        "libonnxruntime.so", "libonnxruntime4j_jni.so"):
                    data = patch_elf(data, parts[2], temporary)
                    patched.add((parts[1], parts[2]))
                    info = copy.copy(info)
                    if parts[2] == "libonnxruntime.so":
                        info.filename = f"jni/{parts[1]}/{RENAMED_LIBRARY}"
                output.writestr(info, data)
        abis = {abi for abi, _ in patched}
        if not abis or patched != {(abi, library) for abi in abis for library in
                                   ("libonnxruntime.so", "libonnxruntime4j_jni.so")}:
            raise ValueError("Official AAR is missing a native library for an ABI")
        if target.exists():
            if sha256(target) != sha256(generated):
                raise ValueError(f"Different artifact already exists: {target}")
        else:
            pending = target.with_name(target.name + ".pending")
            shutil.copyfile(generated, pending)
            pending.replace(target)

    pom_text = ("<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
                "<modelVersion>4.0.0</modelVersion>"
                "<groupId>com.microsoft.onnxruntime</groupId>"
                "<artifactId>onnxruntime-android</artifactId>"
                f"<version>{VERSION}</version><packaging>aar</packaging></project>\n")
    if pom.exists() and pom.read_text(encoding="utf-8") != pom_text:
        raise ValueError(f"Different POM already exists: {pom}")
    pom.write_text(pom_text, encoding="utf-8")
    print(f"Installed {VERSION} for {', '.join(sorted(abis))} into {artifact_dir}")


if __name__ == "__main__":
    main()
