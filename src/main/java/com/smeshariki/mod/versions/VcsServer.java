package com.smeshariki.mod.versions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.smeshariki.mod.SmesharikiMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Упрощённый git для мира: «версия» — снимок всех файлов мира. Файлы хранятся по хешу SHA-256
 * ({@code <мир>/buildmod_vcs/objects/<хеш>}), поэтому неизменившиеся области (region-файлы) между версиями не дублируются;
 * описание версии — {@code commits/<номер>.json} (название, время, список файлов с хешами). Хеши файлов, не менявшихся с прошлого раза
 * (размер и время изменения те же), берутся из {@code index.json}, а не считаются заново.
 * <p>
 * Откат: если LevelMod подключил движок живого отката ({@link WorldVersions#liveRollback}), мир возвращается к версии на ходу, по одному чанку за тик
 * (блоки и сущности). Иначе файлы открыты, и откат откладывается: сервер записывает {@code pending.json} и останавливается; на {@code SERVER_STOPPED}
 * файлы мира заменяются содержимым версии.
 */
public final class VcsServer {
	/**
	 * Папка истории: {@code <мир>/data/smesharikimod/versions}. Раньше лежала в корне мира как {@code managementmod_versions}, ещё раньше {@code buildmod_vcs}
	 * ({@link #migrateOldFolder} переносит её целиком).
	 */
	static final String DIR = "data/smesharikimod/versions";
	static final String[] OLD_DIRS = {"managementmod_versions", "buildmod_vcs"};
	/** Прежние пути в версиях → нынешние: старая версия при откате кладёт файл на новое место ({@link WorldVersions#renamed}). */
	static final Map<String, String> RENAMED = new java.util.concurrent.ConcurrentHashMap<>();

	/** Путь файла версии по нынешним правилам: прежнее имя (или папка с прежним именем) заменяется новым. */
	static String current(String rel) {
		for (var e : RENAMED.entrySet()) {
			if (rel.equals(e.getKey())) return e.getValue();
			if (rel.startsWith(e.getKey() + "/")) return e.getValue() + rel.substring(e.getKey().length());
		}
		return rel;
	}
	private static final int MAX_NAME = 48;
	/**
	 * Что не входит в снимок: сама история, замок сессии и всё, что другие моды объявили общим для версий через {@link WorldVersions#skipTopLevel}
	 * (например, библиотека построек строителя). Мод версий не знает, чьи это файлы.
	 */
	static final Set<String> SKIP_TOP = java.util.concurrent.ConcurrentHashMap.newKeySet();
	/**
	 * Что входит в версии: только состояние мира (блоки, сущности, их данные), всё остальное (игроки, настройки мира, данные уровней, кат-сцены) в снимок не берётся и
	 * при откате не трогается. Шаблоны путей от корня мира через {@code /}, сегмент {@code *} — любое имя; шаблон — и сам путь, и всё, что под ним.
	 */
	static final java.util.List<String[]> KEEP = new java.util.concurrent.CopyOnWriteArrayList<>();
	static {
		// блоки, биомы-метки и сущности всех измерений; сам `dimensions/*/*/data` (принудительные чанки и т. п.) не входит
		for (String p : new String[] {"dimensions/*/*/region", "dimensions/*/*/entities", "dimensions/*/*/poi",
				// состояние механик, привязанное к сущностям (вселённые смешарики, маршруты брёвен): откатывается вместе с ними
				"data/gameplaymod"}) KEEP.add(p.split("/"));
	}

	/** Входит ли файл (путь от корня мира) в версии. */
	static boolean kept(String rel) {
		String[] parts = rel.split("/");
		if (SKIP_TOP.contains(parts[0]) || rel.endsWith(".lock")) return false;
		for (String[] pattern : KEEP) if (matches(pattern, parts, false)) return true;
		return false;
	}

	/** Может ли в папке (путь от корня) лежать что-то из версий: папка совпадает с началом шаблона или лежит внутри него. */
	static boolean mayHoldKept(String relDir) {
		String[] parts = relDir.split("/");
		if (SKIP_TOP.contains(parts[0])) return false;
		for (String[] pattern : KEEP) if (matches(pattern, parts, true)) return true;
		return false;
	}

	/** @param partial путь может быть короче шаблона (папка на пути к нужному) */
	private static boolean matches(String[] pattern, String[] parts, boolean partial) {
		int n = partial ? Math.min(pattern.length, parts.length) : pattern.length;
		if (!partial && parts.length < pattern.length) return false;
		for (int i = 0; i < n; i++) if (!pattern[i].equals("*") && !pattern[i].equals(parts[i])) return false;
		return true;
	}
	static {
		for (String old : OLD_DIRS) SKIP_TOP.add(old);
		SKIP_TOP.add("session.lock");
	}
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private record Entry(String hash, long size) {
	}

	private record Commit(int id, String name, long time, String author, int changed, Map<String, Entry> files) {
		long bytes() {
			long sum = 0;
			for (Entry e : files.values()) sum += e.size();
			return sum;
		}
	}

	private static final Map<Integer, Commit> COMMITS = new LinkedHashMap<>();
	private static int nextId = 1;
	/** Указатель на текущую версию (как HEAD в git): последняя сохранённая или та, к которой откатились; 0 — версий нет. */
	private static int head;
	private static MinecraftServer server;
	/** Идёт живой откат: пока он не кончился, версии не сохраняются, не откатываются и не удаляются (снимок вышел бы из полуоткатанного мира). Только в памяти. */
	private static boolean rolling;

	private VcsServer() {
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			rolling = false;
			migrateOldFolder(s.getWorldPath(LevelResource.ROOT));
			try {
				Files.deleteIfExists(dir().resolve("pending.json")); // откат не состоялся — не применяем его потом неожиданно
			} catch (IOException ignored) {
			}
			load();
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			Path root = s.getWorldPath(LevelResource.ROOT);
			COMMITS.clear();
			server = null;
			rolling = false;
			applyPending(root);
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> send(handler.getPlayer()));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> send(newPlayer));
		ServerPlayNetworking.registerGlobalReceiver(VersionsPayloads.VcsAction.TYPE, (msg, ctx) -> action(ctx.player(), msg));
	}

	/** Мир с историей под прежним именем папки: переименовываем её целиком (одна атомарная операция на одной файловой системе). */
	private static void migrateOldFolder(Path root) {
		Path now = root.resolve(DIR);
		for (String name : OLD_DIRS) {
			Path old = root.resolve(name);
			if (!Files.isDirectory(old) || Files.exists(now)) continue;
			try {
				Files.createDirectories(now.getParent());
				Files.move(old, now, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException | RuntimeException e) {
				SmesharikiMod.LOGGER.error("Не удалось перенести папку версий мира {} в {}: история не будет видна", name, DIR, e);
			}
		}
	}

	private static void say(ServerPlayer player, String text) {
		player.sendOverlayMessage(net.minecraft.network.chat.Component.literal(text));
	}

	// ---------------------------------------------------------------- действия

	private static void action(ServerPlayer player, VersionsPayloads.VcsAction msg) {
		if (server == null) return;
		if (!allowed(player)) {
			say(player, "Версии мира доступны только хосту");
			return;
		}
		// в выживании мир не сохраняется и не откатывается вручную: изменения выживания и так возвращаются сами
		if (!player.isCreative()) {
			say(player, "Версии мира — только в творческом режиме");
			return;
		}
		if (rolling && msg.kind() != VersionsPayloads.VcsAction.RENAME) {
			say(player, "Идёт откат мира: дождитесь его конца");
			return;
		}
		switch (msg.kind()) {
			case VersionsPayloads.VcsAction.COMMIT -> commit(player, msg.text());
			case VersionsPayloads.VcsAction.ROLLBACK -> rollback(player, msg.id());
			case VersionsPayloads.VcsAction.DELETE -> delete(player, msg.id());
			case VersionsPayloads.VcsAction.RENAME -> rename(player, msg.id(), msg.text());
			case VersionsPayloads.VcsAction.EXPORT -> WorldExport.run(server, player);
			default -> {
			}
		}
	}

	private static boolean allowed(ServerPlayer player) {
		// хост — владелец мира, открытого для сети; на выделенном сервере хоста нет, там версиями ведают операторы
		if (server.isDedicatedServer()) return Commands.LEVEL_GAMEMASTERS.check(player.permissions());
		return server.isSingleplayerOwner(player.nameAndId());
	}

	private static void commit(ServerPlayer player, String requested) {
		try {
			Commit c = snapshot(clean(requested), player.getGameProfile().name());
			say(player, "Версия №" + c.id() + " «" + c.name() + "» сохранена (изменено файлов: " + c.changed() + ")");
		} catch (IOException | RuntimeException e) {
			say(player, "Не удалось сохранить версию: " + e);
		}
		sendAll();
	}

	private static void rename(ServerPlayer player, int id, String name) {
		Commit old = COMMITS.get(id);
		if (old == null || name.isBlank()) return;
		Commit renamed = new Commit(old.id(), clean(name), old.time(), old.author(), old.changed(), old.files());
		COMMITS.put(id, renamed);
		try {
			write(renamed);
		} catch (IOException e) {
			say(player, "Не удалось записать версию: " + e);
		}
		sendAll();
	}

	private static void delete(ServerPlayer player, int id) {
		Commit c = COMMITS.remove(id);
		if (c == null) return;
		try {
			if (head == id) {
				head = 0;
				for (Commit k : COMMITS.values()) head = k.id(); // указатель — на последнюю из оставшихся
				writeHead();
			}
			Files.deleteIfExists(commitFile(id));
			collectGarbage();
		} catch (IOException e) {
			say(player, "Версия удалена не полностью: " + e);
		}
		say(player, "Версия «" + c.name() + "» удалена");
		sendAll();
	}

	private static void rollback(ServerPlayer player, int id) {
		Commit target = COMMITS.get(id);
		if (target == null) {
			say(player, "Такой версии нет");
			return;
		}
		WorldVersions.LiveRollback live = WorldVersions.liveRollback();
		if (live != null) {
			rollbackLive(player, live, target);
			return;
		}
		try {
			head = id; // новых версий откат не создаёт, только двигает указатель
			writeHead();
			Files.writeString(dir().resolve("pending.json"), "{\"id\":" + id + "}", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			say(player, "Откат не начат: " + e);
			return;
		}
		sendAll();
		server.getPlayerList().getPlayers().forEach(p -> say(p, "Откат к «" + target.name() + "»: мир закрывается, откройте его снова (несохранённые изменения пропадут)"));
		server.halt(false);
	}

	/** Откат без остановки мира: движок читает текущую версию, поэтому указатель двигается первым и при отказе возвращается. */
	private static void rollbackLive(ServerPlayer player, WorldVersions.LiveRollback live, Commit target) {
		int before = head;
		head = target.id();
		writeHead();
		String refusal;
		try {
			refusal = live.begin(server, percent -> announce("Откат к «" + target.name() + "»: " + percent + "%"), error -> {
				rolling = false;
				announce(error == null ? "Мир возвращён к версии «" + target.name() + "»" : "Откат к «" + target.name() + "» не закончен: " + error);
				sendAll();
			});
		} catch (RuntimeException e) {
			SmesharikiMod.LOGGER.error("Откат версии мира не начат", e);
			refusal = e.toString();
		}
		if (refusal != null) {
			head = before;
			writeHead();
			say(player, refusal);
			return;
		}
		rolling = true;
		announce("Откат к «" + target.name() + "»: 0%");
		sendAll();
	}

	private static void announce(String text) {
		if (server != null) server.getPlayerList().getPlayers().forEach(p -> say(p, text));
	}

	// ---------------------------------------------------------------- снимок

	private static Commit snapshot(String name, String author) throws IOException {
		server.saveAllChunks(true, true, true);
		Path root = server.getWorldPath(LevelResource.ROOT);
		Files.createDirectories(objects());
		Map<String, JsonObject> index = readIndex();
		Map<String, Entry> files = new LinkedHashMap<>();
		List<Path> found = listFiles(root);
		for (Path file : found) {
			String rel = relative(root, file);
			long size = Files.size(file);
			long mtime = Files.getLastModifiedTime(file).toMillis();
			JsonObject cached = index.get(rel);
			String hash;
			if (cached != null && cached.get("size").getAsLong() == size && cached.get("mtime").getAsLong() == mtime
					&& Files.exists(object(cached.get("hash").getAsString()))) {
				hash = cached.get("hash").getAsString();
			} else {
				hash = sha256(file);
				Path obj = object(hash);
				if (!Files.exists(obj)) {
					Path tmp = obj.resolveSibling(hash + ".tmp");
					Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING);
					Files.move(tmp, obj, StandardCopyOption.REPLACE_EXISTING);
				}
				JsonObject o = new JsonObject();
				o.addProperty("size", size);
				o.addProperty("mtime", mtime);
				o.addProperty("hash", hash);
				index.put(rel, o);
			}
			files.put(rel, new Entry(hash, size));
		}
		index.keySet().retainAll(files.keySet());
		writeIndex(index);

		Commit last = COMMITS.get(head);
		int changed = 0;
		for (Map.Entry<String, Entry> e : files.entrySet()) {
			Entry before = last == null ? null : last.files().get(e.getKey());
			if (before == null || !before.hash().equals(e.getValue().hash())) changed++;
		}
		if (last != null) for (String rel : last.files().keySet()) if (!files.containsKey(rel)) changed++;

		Commit c = new Commit(nextId++, name, System.currentTimeMillis(), author, changed, files);
		write(c);
		COMMITS.put(c.id(), c);
		head = c.id();
		writeHead();
		return c;
	}

	private static List<Path> listFiles(Path root) throws IOException {
		List<Path> out = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile).filter(p -> !skipped(root, p)).forEach(out::add);
		}
		return out;
	}

	private static boolean skipped(Path root, Path file) {
		return !kept(relative(root, file));
	}

	private static String relative(Path root, Path file) {
		return root.relativize(file).toString().replace('\\', '/');
	}

	private static String sha256(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[1 << 16];
			for (int n; (n = in.read(buf)) > 0;) md.update(buf, 0, n);
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) sb.append(String.format("%02x", b));
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Удаляет объекты, на которые не ссылается ни одна версия. */
	private static void collectGarbage() throws IOException {
		Set<String> used = new HashSet<>();
		for (Commit c : COMMITS.values()) for (Entry e : c.files().values()) used.add(e.hash());
		if (!Files.isDirectory(objects())) return;
		try (Stream<Path> list = Files.list(objects())) {
			for (Path p : (Iterable<Path>) list::iterator) {
				String n = p.getFileName().toString();
				if (!used.contains(n)) Files.deleteIfExists(p);
			}
		}
	}

	// ---------------------------------------------------------------- откат при остановке

	private static void applyPending(Path root) {
		Path vcs = root.resolve(DIR);
		Path pending = vcs.resolve("pending.json");
		if (!Files.exists(pending)) return;
		try {
			int id = JsonParser.parseString(Files.readString(pending, StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsInt();
			Files.deleteIfExists(pending);
			JsonObject json = JsonParser.parseString(Files.readString(vcs.resolve("commits").resolve(id + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, Entry> files = new LinkedHashMap<>();
			// прежние имена файлов (до переноса данных модов в data/<мод>) — на нынешние места
			readFiles(json).forEach((name, entry) -> files.put(current(name), entry));
			// старые версии хранили и то, что теперь в снимок не входит (игроки, настройки мира, данные уровней): оно остаётся как есть
			files.keySet().removeIf(name -> !kept(name));
			// сначала проверяем, что все объекты на месте — иначе не трогаем мир
			for (Entry e : files.values())
				if (!Files.exists(vcs.resolve("objects").resolve(e.hash()))) throw new IOException("не хватает данных версии " + e.hash());
			for (Path file : listFiles(root)) if (!files.containsKey(relative(root, file))) Files.deleteIfExists(file);
			for (Map.Entry<String, Entry> e : files.entrySet()) {
				Path dest = root.resolve(e.getKey());
				if (Files.exists(dest) && Files.size(dest) == e.getValue().size() && sha256(dest).equals(e.getValue().hash())) continue;
				Files.createDirectories(dest.getParent());
				Path tmp = dest.resolveSibling(dest.getFileName() + ".vcs_tmp");
				Files.copy(vcs.resolve("objects").resolve(e.getValue().hash()), tmp, StandardCopyOption.REPLACE_EXISTING);
				Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
			}
			deleteEmptyDirs(root);
			Files.deleteIfExists(vcs.resolve("index.json")); // времена файлов изменились
		} catch (IOException | RuntimeException e) {
			SmesharikiMod.LOGGER.error("Откат версии мира не удался", e);
		}
	}

	private static void deleteEmptyDirs(Path root) throws IOException {
		List<Path> dirs;
		try (Stream<Path> walk = Files.walk(root)) {
			dirs = walk.filter(Files::isDirectory).filter(p -> !p.equals(root) && mayHoldKept(relative(root, p))).sorted(java.util.Comparator.reverseOrder()).toList();
		}
		for (Path d : dirs) {
			try (Stream<Path> s = Files.list(d)) {
				if (s.findAny().isEmpty()) Files.delete(d);
			}
		}
	}

	// ---------------------------------------------------------------- файлы истории

	private static Path dir() {
		return server.getWorldPath(LevelResource.ROOT).resolve(DIR);
	}

	private static Path objects() {
		return dir().resolve("objects");
	}

	private static Path object(String hash) {
		return objects().resolve(hash);
	}

	private static Path commitFile(int id) {
		return dir().resolve("commits").resolve(id + ".json");
	}

	private static Map<String, JsonObject> readIndex() {
		Map<String, JsonObject> map = new LinkedHashMap<>();
		try {
			Path f = dir().resolve("index.json");
			if (Files.exists(f)) {
				JsonObject root = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
				for (var e : root.entrySet()) map.put(e.getKey(), e.getValue().getAsJsonObject());
			}
		} catch (IOException | RuntimeException ignored) {
			map.clear();
		}
		return map;
	}

	private static void writeIndex(Map<String, JsonObject> index) throws IOException {
		JsonObject root = new JsonObject();
		index.forEach(root::add);
		Files.writeString(dir().resolve("index.json"), GSON.toJson(root), StandardCharsets.UTF_8);
	}

	private static void writeHead() {
		try {
			Files.createDirectories(dir());
			Files.writeString(dir().resolve("head.json"), "{\"id\":" + head + "}", StandardCharsets.UTF_8);
		} catch (IOException e) {
			SmesharikiMod.LOGGER.error("Не записан указатель версий", e);
		}
	}

	private static void write(Commit c) throws IOException {
		Files.createDirectories(commitFile(c.id()).getParent());
		JsonObject o = new JsonObject();
		o.addProperty("id", c.id());
		o.addProperty("name", c.name());
		o.addProperty("time", c.time());
		o.addProperty("author", c.author());
		o.addProperty("changed", c.changed());
		JsonObject files = new JsonObject();
		c.files().forEach((k, v) -> {
			JsonObject f = new JsonObject();
			f.addProperty("hash", v.hash());
			f.addProperty("size", v.size());
			files.add(k, f);
		});
		o.add("files", files);
		Path tmp = commitFile(c.id()).resolveSibling(c.id() + ".json.tmp");
		Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
		Files.move(tmp, commitFile(c.id()), StandardCopyOption.REPLACE_EXISTING);
	}

	private static Map<String, Entry> readFiles(JsonObject json) {
		Map<String, Entry> files = new LinkedHashMap<>();
		for (var e : json.getAsJsonObject("files").entrySet()) {
			JsonObject f = e.getValue().getAsJsonObject();
			files.put(e.getKey(), new Entry(f.get("hash").getAsString(), f.get("size").getAsLong()));
		}
		return files;
	}

	private static void load() {
		COMMITS.clear();
		nextId = 1;
		head = 0;
		Path commits = dir().resolve("commits");
		if (!Files.isDirectory(commits)) return;
		List<Commit> list = new ArrayList<>();
		try (Stream<Path> s = Files.list(commits)) {
			for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".json"))::iterator) {
				try {
					JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
					list.add(new Commit(o.get("id").getAsInt(), o.get("name").getAsString(), o.get("time").getAsLong(),
							o.has("author") ? o.get("author").getAsString() : "", o.get("changed").getAsInt(), readFiles(o)));
				} catch (IOException | RuntimeException ignored) {
				}
			}
		} catch (IOException ignored) {
		}
		list.sort(java.util.Comparator.comparingInt(Commit::id));
		for (Commit c : list) {
			COMMITS.put(c.id(), c);
			nextId = Math.max(nextId, c.id() + 1);
			head = c.id();
		}
		try {
			Path f = dir().resolve("head.json");
			if (Files.exists(f)) {
				int saved = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsInt();
				if (COMMITS.containsKey(saved)) head = saved;
			}
		} catch (IOException | RuntimeException ignored) {
		}
	}

	private static String clean(String name) {
		name = name.replace('\t', ' ').replace('\n', ' ').trim();
		if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME);
		return name.isEmpty() ? "Версия " + nextId : name;
	}

	// ---------------------------------------------------------------- рассылка

	private static void sendAll() {
		if (server == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) send(p);
	}

	private static void send(ServerPlayer player) {
		if (!allowed(player)) return;
		List<VersionsPayloads.Versions.Info> list = new ArrayList<>();
		for (Commit c : COMMITS.values())
			list.add(new VersionsPayloads.Versions.Info(c.id(), c.name(), c.time(), c.author(), c.changed(), c.files().size(), c.bytes()));
		ServerPlayNetworking.send(player, new VersionsPayloads.Versions(head, WorldVersions.liveRollback() != null, rolling, list));
	}
}
