package com.installermod.sync;

import com.installermod.net.ChunkPayload;
import com.installermod.net.DonePayload;
import com.installermod.net.ManifestPayload;
import com.installermod.net.RequestPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * Серверная часть: при входе игрока формирует манифест из папок mods/resourcepacks/shaderpacks
 * и отдаёт файлы кусками по запросам клиента.
 *
 * <p>Клиент запрашивает файл только по номеру из манифеста, который сервер сам ему выдал, —
 * произвольные пути от клиента не принимаются.
 */
public final class ServerSync {
	private static final Logger LOGGER = LoggerFactory.getLogger("InstallerMod");
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "InstallerMod-IO");
		thread.setDaemon(true);
		return thread;
	});

	private record Served(FileEntry entry, Path path, long mtime) {}

	private static final long TIMEOUT_NANOS = 15L * 60 * 1_000_000_000L;
	private static final ConfigurationTask.Type TASK_TYPE = new ConfigurationTask.Type("installermod:sync");

	/** Состояние одного подключающегося клиента: манифест, выданный именно ему, и признак завершения. */
	private static final class Session {
		volatile List<Served> files;
		volatile boolean completed;
		volatile long lastActivity = System.nanoTime();
	}

	private static final Map<ServerConfigurationPacketListenerImpl, Session> SESSIONS = new ConcurrentHashMap<>();

	/**
	 * Задача фазы конфигурации. Пока клиент не ответит {@link DonePayload}, сервер дальше не идёт —
	 * в том числе к сверке реестров, на которой клиент без нужных модов иначе отключился бы.
	 */
	private record SyncTask(ServerConfigurationPacketListenerImpl handler, MinecraftServer server, Session session)
		implements ConfigurationTask {
		@Override
		public void start(Consumer<net.minecraft.network.protocol.Packet<?>> send) {
			if (!ServerConfigurationNetworking.canSend(handler, ManifestPayload.TYPE)) {
				finish(server, handler, session); // у клиента нет InstallerMod
				return;
			}
			IO.execute(() -> {
				List<Served> files;
				try {
					files = scan();
				} catch (IOException | RuntimeException e) {
					LOGGER.error("Не удалось сформировать манифест", e);
					files = List.of();
				}
				List<Served> result = files;
				server.execute(() -> {
					if (SESSIONS.get(handler) != session) {
						return; // клиент уже отключился
					}
					if (result.isEmpty()) {
						complete(handler, session);
						return;
					}
					session.files = result;
					session.lastActivity = System.nanoTime();
					LOGGER.info("Клиенту предложено файлов: {}", result.size());
					ServerConfigurationNetworking.send(handler, new ManifestPayload(ManifestPayload.PROTOCOL,
						result.stream().map(Served::entry).toList()));
				});
			});
		}

		@Override
		public boolean tick() {
			// Завершать задачу нужно только через completeTask (см. complete): возврат true из tick()
			// обходит Fabric, и обычные задачи конфигурации никогда не будут поставлены в очередь.
			if (!session.completed && System.nanoTime() - session.lastActivity > TIMEOUT_NANOS) {
				LOGGER.warn("Клиент не ответил вовремя, продолжаю вход без синхронизации");
				complete(handler, session);
			}
			return false;
		}

		@Override
		public Type type() {
			return TASK_TYPE;
		}
	}

	private static SyncConfig config;

	/** Размер и mtime каждого jar в mods/ на момент запуска: именно эти файлы реально загружены в JVM. */
	private static final Map<String, long[]> LOADED_MODS = new ConcurrentHashMap<>();
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private ServerSync() {}

	/** Отложенное завершение: безопасно вызывать из {@code ConfigurationTask.start}. */
	private static void finish(MinecraftServer server, ServerConfigurationPacketListenerImpl handler, Session session) {
		server.execute(() -> complete(handler, session));
	}

	/** Единственный правильный способ закончить раннюю задачу конфигурации в Fabric. */
	private static void complete(ServerConfigurationPacketListenerImpl handler, Session session) {
		if (session.completed) {
			return;
		}
		session.completed = true;
		SESSIONS.remove(handler);
		try {
			handler.completeTask(TASK_TYPE);
		} catch (RuntimeException e) {
			LOGGER.warn("Не удалось завершить задачу синхронизации", e);
		}
	}

	public static void init() {
		PayloadTypeRegistry.clientboundConfiguration().register(ManifestPayload.TYPE, ManifestPayload.CODEC);
		PayloadTypeRegistry.clientboundConfiguration().register(ChunkPayload.TYPE, ChunkPayload.CODEC);
		PayloadTypeRegistry.serverboundConfiguration().register(RequestPayload.TYPE, RequestPayload.CODEC);
		PayloadTypeRegistry.serverboundConfiguration().register(DonePayload.TYPE, DonePayload.CODEC);

		config = SyncConfig.load();
		snapshotLoadedMods();
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
			// Чтобы владельцу сервера было очевидно, куда класть файлы для раздачи.
			for (SyncCategory category : SyncCategory.values()) {
				try {
					Files.createDirectories(gameDir().resolve(category.dir));
				} catch (IOException e) {
					LOGGER.warn("Не удалось создать папку {}", category.dir, e);
				}
			}
		}

		// Fabric добавляет задачу сверки реестров в BEFORE_CONFIGURE (фаза по умолчанию),
		// поэтому наша задача регистрируется в более ранней фазе и выполняется раньше неё.
		Identifier early = Identifier.fromNamespaceAndPath("installermod", "early");
		ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.addPhaseOrdering(early, Event.DEFAULT_PHASE);
		ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register(early, (handler, server) -> {
			Session session = new Session();
			SESSIONS.put(handler, session);
			handler.addTask(new SyncTask(handler, server, session));
		});
		ServerConfigurationConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.remove(handler));

		ServerConfigurationNetworking.registerGlobalReceiver(RequestPayload.TYPE, (payload, context) ->
			handleRequest(context.server(), context.packetListener(), payload));
		ServerConfigurationNetworking.registerGlobalReceiver(DonePayload.TYPE, (payload, context) -> {
			Session session = SESSIONS.get(context.packetListener());
			if (session != null) {
				LOGGER.info("Клиент завершил синхронизацию");
				complete(context.packetListener(), session);
			}
		});
	}

	private static void snapshotLoadedMods() {
		Path dir = gameDir().resolve(SyncCategory.MOD.dir);
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> stream = Files.list(dir)) {
			for (Path path : stream.filter(Files::isRegularFile).toList()) {
				try {
					LOADED_MODS.put(path.getFileName().toString(),
						new long[] {Files.size(path), Files.getLastModifiedTime(path).toMillis()});
				} catch (IOException e) {
					LOGGER.warn("Не удалось прочитать {}: {}", path, e.toString());
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Не удалось прочитать папку mods: {}", e.toString());
		}
	}

	/**
	 * Мод раздаётся, только если он был в mods/ при запуске и с тех пор не менялся. Иначе клиенты получили бы
	 * версию, отличную от той, что реально работает на сервере (файл заменили, а сервер не перезапускали).
	 */
	private static boolean isLoadedAsIs(String name, FileInfo info) {
		long[] snapshot = LOADED_MODS.get(name);
		boolean ok = snapshot != null && snapshot[0] == info.size() && snapshot[1] == info.mtime();
		if (!ok && WARNED.add(name + '/' + info.size() + '/' + info.mtime())) {
			LOGGER.warn("Мод {} {} с момента запуска сервера — не раздаётся, пока сервер не перезапущен",
				name, snapshot == null ? "добавлен" : "изменён");
		}
		return ok;
	}

	private static Path gameDir() {
		return FabricLoader.getInstance().getGameDir();
	}

	private static List<Served> scan() throws IOException {
		List<Served> result = new ArrayList<>();
		for (SyncCategory category : SyncCategory.values()) {
			if (!config.isEnabled(category)) {
				continue;
			}
			Path dir = gameDir().resolve(category.dir);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			List<Path> paths;
			try (Stream<Path> stream = Files.list(dir)) {
				paths = stream.filter(Files::isRegularFile)
					.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
					.toList();
			}
			for (Path path : paths) {
				String name = path.getFileName().toString();
				if (!FileEntry.isSafeName(category, name) || config.excludeFiles.contains(name)) {
					continue;
				}
				try {
					FileInfo info = FileInfo.of(path, category == SyncCategory.MOD);
					if (info.size() <= 0 || info.size() > config.maxFileSizeBytes()) {
						continue;
					}
					ModMeta meta = info.meta() == null ? ModMeta.NONE : info.meta();
					if (category == SyncCategory.MOD
						&& (meta.id().equals("installermod") || meta.environment().equals("server"))) {
						continue; // серверные моды клиенту не нужны
					}
					if (category == SyncCategory.MOD && config.onlyServeLoadedMods && !isLoadedAsIs(name, info)) {
						continue;
					}
					result.add(new Served(new FileEntry(category, name, info.size(), info.sha256(),
						clip(meta.id()), clip(meta.version())), path, info.mtime()));
				} catch (IOException e) {
					LOGGER.warn("Пропускаю {}: {}", path, e.toString());
				}
			}
		}
		return result;
	}

	private static String clip(String value) {
		return value.length() > 128 ? value.substring(0, 128) : value;
	}

	private static void handleRequest(MinecraftServer server, ServerConfigurationPacketListenerImpl handler,
									  RequestPayload request) {
		Session session = SESSIONS.get(handler);
		List<Served> files = session == null ? null : session.files;
		if (files == null || request.index() < 0 || request.index() >= files.size()) {
			return;
		}
		Served served = files.get(request.index());
		long offset = request.offset();
		if (offset < 0 || offset >= served.entry().size()) {
			return;
		}
		session.lastActivity = System.nanoTime();
		IO.execute(() -> {
			byte[] data = null;
			try (FileChannel channel = FileChannel.open(served.path(), StandardOpenOption.READ)) {
				// Файл не должен меняться с момента формирования манифеста, иначе хэш не сойдётся.
				if (channel.size() != served.entry().size()
					|| Files.getLastModifiedTime(served.path()).toMillis() != served.mtime()) {
					throw new IOException("файл изменился");
				}
				int length = (int) Math.min(ChunkPayload.CHUNK_SIZE, served.entry().size() - offset);
				ByteBuffer buffer = ByteBuffer.allocate(length);
				channel.position(offset);
				while (buffer.hasRemaining()) {
					if (channel.read(buffer) < 0) {
						throw new IOException("неожиданный конец файла");
					}
				}
				data = buffer.array();
			} catch (IOException e) {
				LOGGER.warn("Не удалось прочитать {}: {}", served.path(), e.toString());
			}
			byte[] chunk = data;
			server.execute(() -> {
				if (SESSIONS.get(handler) == session) {
					ServerConfigurationNetworking.send(handler, chunk == null
						? new ChunkPayload(request.index(), -1, new byte[0])
						: new ChunkPayload(request.index(), offset, chunk));
				}
			});
		});
	}
}
