package com.smeshariki.mod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Данные модов внутри мира лежат в одном месте: {@code <мир>/data/<id мода>/…} (рядом с ванильным {@code data/minecraft}), а не россыпью в корне мира.
 * {@link #path} возвращает такой путь и один раз переносит туда прежнее место (файл или папку в корне мира): переименование на одной файловой системе.
 * Не вышло перенести — работает по старому месту, чтобы ничего не потерять.
 */
public final class WorldData {
	private WorldData() {
	}

	/** Корень мира. */
	public static Path root(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT);
	}

	/** {@code <мир>/data/<mod>/<name>}; если его нет, а есть прежнее {@code <мир>/<legacy>}, прежнее переезжает. */
	public static Path path(MinecraftServer server, String mod, String name, String... legacy) {
		return path(root(server), mod, name, legacy);
	}

	public static Path path(Path worldRoot, String mod, String name, String... legacy) {
		Path now = worldRoot.resolve("data").resolve(mod).resolve(name);
		if (Files.exists(now)) return now;
		for (String old : legacy) {
			Path from = worldRoot.resolve(old);
			if (!Files.exists(from)) continue;
			try {
				Files.createDirectories(now.getParent());
				try {
					Files.move(from, now, StandardCopyOption.ATOMIC_MOVE);
				} catch (java.nio.file.AtomicMoveNotSupportedException e) {
					Files.move(from, now);
				}
				SmesharikiMod.LOGGER.info("Данные мира перенесены: {} -> data/{}/{}", old, mod, name);
				return now;
			} catch (IOException | RuntimeException e) {
				SmesharikiMod.LOGGER.warn("Не удалось перенести {} в data/{}/{}, работаю по старому месту", from, mod, name, e);
				return from;
			}
		}
		return now;
	}
}
