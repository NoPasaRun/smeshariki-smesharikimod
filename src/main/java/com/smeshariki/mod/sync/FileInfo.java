package com.smeshariki.mod.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Размер, время изменения, SHA-256 и метаданные мода. Хэши кэшируются по (путь, размер, mtime). */
public record FileInfo(long size, long mtime, String sha256, ModMeta meta) {
	private static final Map<Path, FileInfo> CACHE = new ConcurrentHashMap<>();

	public static FileInfo of(Path path, boolean readModMeta) throws IOException {
		Path key = path.toAbsolutePath().normalize();
		long size = Files.size(key);
		long mtime = Files.getLastModifiedTime(key).toMillis();
		FileInfo cached = CACHE.get(key);
		if (cached != null && cached.size == size && cached.mtime == mtime
			&& (!readModMeta || cached.meta != null)) {
			return cached;
		}
		FileInfo fresh = new FileInfo(size, mtime, sha256(key), readModMeta ? ModMeta.read(key) : null);
		CACHE.put(key, fresh);
		return fresh;
	}

	public static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static String hex(byte[] bytes) {
		return HexFormat.of().formatHex(bytes);
	}

	private static String sha256(Path path) throws IOException {
		MessageDigest digest = newDigest();
		try (InputStream in = Files.newInputStream(path)) {
			byte[] buffer = new byte[64 * 1024];
			int n;
			while ((n = in.read(buffer)) > 0) {
				digest.update(buffer, 0, n);
			}
		}
		return hex(digest.digest());
	}
}
