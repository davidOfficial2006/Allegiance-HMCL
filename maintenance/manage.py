"""Build, archive, record acceptance and promote HMCL maintenance releases. Python stdlib only."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

SOURCE = Path(__file__).resolve().parents[1]
HMCL = SOURCE.parents[1]
RELEASES = HMCL / "维护版本"
APP = HMCL / "hmcl应用版"
CHECKS = {
    "fresh_import": "用全新导入的客户端副本测试",
    "dependency_completion": "完成 Java、游戏和整合包依赖准备",
    "sync_patch": "通过正式接口同步测试补丁并核对备份",
    "launch_and_join": "实际启动游戏并成功进服",
    "single_mod_restore": "实际使用单 Mod 回档菜单并核对文件",
}
TESTS = ["org.jackhuang.hmcl.sync.AllegianceSyncTest", "org.jackhuang.hmcl.addon.repository.CurseForge*Test",
         "org.jackhuang.hmcl.launch.DefaultLauncherRuntimeTest"]


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else hashlib.sha256(stream.read()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write(path, value):
    path = Path(path)
    temp = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def git(*args):
    return subprocess.check_output(["git", "-c", "gc.auto=0", *args], cwd=SOURCE, encoding="utf-8").strip()


def release(version):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,70}", version) or ".." in version:
        raise ValueError("版本名只能使用字母、数字、点、横线和下划线")
    return RELEASES / version


def java_environment():
    env = os.environ.copy()
    if os.name == "nt":
        default = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Java/jdk-21"
        if not env.get("JAVA_HOME") and default.is_dir():
            env["JAVA_HOME"] = str(default)
        # Normal Windows certificate verification; no insecure TLS switch is used.
        options = env.get("JAVA_TOOL_OPTIONS", "")
        for setting in ("-Djava.net.preferIPv4Stack=true", "-Djavax.net.ssl.trustStoreType=Windows-ROOT", "-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp"):
            if setting.split("=")[0] not in options:
                options += " " + setting
        env["JAVA_TOOL_OPTIONS"] = options.strip()
    return env


def run_logged(command, log, env):
    with Path(log).open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=SOURCE, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding="utf-8", errors="replace")
        for line in process.stdout:
            output.write(line)
            output.flush()
            print(line, end="", flush=True)
        if process.wait():
            raise RuntimeError("构建或测试失败，日志已保留；应用版没有替换")


def check_source_archive(path):
    with zipfile.ZipFile(path) as archive:
        required = {"settings.gradle.kts", "maintenance/manage.py", "HMCLCore/src/main/java/org/jackhuang/hmcl/sync/AllegianceSync.java"}
        if not required.issubset(archive.namelist()) or archive.testzip() is not None:
            raise ValueError("源码快照为空、缺少必要源码或校验失败")


def build(version):
    folder = release(version)
    prefix = git("rev-parse", "--show-prefix").rstrip("/")
    if git("status", "--porcelain", "--untracked-files=normal", "--", "."):
        raise ValueError("请先提交 HMCL 源码改动，再构建可追溯版本；无需提交项目其他目录的改动")
    folder.mkdir(parents=True, exist_ok=False)  # Never overwrite an earlier release or failed attempt.
    commit = git("rev-parse", "HEAD")
    provenance = read(SOURCE / "maintenance/upstream.json")
    metadata = {"version": version, "created_at": now(), "source_commit": commit,
                "branch": git("branch", "--show-current"), "source_tree": git("rev-parse", "HEAD:" + prefix),
                "upstream": provenance, "status": "building", "test_selection": TESTS}
    write(folder / "release.json", metadata)
    try:
        env = java_environment()
        env["VERSION_ROOT"] = "3.17-allegiance." + version
        env.pop("BUILD_NUMBER", None)
        command = [str(SOURCE / ("gradlew.bat" if os.name == "nt" else "gradlew")), ":HMCLCore:cleanTest", ":HMCLCore:test"]
        for selection in TESTS:
            command.extend(["--tests", selection])
        command.extend([":HMCL:shadowJar", "--no-daemon", "--console=plain"])
        run_logged([sys.executable, "-m", "unittest", "discover", "-s", "maintenance", "-p", "test_manage.py", "-v"],
                   folder / "script-tests.log", env)
        run_logged(command, folder / "build.log", env)
        # Verify the source didn't change during a long build.
        if commit != git("rev-parse", "HEAD") or git("status", "--porcelain", "--untracked-files=normal", "--", "."):
            raise ValueError("构建期间源码发生变化，本次产物不能进入验收")
        summary = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        reports = list((SOURCE / "HMCLCore/build/test-results/test").glob("TEST-*.xml"))
        if not reports:
            raise ValueError("没有找到本次自动化测试报告")
        for report in reports:
            suite = ET.parse(report).getroot()
            for key in summary:
                summary[key] += int(suite.attrib.get(key, 0))
        if not summary["tests"] or summary["failures"] or summary["errors"] or summary["skipped"]:
            raise ValueError("测试报告存在失败、跳过或空测试，未生成可验收版本")
        original = SOURCE / "HMCL/build/libs" / ("HMCL-" + env["VERSION_ROOT"] + ".SNAPSHOT.jar")
        target = folder / ("Allegiance-HMCL-" + version + ".jar")
        shutil.copyfile(original, target)
        with zipfile.ZipFile(target) as archive:
            for entry in ("org/jackhuang/hmcl/sync/AllegianceSync.class",
                          "org/jackhuang/hmcl/addon/repository/CurseForgeMetadata.class",
                          "org/jackhuang/hmcl/addon/repository/CurseForgeMetadataProbe.class"):
                if entry not in archive.namelist():
                    raise ValueError("构建 JAR 中缺少维护入口：" + entry)
        subprocess.run(["git", "-c", "gc.auto=0", "archive", "--format=zip", "--output=" + str(folder / "source.zip"),
                        commit + ":" + prefix], cwd=git("rev-parse", "--show-toplevel"), check=True)
        check_source_archive(folder / "source.zip")
        with zipfile.ZipFile(folder / "tests.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            for directory in (SOURCE / "HMCLCore/build/test-results/test", SOURCE / "HMCLCore/build/reports/tests/test"):
                for path in directory.rglob("*"):
                    if path.is_file():
                        archive.write(path, path.relative_to(SOURCE).as_posix())
        preview = folder / "preview.cmd"
        preview.write_text('@echo off\nsetlocal\nset "SYNC_JAVA=%ProgramFiles%\\Java\\jdk-21\\bin\\javaw.exe"\n'
                           'if not exist "%SYNC_JAVA%" set "SYNC_JAVA=%JAVA_HOME%\\bin\\javaw.exe"\n'
                           'if not exist "%SYNC_JAVA%" set "SYNC_JAVA=javaw.exe"\n'
                           'set "TEST_DIR=%~dp0..\\..\\maintenance-runs\\' + version + '"\n'
                           'if not exist "%TEST_DIR%" mkdir "%TEST_DIR%"\npushd "%TEST_DIR%"\n'
                           'start "" "%SYNC_JAVA%" -Djavax.net.ssl.trustStoreType=Windows-ROOT '
                           '-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp -Dhmcl.curseforge.source=auto '
                           '-jar "%~dp0' + target.name + '"\npopd\nendlocal\n', encoding="ascii", newline="\r\n")
        artifacts = {p.name: sha(p) for p in (target, folder / "source.zip", folder / "tests.zip", folder / "build.log", folder / "script-tests.log", preview)}
        metadata.update(status="candidate", automatic_tests=summary, artifacts=artifacts,
                        jar=target.name, build_version_root=env["VERSION_ROOT"], maintenance_gate_tests="passed", finished_at=now())
        write(folder / "release.json", metadata)
        (folder / "SHA256SUMS").write_text("".join(digest + "  " + name + "\n" for name, digest in artifacts.items()), encoding="utf-8")
        write(folder / "acceptance.json", {"version": version, "jar_sha256": artifacts[target.name],
              "checks": {key: {"label": label, "status": "pending", "tester": "", "note": "", "tested_at": ""}
                         for key, label in CHECKS.items()}})
        print("\n候选版本已归档：", folder)
        print("应用版没有替换。请完成 acceptance.json 中的五项实际验收后再 promote。")
    except Exception as error:
        metadata.update(status="failed", finished_at=now(), error=type(error).__name__)
        write(folder / "release.json", metadata)
        raise


def verified(folder):
    metadata = read(folder / "release.json")
    if metadata["status"] != "candidate":
        raise ValueError("构建状态不是 candidate，不能验收或切换")
    for name, expected in metadata["artifacts"].items():
        if Path(name).name != name or sha(folder / name) != expected:
            raise ValueError("产物丢失或被修改：" + name)
    if "source.zip" in metadata["artifacts"]:
        check_source_archive(folder / "source.zip")
    return metadata


def record(version, check, status, tester, note):
    folder = release(version)
    metadata = verified(folder)
    if not tester.strip() or not note.strip():
        raise ValueError("必须填写实际测试人和结果说明")
    acceptance = read(folder / "acceptance.json")
    if acceptance["jar_sha256"] != metadata["artifacts"][metadata["jar"]]:
        raise ValueError("验收记录与本次 JAR 不匹配")
    entry = acceptance["checks"][check]
    entry.update(status=status, tester=tester.strip(), note=note.strip(), tested_at=now())
    write(folder / "acceptance.json", acceptance)
    print("已记录：", entry["label"], status)


def promotion_ready(metadata, acceptance):
    if acceptance.get("version") != metadata["version"] or acceptance.get("jar_sha256") != metadata["artifacts"][metadata["jar"]]:
        raise ValueError("验收记录与构建版本不匹配")
    missing = [label for key, label in CHECKS.items() if acceptance.get("checks", {}).get(key, {}).get("status") != "passed"
               or not all(acceptance["checks"][key].get(field) for field in ("tester", "note", "tested_at"))]
    if missing:
        raise ValueError("尚未通过实际验收：" + "、".join(missing))


def promote(version):
    folder = release(version)
    metadata = verified(folder)
    acceptance = read(folder / "acceptance.json")
    promotion_ready(metadata, acceptance)
    APP.mkdir(parents=True, exist_ok=True)
    artifact = APP / metadata["jar"]
    if artifact.exists() and sha(artifact) != metadata["artifacts"][metadata["jar"]]:
        raise ValueError("应用目录存在不同内容的同名 JAR，拒绝覆盖")
    if not artifact.exists():
        shutil.copyfile(folder / metadata["jar"], artifact)
    launcher = APP / "start-local-sync.cmd"
    content = launcher.read_text(encoding="ascii")
    matches = re.findall(r"Allegiance-HMCL-[A-Za-z0-9_.-]+\.jar", content)
    if len(matches) != 1:
        raise ValueError("启动入口格式不符合预期，未修改")
    history = APP / "维护切换记录"
    history.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    shutil.copyfile(launcher, history / (stamp + ".cmd"))
    temp = launcher.with_suffix(".cmd.tmp")
    temp.write_text(content.replace(matches[0], metadata["jar"]), encoding="ascii", newline="\r\n")
    os.replace(temp, launcher)
    receipt = {"version": version, "jar_sha256": metadata["artifacts"][metadata["jar"]],
               "previous_jar": matches[0], "promoted_at": now(), "acceptance": acceptance}
    write(history / (stamp + ".json"), receipt)
    write(APP / "current-maintenance.json", receipt)
    print("应用版已切换：", version, "；旧 JAR 和旧启动入口均保留")


def probe(version, project, file_id):
    folder = release(version)
    metadata = verified(folder)
    env = java_environment()
    java = str(Path(env["JAVA_HOME"]) / "bin/java") if env.get("JAVA_HOME") else "java"
    command = [java, "-Dfile.encoding=UTF-8", "-cp", str(folder / metadata["jar"]),
               "org.jackhuang.hmcl.addon.repository.CurseForgeMetadataProbe", str(project), str(file_id)]
    result = subprocess.run(command, cwd=SOURCE, env=env, capture_output=True, encoding="utf-8", errors="replace", timeout=120)
    path = folder / ("probe-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:6] + ".json")
    try:
        evidence = json.loads(result.stdout) if result.returncode == 0 else {"status": "failed", "error": result.stderr[-6000:]}
    except ValueError:
        evidence = {"status": "failed", "error": "Probe output was not JSON"}
    evidence.update(tested_at=now(), version=version, jar_sha256=metadata["artifacts"][metadata["jar"]])
    write(path, evidence)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    if evidence["status"] != "passed":
        raise RuntimeError("探针失败，结果已记录。应用版没有替换")


def main():
    parser = argparse.ArgumentParser(description="HMCL 维护版本：构建归档、实际验收、切换与回退")
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("build", "promote"):
        sub.add_parser(name).add_argument("version")
    p = sub.add_parser("record")
    p.add_argument("version")
    p.add_argument("check", choices=CHECKS)
    p.add_argument("status", choices=("passed", "failed"))
    p.add_argument("--tester", required=True)
    p.add_argument("--note", required=True)
    p = sub.add_parser("probe")
    p.add_argument("version")
    p.add_argument("--project", type=int, default=1406509)
    p.add_argument("--file", type=int, default=7565972)
    args = parser.parse_args()
    if args.command == "build": build(args.version)
    elif args.command == "promote": promote(args.version)
    elif args.command == "record": record(args.version, args.check, args.status, args.tester, args.note)
    else: probe(args.version, args.project, args.file)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError, KeyError) as error:
        print("未完成：" + str(error), file=sys.stderr)
        sys.exit(1)
