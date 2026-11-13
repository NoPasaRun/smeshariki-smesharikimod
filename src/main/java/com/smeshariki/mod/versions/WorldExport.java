package com.smeshariki.mod.versions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.smeshariki.mod.SmesharikiMod;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Экспорт мира для раздачи: чистая копия мира в zip, который ставит SmesharikiMod с GitHub или кладут в {@code saves/} руками.
 * То же, что делает {@code tools/export_world.py}, но из игры (кнопка «Экспорт мира» в окне версий), поэтому мир перед копированием сохраняется на диск.
 * Архив — {@code <папка игры>/data/smesharikimod/exports/<мир>.zip}, внутри одна папка с именем мира.
 * <p>
 * Не попадает в архив: история версий (гигабайты), {@code session.lock}, {@code level.dat_old}, данные игроков ({@code players/}, {@code playerdata/},
 * {@code advancements/}, {@code stats/}: новый игрок начнёт с нуля), прогоны и статистика уровней ({@code data/levelmod/levels.dat} и копии).
 * Добавляется {@code data/levelmod/places.json} — локации и группы из {@code <папка игры>/data/levelmod/worlds/<мир>/places.json}
 * (вне мира они живут в файле, а у получателя папка мира может называться иначе).
 */
public final class WorldExport {
	private static final Set<String> SKIP_DIRS = Set.of("managementmod_versions", "buildmod_vcs", "players", "playerdata", "advancements", "stats");
	private static final Set<String> SKIP_FILES = Set.of("session.lock", "level.dat_old", "uid.dat");
	private static final Set<String> LEVELMOD_PROGRESS = Set.of("levels.dat", "levels.dat.pre_places.bak", "ghosts.dat");
	private static final String PLACES = "data/levelmod/places.json";

	private WorldExport() {
	}

	/** Итог экспорта: где архив, сколько файлов и байт, были ли добавлены локации. */
	public record Result(Path file, int files, long bytes, boolean places) {
	}

	/** Не входит ли путь (от корня мира, через {@code /}) в архив: файл или папка. */
	static boolean skipped(String rel) {
		String[] parts = rel.split("/");
		if (SKIP_DIRS.contains(parts[0])) return true;
		if (parts.length >= 3 && parts[0].equals("data") && parts[1].equals("smesharikimod") && parts[2].equals("versions")) return true;
		String last = parts[parts.length - 1];
		if (SKIP_FILES.contains(last) || last.endsWith(".lock")) return true;
		return parts.length == 3 && parts[0].equals("data") && parts[1].equals("levelmod") && LEVELMOD_PROGRESS.contains(last);
	}

	/** Имя папки мира годится для архива: без разделителей путей и служебных знаков. */
	static boolean validName(String name) {
		if (name.isEmpty() || name.startsWith(".")) return false;
		for (char c : "/\\:*?\"<>|".toCharArray()) if (name.indexOf(c) >= 0) return false;
		return true;
	}

	/** Запись архива из папки мира (на диске уже всё сохранено). {@code places} — файл локаций или {@code null}. */
	public static Result write(Path world, String name, Path places, Path out) throws IOException {
		if (!Files.isRegularFile(world.resolve("level.dat"))) throw new IOException("не мир (нет level.dat): " + world);
		if (!validName(name)) throw new IOException("недопустимое имя мира: " + name);
		Files.createDirectories(out.toAbsolutePath().getParent());
		Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
		int count = 0;
		long bytes = 0;
		boolean withPlaces = false;
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(tmp))) {
			zip.setLevel(Deflater.DEFAULT_COMPRESSION);
			Set<String> written = new HashSet<>();
			List<String> rels = new ArrayList<>();
			// историю версий (гигабайты) не обходим вовсе
			try (Stream<Path> walk = Files.walk(world)) {
				walk.filter(Files::isRegularFile).forEach(p -> rels.add(world.relativize(p).toString().replace('\\', '/')));
			}
			java.util.Collections.sort(rels);
			for (String rel : rels) {
				if (skipped(rel) || skippedByParent(rel)) continue;
				if (places != null && rel.equals(PLACES)) continue; // вместо него — файл из папки данных игры
				put(zip, world.resolve(rel), name + "/" + rel);
				written.add(rel);
				count++;
				bytes += Files.size(world.resolve(rel));
			}
			if (places != null && Files.isRegularFile(places)) {
				put(zip, places, name + "/" + PLACES);
				withPlaces = true;
				count++;
				bytes += Files.size(places);
			} else if (written.contains(PLACES)) {
				withPlaces = true;
			}
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(tmp);
			throw e;
		}
		try {
			Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
		}
		return new Result(out, count, bytes, withPlaces);
	}

	/** Файл лежит внутри пропущенной папки (на любом уровне). */
	private static boolean skippedByParent(String rel) {
		int slash = rel.indexOf('/');
		while (slash >= 0) {
			if (skipped(rel.substring(0, slash))) return true;
			slash = rel.indexOf('/', slash + 1);
		}
		return false;
	}

	private static void put(ZipOutputStream zip, Path file, String entryName) throws IOException {
		ZipEntry entry = new ZipEntry(entryName);
		entry.setTime(Files.getLastModifiedTime(file).toMillis());
		zip.putNextEntry(entry);
		Files.copy(file, zip);
		zip.closeEntry();
	}

	/** Действие хоста: сохранить мир на диск и выгрузить архив; итог — сообщением игроку. */
	static void run(MinecraftServer server, ServerPlayer player) {
		try {
			server.saveAllChunks(true, true, true);
			Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
			String name = world.getFileName().toString();
			Path game = FabricLoader.getInstance().getGameDir();
			Path out = game.resolve("data").resolve("smesharikimod").resolve("exports").resolve(name + ".zip");
			Result r = write(world, name, game.resolve("data").resolve("levelmod").resolve("worlds").resolve(name).resolve("places.json"), out);
			player.sendSystemMessage(Component.translatable("gui.smesharikimod.export.done", r.file().toString(), String.format("%.1f", Files.size(r.file()) / 1048576.0)));
			if (!r.places()) player.sendSystemMessage(Component.translatable("gui.smesharikimod.export.no_places"));
		} catch (IOException | RuntimeException e) {
			SmesharikiMod.LOGGER.error("Экспорт мира не удался", e);
			player.sendSystemMessage(Component.translatable("gui.smesharikimod.export.failed", String.valueOf(e.getMessage() != null ? e.getMessage() : e)));
		}
	}
}
