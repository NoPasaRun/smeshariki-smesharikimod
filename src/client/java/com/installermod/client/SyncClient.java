package com.installermod.client;

import com.installermod.net.ChunkPayload;
import com.installermod.net.DonePayload;
import com.installermod.net.ManifestPayload;
import com.installermod.sync.FileEntry;
import com.installermod.sync.FileInfo;
import com.installermod.sync.SyncCategory;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/** Клиентская часть: получает манифест, находит недостающие файлы и предлагает их установить. */
public final class SyncClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("InstallerMod");
	/** Больше этого за одну сессию клиент не согласится принимать, что бы ни заявил сервер. */
	private static final long MAX_TOTAL_BYTES = 4L << 30;
	private static final ExecutorService ANALYZE = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "InstallerMod-Analyze");
		thread.setDaemon(true);
		return thread;
	});

	private static SyncSession session;
	private static PacketSender sender;
	private static Screen previousScreen;

	private SyncClient() {}

	public static void init() {
		ClientConfigurationNetworking.registerGlobalReceiver(ManifestPayload.TYPE, (payload, context) ->
			onManifest(context.client(), context.responseSender(), payload));
		ClientConfigurationNetworking.registerGlobalReceiver(ChunkPayload.TYPE, (payload, context) -> {
			if (session != null) {
				session.onChunk(payload);
			}
		});
		ClientConfigurationConnectionEvents.DISCONNECT.register((handler, client) -> reset());
	}

	private static void reset() {
		if (session != null) {
			session.cancel();
		}
		session = null;
		sender = null;
		previousScreen = null;
	}

	private static void sendDone() {
		try {
			ClientConfigurationNetworking.send(new DonePayload());
		} catch (RuntimeException e) {
			LOGGER.warn("Не удалось отправить ответ серверу", e);
		}
	}

	/**
	 * Завершает предложение: отказ, итог или закрытие. Если установлены моды, вход невозможен до перезапуска
	 * игры — соединение закрывается с понятным сообщением. Иначе сервер продолжает вход.
	 */
	public static void finish(Minecraft client) {
		SyncSession finished = session;
		PacketSender connection = sender;
		Screen back = previousScreen;
		session = null;
		sender = null;
		previousScreen = null;
		if (finished == null) {
			return;
		}
		client.gui.setScreen(back); // экран подключения продолжает обрабатывать соединение
		if (finished.modsInstalled() && connection != null) {
			connection.disconnect(Component.translatable("installermod.restart"));
		} else {
			sendDone();
		}
	}

	private static void onManifest(Minecraft client, PacketSender handler,
								   ManifestPayload manifest) {
		if (manifest.protocol() != ManifestPayload.PROTOCOL || session != null) {
			sendDone();
			return;
		}
		ANALYZE.execute(() -> {
			List<SyncSession.Item> items = List.of();
			try {
				items = analyze(manifest.files());
			} catch (RuntimeException e) {
				LOGGER.error("Не удалось обработать манифест сервера", e);
			}
			List<SyncSession.Item> result = items;
			client.execute(() -> {
				if (result.isEmpty() || session != null) {
					sendDone();
					return;
				}
				sender = handler;
				session = new SyncSession(client, result);
				previousScreen = client.gui.screen();
				client.gui.setScreen(new SyncScreen(session, previousScreen));
			});
		});
	}

	private record Local(Path path, String version) {}

	private static final class LocalDir {
		final Path dir;
		final Map<String, Path> byHash = new HashMap<>();
		final Map<String, List<Local>> byModId = new HashMap<>();

		LocalDir(Path dir, SyncCategory category) {
			this.dir = dir;
			if (!Files.isDirectory(dir)) {
				return;
			}
			try (Stream<Path> stream = Files.list(dir)) {
				for (Path path : stream.filter(Files::isRegularFile).toList()) {
					if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(category.extension)) {
						continue;
					}
					try {
						FileInfo info = FileInfo.of(path, category == SyncCategory.MOD);
						byHash.put(info.sha256(), path);
						if (info.meta() != null && !info.meta().id().isEmpty()) {
							byModId.computeIfAbsent(info.meta().id(), k -> new ArrayList<>())
								.add(new Local(path, info.meta().version()));
						}
					} catch (IOException e) {
						LOGGER.warn("Не удалось прочитать {}: {}", path, e.toString());
					}
				}
			} catch (IOException e) {
				LOGGER.warn("Не удалось прочитать папку {}: {}", dir, e.toString());
			}
		}
	}

	/** Оставляет только то, чего у клиента ещё нет. Данные манифеста недоверенные — всё проверяется. */
	private static List<SyncSession.Item> analyze(List<FileEntry> files) {
		Path game = FabricLoader.getInstance().getGameDir();
		Map<SyncCategory, LocalDir> locals = new EnumMap<>(SyncCategory.class);
		Set<String> seen = new HashSet<>();
		List<SyncSession.Item> items = new ArrayList<>();
		long total = 0;
		for (int i = 0; i < files.size(); i++) {
			FileEntry entry = files.get(i);
			if (!entry.isValid() || !seen.add(entry.category() + "/" + entry.name().toLowerCase(Locale.ROOT))) {
				continue;
			}
			LocalDir local = locals.computeIfAbsent(entry.category(), c -> new LocalDir(game.resolve(c.dir), c));
			if (local.byHash.containsKey(entry.sha256())) {
				continue; // такой файл уже есть (под любым именем)
			}
			List<Path> replaces = new ArrayList<>();
			if (entry.category() == SyncCategory.MOD && !entry.modId().isEmpty()) {
				// Любой другой jar с тем же id (другая версия или пересборка с той же версией) заменяется:
				// два jar с одним id сломают запуск, а эталоном считается файл сервера.
				List<Local> same = local.byModId.getOrDefault(entry.modId(), List.of());
				same.forEach(l -> replaces.add(l.path()));
			}
			Path target = local.dir.resolve(entry.name());
			if (Files.exists(target) && !replaces.contains(target)) {
				replaces.add(target);
			}
			total += entry.size();
			if (total > MAX_TOTAL_BYTES) {
				LOGGER.warn("Сервер предлагает слишком много данных — предложение отклонено");
				return List.of();
			}
			items.add(new SyncSession.Item(i, entry, replaces));
		}
		return items;
	}
}
