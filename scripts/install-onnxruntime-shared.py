"""Install ONNX Runtime Java bindings that share sherpa-onnx's 1.28.2 native library.

The official 1.28.0 Android AAR supplies the Java classes and JNI bridge. Its
JNI bridge references three symbols versioned VERS_1.28.0. sherpa-onnx 1.13.8
ships those symbols as VERS_1.28.2. Patch only that version requirement and
omit the official runtime library, leaving sherpa's runtime as the sole copy.
"""

import argparse
import hashlib
import shutil
import tempfile
import urllib.request
import zipfile
from pathlib import Path

import lief


VERSION = "1.28.0-kiora-ort1.28.2"
ORT_SHA256 = "f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9"
SHERPA_SHA256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
ORT_URL = (
    "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/"
    "onnxruntime-android/1.28.0/onnxruntime-android-1.28.0.aar"
)
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
OLD_VERSION = b"VERS_1.28.0"
NEW_VERSION = b"VERS_1.28.2"


def elf_hash(name: str) -> int:
    value = 0
    for byte in name.encode("ascii"):
        value = (value << 4) + byte
        high = value & 0xF0000000
        if high:
            value ^= high >> 24
        value &= ~high
    return value


def patch_bridge(data: bytes, abi: str, temporary: Path) -> bytes:
    if data.count(OLD_VERSION) != 1 or NEW_VERSION in data:
        raise ValueError(f"Unexpected JNI bridge version: {abi}")
    if data.count(b"libonnxruntime.so") != 1:
        raise ValueError(f"Unexpected JNI bridge dependency: {abi}")
    source = temporary / f"{abi}-input.so"
    target = temporary / f"{abi}-output.so"
    source.write_bytes(data)
    binary = lief.parse(str(source))
    if binary is None:
        raise ValueError(f"Cannot parse JNI bridge: {abi}")
    requirements = [item for item in binary.symbols_version_requirement
                    if item.name == "libonnxruntime.so"]
    if len(requirements) != 1:
        raise ValueError(f"Unexpected ONNX Runtime version requirements: {abi}")
    auxiliaries = requirements[0].get_auxiliary_symbols()
    if len(auxiliaries) != 1 or auxiliaries[0].name != OLD_VERSION.decode("ascii"):
        raise ValueError(f"Unexpected JNI symbol version: {abi}")
    auxiliary = auxiliaries[0]
    if auxiliary.hash != elf_hash(auxiliary.name):
        raise ValueError(f"Unexpected JNI version hash: {abi}")
    auxiliary.name = NEW_VERSION.decode("ascii")
    auxiliary.hash = elf_hash(auxiliary.name)
    binary.write(str(target))
    patched = target.read_bytes()
    if OLD_VERSION in patched or patched.count(NEW_VERSION) != 1:
        raise ValueError(f"JNI bridge patch failed: {abi}")
    return patched


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Official onnxruntime-android 1.28.0 AAR")
    parser.add_argument("--sherpa-source", type=Path, help="Official sherpa-onnx 1.13.8 AAR")
    parser.add_argument("--repository", type=Path,
                        default=Path(__file__).resolve().parents[2] / "artifacts" / "maven")
    args = parser.parse_args()
    sherpa_source = args.sherpa_source or (
        args.repository / "com" / "k2fsa" / "sherpa" / "onnx" /
        "sherpa-onnx-android" / "1.13.8" / "sherpa-onnx-android-1.13.8.aar"
    )
    if sha256(sherpa_source) != SHERPA_SHA256:
        raise ValueError("sherpa-onnx AAR SHA-256 mismatch")

    artifact_dir = (args.repository / "com" / "microsoft" / "onnxruntime" /
                    "onnxruntime-android" / VERSION)
    target = artifact_dir / f"onnxruntime-android-{VERSION}.aar"
    pom = artifact_dir / f"onnxruntime-android-{VERSION}.pom"
    artifact_dir.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="kiora-ort-shared-") as temp_name:
        temporary = Path(temp_name)
        source = args.source or temporary / "official.aar"
        if args.source is None:
            urllib.request.urlretrieve(ORT_URL, source)
        if sha256(source) != ORT_SHA256:
            raise ValueError("Official ONNX Runtime AAR SHA-256 mismatch")

        generated = temporary / "shared.aar"
        with zipfile.ZipFile(source) as original, zipfile.ZipFile(sherpa_source) as sherpa, \
                zipfile.ZipFile(generated, "w") as output:
            for abi in ABIS:
                runtime_name = f"jni/{abi}/libonnxruntime.so"
                bridge_name = f"jni/{abi}/libonnxruntime4j_jni.so"
                if NEW_VERSION not in sherpa.read(runtime_name):
                    raise ValueError(f"sherpa runtime version mismatch: {abi}")
                patch_bridge(original.read(bridge_name), abi, temporary)

            for info in original.infolist():
                parts = info.filename.split("/")
                if len(parts) == 3 and parts[0] == "jni" and parts[2] == "libonnxruntime.so":
                    continue
                data = original.read(info.filename)
                if len(parts) == 3 and parts[0] == "jni" and parts[2] == "libonnxruntime4j_jni.so":
                    data = patch_bridge(data, parts[1], temporary)
                output.writestr(info, data)

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
    print(f"Installed shared ONNX Runtime bindings for {', '.join(ABIS)} into {artifact_dir}")


if __name__ == "__main__":
    main()
