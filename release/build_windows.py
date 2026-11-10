"""Build a portable Windows EXE image with a verified, private Java + JavaFX runtime."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

SOURCE = Path(__file__).resolve().parents[1]
RUNTIME = {
    'version': '21.0.13.0.3+5',
    'name': 'bellsoft-jre21.0.13.0.3+5-windows-amd64-full.zip',
    'url': 'https://github.com/bell-sw/Liberica/releases/download/21.0.13.0.3%2B5/bellsoft-jre21.0.13.0.3%2B5-windows-amd64-full.zip',
    'sha256': '00162ae246569f2ccccd12c464133e6cca95234e499542a5d29274d519725d31',
    'provider': 'BellSoft Liberica JRE Full (OpenJDK + LibericaFX)',
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def build(args):
    if os.name != 'nt':
        raise RuntimeError('Build the Windows application image on Windows')
    jpackage = args.jdk / 'bin/jpackage.exe'
    if not jpackage.is_file() or not args.jar.is_file():
        raise RuntimeError('Supply an existing JDK 21 and the compiled launcher JAR')
    target = args.output / 'Allegiance-HMCL'
    if target.exists():
        raise RuntimeError('Output already exists; refusing to overwrite launcher or player data')
    args.work.mkdir(parents=True, exist_ok=True)
    runtime = args.runtime_image
    if runtime is None:
        archive = args.runtime_archive or args.work / RUNTIME['name']
        if not archive.exists():
            request = urllib.request.Request(RUNTIME['url'], headers={'User-Agent': 'Allegiance-runtime-packager'})
            with urllib.request.urlopen(request, timeout=180) as response, archive.open('xb') as out:
                shutil.copyfileobj(response, out)
        if digest(archive) != RUNTIME['sha256']:
            raise RuntimeError('Bundled runtime SHA256 does not match its pinned official asset')
        runtime_parent = args.work / 'runtime'
        if not runtime_parent.exists():
            runtime_parent.mkdir()
            with zipfile.ZipFile(archive) as z:
                for name in z.namelist():
                    if not (runtime_parent / name).resolve().is_relative_to(runtime_parent.resolve()):
                        raise RuntimeError('Invalid runtime archive path')
                z.extractall(runtime_parent)
        runtime = next(p for p in runtime_parent.iterdir() if (p / 'bin/java.exe').is_file())
    listed = subprocess.check_output([str(runtime / 'bin/java.exe'), '--list-modules'], text=True)
    modules = {line.split('@')[0] for line in listed.splitlines()}
    required = {'javafx.controls', 'javafx.fxml', 'javafx.media', 'javafx.swing', 'javafx.web', 'jdk.crypto.mscapi'}
    if not required.issubset(modules):
        raise RuntimeError('Bundled runtime is missing required JavaFX or Windows certificate modules')
    input_dir = args.work / 'input'
    input_dir.mkdir(exist_ok=True)
    shutil.copyfile(args.jar, input_dir / 'Allegiance-HMCL.jar')
    options = [
        '--add-modules=' + ','.join(sorted(required)),
        '-Duser.dir=$APPDIR/..',
        '-Dhmcl.home=$APPDIR/../.hmcl/user',
        '-Dhmcl.curseforge.source=auto',
    ]
    with zipfile.ZipFile(args.jar) as z:
        properties = z.read('assets/hmcl.properties').decode('utf-8')
        opens = next(line.split('=', 1)[1] for line in properties.splitlines() if line.startswith('hmcl.add-opens='))
        for entry in opens.split():
            if entry.split('/')[0] in modules:
                options += ['--add-opens=' + entry + '=ALL-UNNAMED', '--add-exports=' + entry + '=ALL-UNNAMED']
    command = [str(jpackage), '--type', 'app-image', '--name', 'Allegiance-HMCL',
               '--dest', str(args.output), '--input', str(input_dir), '--main-jar', 'Allegiance-HMCL.jar',
               '--main-class', 'org.jackhuang.hmcl.Main', '--runtime-image', str(runtime),
               '--app-version', '3.17.1110', '--vendor', 'Allegiance Community',
               '--icon', str(SOURCE / 'release/branding/launcher.ico'),
               '--description', 'Unofficial HMCL launcher with Allegiance client sync']
    for option in options:
        command += ['--java-options', option]
    subprocess.run(command, check=True)
    config = target / 'app/Allegiance-HMCL.cfg'
    with config.open('a', encoding='utf-8') as stream:
        stream.write('\n# Optional window title. Remove the leading # to enable, then restart.\n'
                     '# java-options=-Dhmcl.window.title=DecreasedCraft Allegiance\n')
    shutil.copyfile(SOURCE / 'LICENSE', target / 'LICENSE')
    shutil.copyfile(SOURCE / 'release/DELIVERY.md', target / '使用说明.md')
    shutil.copyfile(SOURCE / 'release/TITLE-BAR.md', target / '修改标题栏说明.md')
    (target / '样式素材').mkdir()
    info = {'version': args.version, 'runtime': RUNTIME, 'launcher_jar_sha256': digest(args.jar),
            'architecture': 'windows-x64', 'entry': 'Allegiance-HMCL.exe', 'bundled_javafx': True}
    (target / '交付信息.json').write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding='utf-8')
    print('PORTABLE_IMAGE_READY', target)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jdk', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--runtime-archive', type=Path)
    parser.add_argument('--runtime-image', type=Path, help='Reuse the verified runtime from an earlier delivery')
    parser.add_argument('--version', default='maint-20261110.4')
    build(parser.parse_args())
