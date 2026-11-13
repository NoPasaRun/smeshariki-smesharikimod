package com.smeshariki.mod.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Серверная конфигурация: config/smesharikimod.json. */
public class SyncConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("SmesharikiMod");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public boolean syncMods = true;
	public boolean syncResourcePacks = true;
	public boolean syncShaderPacks = true;
	/**
	 * Раздавать моды только в том виде, в каком они загружены на сервере: добавленные или изменённые
	 * после запуска jar не отдаются до перезапуска.
	 */
	public boolean onlyServeLoadedMods = true;
	/** Файлы больше этого размера (в МиБ) не раздаются. */
	public int maxFileSizeMb = 512;
	/** Имена файлов (например "secret-server-mod.jar"), которые никогда не раздаются. */
	public List<String> excludeFiles = new ArrayList<>();

	/**
	 * Установка с GitHub (для того, у кого нет хоста: сам хост или игрок один): репозиторий {@code владелец/имя}, последний релиз которого содержит
	 * {@code manifest.json} и файлы. По умолчанию — репозиторий демо; пусто — не проверять. Проверка один раз при запуске игры на экране заголовка, установка только по согласию.
	 */
	public String gitRepo = "NoPasaRun/smeshariki-demo";
	/** Проверять релиз при запуске игры. */
	public boolean gitAutoCheck = true;
	/** Адрес API (для проверок на своём узле можно указать {@code http://localhost:порт}). */
	public String gitApiBase = "https://api.github.com";

	public boolean isEnabled(SyncCategory category) {
		return switch (category) {
			case MOD -> syncMods;
			case RESOURCE_PACK -> syncResourcePacks;
			case SHADER_PACK -> syncShaderPacks;
			case WORLD -> false;
		};
	}

	public long maxFileSizeBytes() {
		return Math.min(FileEntry.MAX_FILE_SIZE, Math.max(1, maxFileSizeMb) * 1024L * 1024L);
	}

	public static SyncConfig load() {
		Path file = FabricLoader.getInstance().getConfigDir().resolve("smesharikimod.json");
		// мод назывался ManagementMod, а ещё раньше InstallerMod: прежний файл настроек переезжает в новый
		for (String previous : new String[] {"managementmod.json", "installermod.json"}) {
			Path old = FabricLoader.getInstance().getConfigDir().resolve(previous);
			try {
				if (Files.isRegularFile(old) && !Files.exists(file)) Files.move(old, file);
			} catch (IOException e) {
				LOGGER.warn("Не удалось перенести {} в {}", old, file, e);
			}
		}
		SyncConfig config = new SyncConfig();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				SyncConfig loaded = GSON.fromJson(reader, SyncConfig.class);
				if (loaded != null) {
					config = loaded;
					if (config.excludeFiles == null) {
						config.excludeFiles = new ArrayList<>();
					}
				}
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Не удалось прочитать {}, использую значения по умолчанию", file, e);
			}
		} else {
			try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			} catch (IOException e) {
				LOGGER.warn("Не удалось записать {}", file, e);
			}
		}
		return config;
	}
}
