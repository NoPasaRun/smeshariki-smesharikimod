package com.managementmod.client;

import com.managementmod.net.ChunkPayload;
import com.managementmod.net.RequestPayload;
import com.managementmod.sync.FileEntry;
import com.managementmod.sync.FileInfo;
import com.managementmod.sync.SyncCategory;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Одна сессия «предложение → согласие → загрузка». Все поля меняются только в клиентском потоке;
 * запись на диск выполняется в отдельном потоке, результат возвращается через {@code Minecraft.execute}.
 *
 * <p>Каждый файл качается во временный {@code *.managementmod-part}, проверяется по SHA-256 из манифеста
 * и только потом переименовывается. Заменяемые файлы не удаляются, а переименовываются в {@code *.managementmod-old}.
 */
public final class SyncSession implements SyncView {
	public enum State { OFFER, DOWNLOADING, DONE }

	/** Файл к установке. {@code replaces} — локальные файлы, которые будут заменены этим. */
	public record Item(int index, FileEntry entry, List<Path> replaces) {
		public boolean isUpdate() {
			return !replaces.isEmpty();
		}
	}

	private static final long TIMEOUT_NANOS = 30_000_000_000L;
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "ManagementMod-Client-IO");
		thread.setDaemon(true);
		return thread;
	});

	private static final class Download {
		final Item item;
		final Path target;
		final Path part;
		final OutputStream out;
		final MessageDigest digest = FileInfo.newDigest();
		long offset;
		boolean writing;
		long lastActivity = System.nanoTime();

		Download(Item item, Path target, Path part, OutputStream out) {
			this.item = item;
			this.target = target;
			this.part = part;
			this.out = out;
		}
	}

	private final Minecraft minecraft;
	private final List<Item> items;
	private final long totalBytes;
	private final List<String> errors = new ArrayList<>();
	private State state = State.OFFER;
	private int next;
	private int installed;
	private long doneBytes;
	private boolean modsInstalled;
	private boolean cancelled;
	private Download active;

	public SyncSession(Minecraft minecraft, List<Item> items) {
		this.minecraft = minecraft;
		this.items = List.copyOf(items);
		this.totalBytes = items.stream().mapToLong(i -> i.entry().size()).sum();
	}

	@Override
	public State state() { return state; }
	@Override
	public List<Item> items() { return items; }
	@Override
	public long totalBytes() { return totalBytes; }
	@Override
	public long doneBytes() { return doneBytes; }
	@Override
	public int installed() { return installed; }
	@Override
	public boolean modsInstalled() { return modsInstalled; }
	@Override
	public boolean cancelled() { return cancelled; }
	@Override
	public List<String> errors() { return errors; }
	/** Номер файла, который качается сейчас (с 1). */
	@Override
	public int currentNumber() { return Math.min(next, items.size()); }
	@Override
	public String currentName() { return active == null ? "" : active.item.entry().name(); }

	@Override
	public void start() {
		if (state != State.OFFER) {
			return;
		}
		state = State.DOWNLOADING;
		startNext();
	}

	/** Остановить загрузку (по кнопке или при отключении от сервера). */
	@Override
	public void cancel() {
		if (state == State.DONE) {
			return;
		}
		cancelled = true;
		discardActive();
		state = State.DONE;
	}

	@Override
	public void tick() {
		if (state == State.DOWNLOADING && active != null && !active.writing
			&& System.nanoTime() - active.lastActivity > TIMEOUT_NANOS) {
			failActive("таймаут ответа сервера");
		}
	}

	private void startNext() {
		while (true) {
			if (cancelled || next >= items.size()) {
				state = State.DONE;
				return;
			}
			Item item = items.get(next++);
			try {
				Path dir = FabricLoader.getInstance().getGameDir().resolve(item.entry().category().dir).normalize();
				Files.createDirectories(dir);
				Path target = dir.resolve(item.entry().name()).normalize();
				if (!dir.equals(target.getParent())) {
					throw new IOException("недопустимое имя файла");
				}
				Path part = dir.resolve(item.entry().name() + ".managementmod-part");
				OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
				active = new Download(item, target, part, out);
				ClientConfigurationNetworking.send(new RequestPayload(item.index(), 0));
				return;
			} catch (IOException e) {
				errors.add(item.entry().name() + ": " + e.getMessage());
			}
		}
	}

	public void onChunk(ChunkPayload chunk) {
		Download d = active;
		if (state != State.DOWNLOADING || d == null || d.writing || chunk.index() != d.item.index()) {
			return;
		}
		if (chunk.offset() == -1) {
			failActive("сервер не смог отдать файл");
			return;
		}
		long length = chunk.data().length;
		if (chunk.offset() != d.offset || length == 0 || d.offset + length > d.item.entry().size()) {
			failActive("некорректный ответ сервера");
			return;
		}
		d.writing = true;
		byte[] data = chunk.data();
		IO.execute(() -> {
			IOException error = null;
			try {
				d.out.write(data);
				d.digest.update(data);
			} catch (IOException e) {
				error = e;
			}
			IOException result = error;
			minecraft.execute(() -> afterWrite(d, data.length, result));
		});
	}

	private void afterWrite(Download d, int length, IOException error) {
		if (active != d) {
			return;
		}
		d.writing = false;
		if (error != null) {
			failActive(error.getMessage());
			return;
		}
		d.offset += length;
		d.lastActivity = System.nanoTime();
		doneBytes += length;
		if (d.offset >= d.item.entry().size()) {
			complete(d);
		} else {
			ClientConfigurationNetworking.send(new RequestPayload(d.item.index(), d.offset));
		}
	}

	private void complete(Download d) {
		d.writing = true; // новые куски больше не принимаются
		IO.execute(() -> {
			String error = null;
			try {
				d.out.close();
				if (!FileInfo.hex(d.digest.digest()).equals(d.item.entry().sha256())) {
					throw new IOException("контрольная сумма не совпала");
				}
				for (Path old : d.item.replaces()) {
					if (Files.exists(old)) {
						Files.move(old, old.resolveSibling(old.getFileName() + ".managementmod-old"),
							StandardCopyOption.REPLACE_EXISTING);
					}
				}
				Files.move(d.part, d.target, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				error = String.valueOf(e.getMessage());
				deleteQuietly(d.part);
			}
			String message = error;
			minecraft.execute(() -> {
				if (active != d) {
					return;
				}
				active = null;
				if (message == null) {
					installed++;
					modsInstalled |= d.item.entry().category() == SyncCategory.MOD;
				} else {
					errors.add(d.item.entry().name() + ": " + message);
				}
				startNext();
			});
		});
	}

	private void failActive(String message) {
		Download d = active;
		if (d == null) {
			return;
		}
		errors.add(d.item.entry().name() + ": " + message);
		discardActive();
		startNext();
	}

	private void discardActive() {
		Download d = active;
		active = null;
		if (d != null) {
			IO.execute(() -> {
				try {
					d.out.close();
				} catch (IOException ignored) {
				}
				deleteQuietly(d.part);
			});
		}
	}

	private static void deleteQuietly(Path path) {
		try {
			Files.deleteIfExists(path);
		} catch (IOException ignored) {
		}
	}
}
