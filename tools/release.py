#!/usr/bin/env python3
"""Сборка релиза для установки с GitHub (ManagementMod, настройка gitRepo в config/managementmod.json) и, по желанию, его публикация.

    python3 release.py --tag v0.1.0 --world-zip мир.zip [--shaderpack файл.zip] [--out dist] [--game-dir ~/.minecraft]
    GITHUB_TOKEN=… python3 release.py --tag v0.1.0 --world-zip мир.zip --repo владелец/имя --upload

Без --upload только собирает папку dist/: manifest.json (имя, категория, размер, SHA-256) и сами файлы — их можно загрузить в релиз вручную на сайте.
С --upload создаёт релиз в репозитории и загружает все файлы (токен GitHub с правом записи в репозиторий берётся из GITHUB_TOKEN, нигде не сохраняется);
перед отправкой спрашивает подтверждение. Мир получают инструментом export_world.py.

В релиз идут: семь модов (uilib, gameplaymod, managementmod, worldmod, behaviormod, buildmod, levelmod) из <папка игры>/mods, шейдерпак и мир.
Fabric API, Sodium и Iris не включаются: игрок ставит их сам (у Fabric API имя файла содержит «+», которое GitHub переписывает, а второй экземпляр рядом
с уже стоящим сломал бы запуск). Имена файлов релиза — только буквы, цифры, «.», «_», «-»: иначе GitHub переименует ресурс и манифест перестанет совпадать.
"""
import argparse
import glob
import hashlib
import json
import os
import re
import shutil
import sys
import urllib.request
import zipfile

# прежние имена модов: если лежат в mods рядом с новыми, игра не запускается; установщик отключает их (переименует в .managementmod-old)
OBSOLETE = ["smesharikiworld-1.0.0.jar", "installermod-1.0.0.jar"]
MODS = ["uilib", "gameplaymod", "managementmod", "worldmod", "behaviormod", "buildmod", "levelmod"]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--world-zip", help="архив мира от export_world.py")
    ap.add_argument("--shaderpack", help="zip шейдерпака (необязательно)")
    ap.add_argument("--out", default="dist")
    ap.add_argument("--game-dir", default=os.path.expanduser("~/.minecraft"))
    ap.add_argument("--repo", help="владелец/имя (для --upload)")
    ap.add_argument("--upload", action="store_true")
    ap.add_argument("--notes", default="", help="описание релиза")
    args = ap.parse_args()

    mods_dir = os.path.join(os.path.abspath(os.path.expanduser(args.game_dir)), "mods")
    out = os.path.abspath(args.out)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    files = []

    def add(src, category, name=None, **extra):
        name = name or os.path.basename(src)
        if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
            sys.exit("имя файла для релиза недопустимо (GitHub его переименует): " + name)
        shutil.copy2(src, os.path.join(out, name))
        entry = {"name": name, "category": category, "size": os.path.getsize(src), "sha256": sha256(src)}
        entry.update(extra)
        files.append(entry)
        print("  %-8s %-40s %8.1f МБ" % (category, name, entry["size"] / 1048576))

    for mod in MODS:
        path = os.path.join(mods_dir, mod + "-1.0.0.jar")
        if not os.path.isfile(path):
            sys.exit("нет " + path + " (соберите моды и положите jar в mods)")
        add(path, "mod")
    if args.shaderpack:
        add(args.shaderpack, "shaderpack")
    if args.world_zip:
        with zipfile.ZipFile(args.world_zip) as z:
            tops = {n.split("/")[0] for n in z.namelist() if "/" in n}
        if len(tops) != 1:
            sys.exit("в архиве мира должна быть одна папка, найдено: %s" % sorted(tops))
        add(args.world_zip, "world", name="world.zip", world=tops.pop())
    with open(os.path.join(out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump({"tag": args.tag, "files": files, "obsolete": OBSOLETE}, f, ensure_ascii=False, indent=2)
    print("собрано: %s (%d файлов + manifest.json)" % (out, len(files)))
    if not args.upload:
        print("не опубликовано: загрузите содержимое dist/ в релиз %s вручную или запустите с --upload" % args.tag)
        return

    token = os.environ.get("GITHUB_TOKEN")
    if not token or not args.repo:
        sys.exit("для --upload нужны --repo и переменная GITHUB_TOKEN")
    print("Будет создан релиз %s в %s и загружено %d файлов (%.0f МБ). Продолжить? [y/N] " % (args.tag, args.repo, len(files) + 1, sum(e["size"] for e in files) / 1048576), end="")
    if input().strip().lower() != "y":
        sys.exit("отменено")

    def api(url, data=None, ctype="application/json", method=None):
        req = urllib.request.Request(url, data=data, method=method or ("POST" if data is not None else "GET"))
        req.add_header("Authorization", "Bearer " + token)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("User-Agent", "smeshariki-release")
        if data is not None:
            req.add_header("Content-Type", ctype)
        with urllib.request.urlopen(req, timeout=1800) as r:
            return json.load(r) if r.status != 204 else {}

    base = "https://api.github.com/repos/%s" % args.repo
    try:
        release = api("%s/releases/tags/%s" % (base, args.tag))
        print("релиз %s уже есть: докачиваю недостающее" % args.tag)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        release = api(base + "/releases", json.dumps({"tag_name": args.tag, "name": args.tag, "body": args.notes, "draft": False, "prerelease": False}).encode())
    have = {a["name"]: a for a in release.get("assets", [])}

    def curl_upload(name, path):
        """Загрузка через curl (повторы при обрыве); токен передаётся через stdin, а не в аргументах."""
        import subprocess
        url = "https://uploads.github.com/repos/%s/releases/%d/assets?name=%s" % (args.repo, release["id"], name)
        cfg = 'header = "Authorization: Bearer %s"\nheader = "Accept: application/vnd.github+json"\nheader = "Content-Type: application/octet-stream"\n' % token
        for attempt in range(1, 5):
            r = subprocess.run(["curl", "-sS", "--fail-with-body", "-X", "POST", "-K", "-", "--data-binary", "@" + path, "--max-time", "3600", url], input=cfg, text=True, capture_output=True)
            if r.returncode == 0:
                return
            print("  попытка %d не удалась: %s" % (attempt, (r.stderr or r.stdout).strip()[:200]), flush=True)
        sys.exit("не удалось загрузить " + name)

    for name in [f["name"] for f in files] + ["manifest.json"]:
        path = os.path.join(out, name)
        size = os.path.getsize(path)
        if name in have:
            if have[name]["size"] == size and have[name]["state"] == "uploaded":
                print("уже загружено:", name, flush=True)
                continue
            api("%s/releases/assets/%d" % (base, have[name]["id"]), method="DELETE")
        curl_upload(name, path)
        print("загружено:", name, flush=True)
    # проверка: имена ресурсов на GitHub совпали с манифестом (GitHub мог переименовать)
    uploaded = {a["name"] for a in api("%s/releases/%d" % (base, release["id"]))["assets"]}
    missing = [f["name"] for f in files if f["name"] not in uploaded]
    if missing:
        sys.exit("ресурсы переименованы GitHub, манифест не совпадёт: %s" % missing)
    print("готово:", release["html_url"])


if __name__ == "__main__":
    main()
