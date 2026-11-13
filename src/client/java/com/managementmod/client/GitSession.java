package com.managementmod.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.managementmod.ManagementMod;
import com.managementmod.sync.GitRelease;
import com.managementmod.sync.SyncCategory;

import net.minecraft.network.chat.Component;

/**
 * Установка с GitHub: то же «предложение → согласие → загрузка», что и с хоста ({@link SyncSession}), но файлы идут по https из релиза репозитория
 * ({@link GitRelease}). Загрузка идёт в отдельном потоке, поля читает экран; каждый файл сверяется по SHA-256 из манифеста, заменяемый не удаляется, а
 * переименовывается в {@code *.managementmod-old}; мир распаковывается во временную папку и только потом ложится в {@code saves/} (существующий не трогается).
 */
public final class GitSession implements SyncView {
	/** Предел распаковки мира: защита от архива-бомбы. */
	private static final long MAX_UNPACKED = 8L << 30;
	private static final int MAX_FILES = 300_000;

	private final String apiBase, repo, tag;
	private final Path gameDir;
	private final List<GitRelease.Entry> entries;
	private final List<String> obsolete;
	private final List<SyncSession.Item> items = new ArrayList<>();
	private final List<String> errors = new CopyOnWriteArrayList<>();
	private final long totalBytes;
	private volatile SyncSession.State state = SyncSession.State.OFFER;
	private volatile long doneBytes;
	private volatile int next, installed;
	private volatile boolean modsInstalled, cancelled;
	private volatile String current = "";
	private Thread worker;

	public GitSession(String apiBase, String repo, GitRelease.Plan plan, Path gameDir) {
		this.apiBase = apiBase;
		this.repo = repo;
		this.tag = plan.tag();
		this.gameDir = gameDir;
		this.entries = plan.entries();
		this.obsolete = plan.obsolete();
		for (int i = 0; i < entries.size(); i++) {
			var file = entries.get(i).file();
			Path local = gameDir.resolve(file.category().dir).resolve(file.name());
			items.add(new SyncSession.Item(i, file, file.category() != SyncCategory.WORLD && Files.exists(local) ? List.of(local) : List.of()));
		}
		this.totalBytes = items.stream().mapToLong(i -> i.entry().size()).sum();
	}

	@Override
	public SyncSession.State state() {
		return state;
	}

	@Override
	public List<SyncSession.Item> items() {
		return items;
	}

	@Override
	public long totalBytes() {
		return totalBytes;
	}

	@Override
	public long doneBytes() {
		return doneBytes;
	}

	@Override
	public int installed() {
		return installed;
	}

	@Override
	public boolean modsInstalled() {
		return modsInstalled;
	}

	@Override
	public boolean cancelled() {
		return cancelled;
	}

	@Override
	public List<String> errors() {
		return errors;
	}

	@Override
	public int currentNumber() {
		return Math.min(next, items.size());
	}

	@Override
	public String currentName() {
		return current;
	}

	@Override
	public Component title() {
		return Component.translatable("managementmod.git.title", tag);
	}

	@Override
	public Component warning() {
		Component warning = Component.translatable("managementmod.git.warning", repo);
		if (!obsolete.isEmpty()) warning = warning.copy().append(" ").append(Component.translatable("managementmod.git.obsolete", String.join(", ", obsolete)));
		return warning;
	}

	@Override
	public void tick() {
	}

	@Override
	public void start() {
		if (state != SyncSession.State.OFFER) return;
		state = SyncSession.State.DOWNLOADING;
		worker = new Thread(this::run, "ManagementMod-Git-Download");
		worker.setDaemon(true);
		worker.start();
	}

	@Override
	public void cancel() {
		if (state == SyncSession.State.DONE) return;
		cancelled = true;
		if (worker != null) worker.interrupt();
	}

	private void run() {
		for (int i = 0; i < items.size() && !cancelled; i++) {
			next = i + 1;
			var item = items.get(i);
			current = item.entry().name();
			try {
				install(entries.get(i), item);
				installed++;
				modsInstalled |= item.entry().category() == SyncCategory.MOD;
			} catch (InterruptedException e) {
				cancelled = true;
			} catch (IOException | RuntimeException e) {
				errors.add(item.entry().name() + ": " + e.getMessage());
				ManagementMod.LOGGER.warn("Установка с GitHub: {} не поставлен", item.entry().name(), e);
			}
		}
		if (!cancelled) disableObsolete();
		state = SyncSession.State.DONE;
	}

	/** Устаревшие моды (прежние имена) не удаляются, а переименовываются в {@code *.managementmod-old}: рядом с новым они дают дубль и игра не запускается. */
	private void disableObsolete() {
		Path mods = gameDir.resolve(SyncCategory.MOD.dir);
		for (String name : obsolete) {
			Path old = mods.resolve(name);
			try {
				if (Files.isRegularFile(old)) {
					Files.move(old, old.resolveSibling(name + ".managementmod-old"), StandardCopyOption.REPLACE_EXISTING);
					modsInstalled = true;
				}
			} catch (IOException e) {
				errors.add(name + ": " + e.getMessage());
			}
		}
	}

	private void install(GitRelease.Entry entry, SyncSession.Item item) throws IOException, InterruptedException {
		var file = entry.file();
		Path dir = gameDir.resolve(file.category().dir).normalize();
		Files.createDirectories(dir);
		Path part = dir.resolve(file.name() + ".managementmod-part");
		try {
			String sum = GitRelease.download(apiBase, entry.url(), part, n -> doneBytes += n);
			if (!sum.equals(file.sha256())) throw new IOException("контрольная сумма не совпала");
			if (file.category() == SyncCategory.WORLD) {
				unpackWorld(part, dir, entry.world());
				return;
			}
			Path target = dir.resolve(file.name()).normalize();
			if (!dir.equals(target.getParent())) throw new IOException("недопустимое имя файла");
			for (Path old : item.replaces()) {
				if (Files.exists(old)) Files.move(old, old.resolveSibling(old.getFileName() + ".managementmod-old"), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			Files.deleteIfExists(part);
		}
	}

	/** Архив мира → {@code saves/<мир>}: через временную папку, с проверкой путей и размера; уже существующий мир не заменяется. */
	private static void unpackWorld(Path zip, Path saves, String world) throws IOException {
		Path target = saves.resolve(world).normalize();
		if (!saves.equals(target.getParent())) throw new IOException("недопустимое имя мира");
		if (Files.exists(target)) throw new IOException("мир «" + world + "» уже есть");
		Path tmp = saves.resolve(".managementmod-unpack-" + System.nanoTime());
		Files.createDirectories(tmp);
		try {
			long total = 0;
			int count = 0;
			try (InputStream raw = Files.newInputStream(zip); ZipInputStream in = new ZipInputStream(raw)) {
				for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
					if (++count > MAX_FILES) throw new IOException("в архиве слишком много файлов");
					Path out = tmp.resolve(e.getName()).normalize();
					if (!out.startsWith(tmp) || out.equals(tmp)) throw new IOException("недопустимый путь в архиве: " + e.getName());
					if (e.isDirectory()) {
						Files.createDirectories(out);
						continue;
					}
					Files.createDirectories(out.getParent());
					try (var o = Files.newOutputStream(out)) {
						byte[] buffer = new byte[1 << 16];
						for (int n; (n = in.read(buffer)) > 0; ) {
							total += n;
							if (total > MAX_UNPACKED) throw new IOException("архив слишком большой после распаковки");
							o.write(buffer, 0, n);
						}
					}
				}
			}
			// корень: либо единственная папка внутри архива, либо сам архив (есть level.dat)
			Path root = tmp;
			if (!Files.exists(tmp.resolve("level.dat"))) {
				try (var s = Files.list(tmp)) {
					List<Path> top = s.toList();
					if (top.size() == 1 && Files.isDirectory(top.get(0)) && Files.exists(top.get(0).resolve("level.dat"))) root = top.get(0);
					else throw new IOException("в архиве не мир (нет level.dat)");
				}
			}
			Files.move(root, target);
		} finally {
			if (Files.exists(tmp)) {
				try (var walk = Files.walk(tmp)) {
					for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
				}
			}
		}
	}
}
