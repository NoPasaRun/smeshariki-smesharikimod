#!/usr/bin/env python3
"""Экспорт мира для раздачи (демо): чистая копия мира в zip, который ставит SmesharikiMod с GitHub или кладут в saves/ руками.

    python3 export_world.py "Ромашковая Долина" [--out файл.zip] [--name "Имя мира в архиве"] [--game-dir ~/.minecraft] [--keep-progress]

Что НЕ попадает в архив: история версий (managementmod_versions, buildmod_vcs: гигабайты), session.lock, level.dat_old, данные игроков
(players/, playerdata/, advancements/, stats/: новый игрок начнёт с нуля), прогоны и статистика уровней (data/levelmod/levels.dat и копии: вернуть
флагом --keep-progress), прежний ghosts.dat. Всё остальное (блоки, сущности, смешарики, платформы и стены, кат-сцены, маршруты брёвен) идёт как есть.

Что ДОБАВЛЯЕТСЯ: levelmod_places.json в корне мира — локации и группы из <папка игры>/data/levelmod/worlds/<мир>/places.json (вне мира они живут
в файле, а у получателя папка мира может называться иначе); LevelMod подхватывает его при первом открытии мира.

Игра должна быть закрыта (файлы региона читаются как есть). Мир не изменяется, архив пишется отдельно.
"""
import argparse
import os
import sys
import zipfile

SKIP_DIRS = {"managementmod_versions", "buildmod_vcs", "players", "playerdata", "advancements", "stats"}
SKIP_FILES = {"session.lock", "level.dat_old", "uid.dat"}
LEVELMOD_PROGRESS = {"levels.dat", "levels.dat.pre_places.bak", "ghosts.dat"}


def skipped(rel, keep_progress):
    parts = rel.split("/")
    if parts[0] in SKIP_DIRS:
        return True
    if parts[-1] in SKIP_FILES or parts[-1].endswith(".lock"):
        return True
    if not keep_progress and parts[:2] == ["data", "levelmod"] and len(parts) == 3 and parts[2] in LEVELMOD_PROGRESS:
        return True
    return False


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("world", help="имя папки в saves/ или путь к папке мира")
    ap.add_argument("--out", help="файл zip (по умолчанию <папка игры>/data/smesharikimod/exports/<мир>.zip)")
    ap.add_argument("--name", help="имя папки мира внутри архива (по умолчанию как у исходного)")
    ap.add_argument("--game-dir", default=os.path.expanduser("~/.minecraft"))
    ap.add_argument("--keep-progress", action="store_true", help="оставить прогоны и статистику уровней")
    args = ap.parse_args()

    game = os.path.abspath(os.path.expanduser(args.game_dir))
    world = args.world if os.path.isdir(args.world) else os.path.join(game, "saves", args.world)
    world = os.path.abspath(world)
    if not os.path.isfile(os.path.join(world, "level.dat")):
        sys.exit("не мир (нет level.dat): " + world)
    folder = os.path.basename(world)
    name = args.name or folder
    if any(c in name for c in '/\\:*?"<>|') or name.startswith("."):
        sys.exit("недопустимое имя мира: " + name)
    out = args.out or os.path.join(game, "data", "smesharikimod", "exports", name + ".zip")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    if os.path.exists(os.path.join(world, "session.lock")):
        # lock есть всегда, пока мир открыт или после сбоя; открытую игру отличить нельзя — предупреждаем
        print("session.lock есть (остаётся и после закрытия игры); в архив не идёт. Игра должна быть закрыта.")

    places = os.path.join(game, "data", "levelmod", "worlds", folder, "places.json")
    total = packed = 0
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for root, dirs, files in os.walk(world):
            dirs.sort()
            for f in sorted(files):
                path = os.path.join(root, f)
                rel = os.path.relpath(path, world).replace(os.sep, "/")
                if skipped(rel, args.keep_progress):
                    continue
                z.write(path, name + "/" + rel)
                total += os.path.getsize(path)
        if os.path.isfile(places):
            z.write(places, name + "/levelmod_places.json")
            print("локации и группы добавлены: levelmod_places.json")
        else:
            print("ВНИМАНИЕ: нет файла локаций " + places + " — в чужом мире уровень не загрузится (локации не отмечены)")
    packed = os.path.getsize(out)
    print("готово: %s\n  мир «%s»: %.1f МБ -> архив %.1f МБ" % (out, name, total / 1048576, packed / 1048576))


if __name__ == "__main__":
    main()
