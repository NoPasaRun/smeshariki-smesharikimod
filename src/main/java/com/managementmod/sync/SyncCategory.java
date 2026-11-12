package com.managementmod.sync;

/** Что именно синхронизируется: папка внутри игровой директории и допустимое расширение файла. */
public enum SyncCategory {
	MOD("mods", ".jar", "managementmod.category.mod"),
	RESOURCE_PACK("resourcepacks", ".zip", "managementmod.category.resourcepack"),
	SHADER_PACK("shaderpacks", ".zip", "managementmod.category.shaderpack"),
	/** Только при установке с GitHub: архив мира распаковывается в {@code saves/} (сервер мир не раздаёт). Номер в протоколе — последний, прежние не сдвигаются. */
	WORLD("saves", ".zip", "managementmod.category.world");

	public final String dir;
	public final String extension;
	public final String translationKey;

	SyncCategory(String dir, String extension, String translationKey) {
		this.dir = dir;
		this.extension = extension;
		this.translationKey = translationKey;
	}

	/** @return категория по её номеру в протоколе или {@code null}, если номер неизвестен. */
	public static SyncCategory byId(int id) {
		SyncCategory[] all = values();
		return id >= 0 && id < all.length ? all[id] : null;
	}
}
