package com.smeshariki.mod.versions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Программный доступ к версиям мира для других модов. Формат хранения на диске остаётся делом этого мода: тот, кому нужна текущая версия (например,
 * возврат блоков области), читает её здесь, а не разбирает файлы истории сам.
 */
public final class WorldVersions {
	private WorldVersions() {
	}

	/**
	 * Файл или папка в корне мира, которые не входят в снимки версий (общие для всех версий данные мода: откат версии их не трогает и не стирает).
	 * Звать при загрузке мода.
	 */
	public static void skipTopLevel(String name) {
		VcsServer.SKIP_TOP.add(name);
	}

	/**
	 * Добавить в версии путь (шаблон от корня мира через {@code /}, {@code *} — любое имя сегмента; входит и сам путь, и всё внутри). Версии хранят <b>только</b> состояние мира:
	 * блоки и сущности входят всегда, остальное — только объявленное так. Моды объявляют свои файлы, привязанные к блокам и сущностям (платформы, стены), а настройки,
	 * данные игроков и уровней не объявляют: те сохраняются постоянно и откатом не трогаются. Звать при загрузке мода.
	 */
	public static void keep(String pattern) {
		VcsServer.KEEP.add(pattern.split("/"));
	}

	/**
	 * Живой откат: возврат мира к текущей версии без остановки сервера (блоки и сущности по одному чанку за тик). Движок есть у LevelMod (он же откатывает мир после
	 * уровней); без него откат версии из окна V останавливает мир и заменяет файлы.
	 */
	public interface LiveRollback {
		/**
		 * Начать возврат мира к текущей версии (указатель уже передвинут на нужную). Идёт на потоке сервера понемногу каждый тик.
		 *
		 * @param progress звать при смене процента, 0…99
		 * @param finished звать один раз в конце: {@code null} — мир возвращён, иначе причина сбоя
		 * @return причина, по которой начать нельзя (тогда ничего не запущено и {@code finished} не будет вызван), или {@code null}
		 */
		@Nullable String begin(net.minecraft.server.MinecraftServer server, java.util.function.IntConsumer progress, java.util.function.Consumer<@Nullable String> finished);
	}

	private static volatile LiveRollback liveRollback;

	/** Подключить движок живого отката. Звать при загрузке мода. */
	public static void liveRollback(LiveRollback handler) {
		liveRollback = handler;
	}

	static @Nullable LiveRollback liveRollback() {
		return liveRollback;
	}

	/**
	 * Файлы текущей версии мира: путь от корня мира (через {@code /}) → хеш содержимого; {@code null} — версий ещё нет. Читает с диска, сервер для этого
	 * не нужен; чтение вызывать не на потоке игры, если версия большая.
	 */
	public static @Nullable Map<String, String> currentFiles(Path worldRoot) throws IOException {
		Path vcs = vcsDir(worldRoot);
		Path headFile = vcs.resolve("head.json");
		if (!Files.exists(headFile)) return null;
		int head = JsonParser.parseString(Files.readString(headFile, StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsInt();
		Path commitFile = vcs.resolve("commits").resolve(head + ".json");
		if (head == 0 || !Files.exists(commitFile)) return null;
		JsonObject files = JsonParser.parseString(Files.readString(commitFile, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("files");
		Map<String, String> out = new LinkedHashMap<>();
		for (var entry : files.entrySet()) out.put(entry.getKey(), entry.getValue().getAsJsonObject().get("hash").getAsString());
		return out;
	}

	/** Содержимое файла версии по хешу из {@link #currentFiles}. */
	public static byte[] read(Path worldRoot, String hash) throws IOException {
		return Files.readAllBytes(vcsDir(worldRoot).resolve("objects").resolve(hash));
	}

	/** Папка истории мира; пока мир не открывали с этим модом, история может лежать под прежним именем. */
	private static Path vcsDir(Path worldRoot) {
		Path now = worldRoot.resolve(VcsServer.DIR);
		return Files.isDirectory(now) ? now : worldRoot.resolve(VcsServer.OLD_DIR);
	}
}
