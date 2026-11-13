package com.smeshariki.mod.sync;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Locale;

/**
 * Описание одного файла в манифесте хоста.
 *
 * <p>Приходящие по сети записи считаются недоверенными: перед использованием
 * на клиенте обязательно вызывается {@link #isValid()}.
 */
public record FileEntry(SyncCategory category, String name, long size, String sha256, String modId, String modVersion) {
	/** Верхняя граница размера одного файла, которую клиент вообще согласится принять. */
	public static final long MAX_FILE_SIZE = 1L << 30;

	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(category.ordinal());
		buf.writeUtf(name, 256);
		buf.writeLong(size);
		buf.writeUtf(sha256, 64);
		buf.writeUtf(modId, 128);
		buf.writeUtf(modVersion, 128);
	}

	public static FileEntry read(FriendlyByteBuf buf) {
		SyncCategory category = SyncCategory.byId(buf.readVarInt());
		return new FileEntry(category, buf.readUtf(256), buf.readLong(), buf.readUtf(64), buf.readUtf(128), buf.readUtf(128));
	}

	public boolean isValid() {
		return category != null
			&& isSafeName(category, name)
			&& size > 0 && size <= MAX_FILE_SIZE
			&& sha256 != null && sha256.length() == 64 && sha256.chars().allMatch(c -> Character.digit(c, 16) >= 0);
	}

	/** Имя обязано быть простым именем файла: никаких путей, служебных символов и скрытых файлов. */
	public static boolean isSafeName(SyncCategory category, String name) {
		if (name == null || name.isEmpty() || name.length() > 120 || name.startsWith(".")
			|| name.endsWith(" ") || name.endsWith(".")) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0) {
				return false;
			}
		}
		return name.toLowerCase(Locale.ROOT).endsWith(category.extension);
	}
}
