import argparse
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '13.1.5'
HASHES = {
    'api': '4def9bde498ef8626614c2fc5db9af4749c86f16f6c33e3f5658d35e70bab59b',
    'aidl': '33fe7191cdd69fcb66d649264f3b0c47acb2f3d6343afc05b98dbbff6f221963',
    'provider': 'b0f18cd9812464ec171c53cac93a819fe411718a3965c311f01eb4de265381b3',
}


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def required(path):
    path = Path(path).expanduser().resolve()
    if not path.is_file():
        raise RuntimeError('Required file not found: ' + str(path))
    return path


def dependencies():
    cache = ROOT / '.local' / 'dependencies'
    cache.mkdir(parents=True, exist_ok=True)
    jars = []
    for module, expected in HASHES.items():
        archive = cache / (module + '-' + VERSION + '.aar')
        if not archive.is_file():
            url = ('https://repo.maven.apache.org/maven2/dev/rikka/shizuku/'
                   + module + '/' + VERSION + '/' + module + '-' + VERSION + '.aar')
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError('Dependency checksum mismatch: ' + module)
            archive.write_bytes(data)
        data = archive.read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('Cached dependency checksum mismatch: ' + module)
        target = cache / (module + '-' + VERSION + '.jar')
        with zipfile.ZipFile(io.BytesIO(data)) as source:
            target.write_bytes(source.read('classes.jar'))
        jars.append(target)
    return jars


def jar_classes(target, classes):
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as output:
        for item in sorted(classes.rglob('*.class')):
            output.write(item, item.relative_to(classes).as_posix())


def main():
    parser = argparse.ArgumentParser(description='Build the vivo car launcher v15 without Gradle')
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME'))
    parser.add_argument('--build-tools')
    parser.add_argument('--android-jar')
    parser.add_argument('--framework-aidl')
    args = parser.parse_args()
    sdk = Path(args.sdk).expanduser() if args.sdk else None
    if not args.build_tools and sdk is None:
        parser.error('Set ANDROID_SDK_ROOT or pass --sdk / --build-tools')
    tools = Path(args.build_tools).expanduser() if args.build_tools else sdk / 'build-tools' / '34.0.0'
    if not args.android_jar and sdk is None:
        parser.error('Pass --android-jar when using a custom Build Tools path')
    android = required(args.android_jar or sdk / 'platforms' / 'android-34' / 'android.jar')
    framework = required(args.framework_aidl or android.parent / 'framework.aidl')
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        os.environ['PATH'] = str(Path(java_home) / 'bin') + os.pathsep + os.environ.get('PATH', '')
    javac = shutil.which('javac')
    if not javac:
        raise RuntimeError('JDK 17 is required; set JAVA_HOME')
    for name in ['aapt2', 'aidl', 'd8']:
        required(tools / name)
    build = ROOT / 'build'
    build.mkdir(exist_ok=True)
    work = build / 'intermediate'
    if work.exists():
        shutil.rmtree(work)
    classes = work / 'classes'
    generated = work / 'generated'
    dex = work / 'dex'
    bridge_classes = work / 'bridge-classes'
    bridge_dex = work / 'bridge-dex'
    for directory in [classes, generated, dex, bridge_classes, bridge_dex]:
        directory.mkdir(parents=True)
    libs = dependencies()
    src = ROOT / 'app' / 'src'
    interface = src / 'top/quietphoenix/carstarter/ICarService.aidl'
    run(tools / 'aidl', '-I' + str(src), '-p' + str(framework), '-o' + str(generated), interface)
    sources = sorted(src.rglob('*.java')) + sorted(generated.rglob('*.java'))
    sources.append(ROOT / 'daemon/top/quietphoenix/card/CarD.java')
    classpath = os.pathsep.join(str(item) for item in [android] + libs)
    run(javac, '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-classpath', classpath,
        '-d', classes, *sources)
    app_jar = work / 'app-classes.jar'
    jar_classes(app_jar, classes)
    run(tools / 'd8', '--release', '--lib', android, '--min-api', '29', '--output', dex, app_jar, *libs)
    apk = build / 'CarStarter-v15-unsigned.apk'
    run(tools / 'aapt2', 'link', '-I', android, '--manifest', ROOT / 'app/AndroidManifest.xml',
        '-o', apk, '--min-sdk-version', '29', '--target-sdk-version', '34',
        '--version-code', '15', '--version-name', '1.5')
    with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as output:
        output.write(dex / 'classes.dex', 'classes.dex')
    run(javac, '-encoding', 'UTF-8', '--release', '11', '-classpath', android,
        '-d', bridge_classes, ROOT / 'daemon/CarSessionBridge.java')
    bridge_jar = work / 'bridge-classes.jar'
    jar_classes(bridge_jar, bridge_classes)
    run(tools / 'd8', '--release', '--lib', android, '--min-api', '29', '--output', bridge_dex, bridge_jar)
    with zipfile.ZipFile(build / 'car-bridge.jar', 'w', zipfile.ZIP_DEFLATED) as output:
        output.write(bridge_dex / 'classes.dex', 'classes.dex')
    print('Built unsigned APK: ' + str(apk))
    print('Built bridge: ' + str(build / 'car-bridge.jar'))


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, subprocess.CalledProcessError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
