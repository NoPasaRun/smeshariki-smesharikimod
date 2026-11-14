package com.smeshariki.mod.client;

import java.util.List;

import net.minecraft.network.chat.Component;

/** Что экран установки знает о сессии: предложение, ход загрузки, итог. Её дают {@link SyncSession} (с хоста) и {@link GitSession} (с GitHub). */
public interface SyncView {
	SyncSession.State state();

	List<SyncSession.Item> items();

	long totalBytes();

	long doneBytes();

	int installed();

	boolean modsInstalled();

	boolean cancelled();

	List<String> errors();

	int currentNumber();

	String currentName();

	/** Есть ли в предложении мир, который можно не скачивать (только установка с GitHub). */
	default boolean hasWorld() {
		return false;
	}

	default boolean worldSelected() {
		return true;
	}

	default void setWorldSelected(boolean selected) {
	}

	/** Сколько файлов будет установлено с учётом отказа от мира. */
	default int selectedCount() {
		return items().size();
	}

	void start();

	void cancel();

	void tick();

	default Component title() {
		return Component.translatable("smesharikimod.title");
	}

	default Component warning() {
		return Component.translatable("smesharikimod.warning");
	}
}
