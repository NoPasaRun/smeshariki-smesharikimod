package com.managementmod.client;

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

	void start();

	void cancel();

	void tick();

	default Component title() {
		return Component.translatable("managementmod.title");
	}

	default Component warning() {
		return Component.translatable("managementmod.warning");
	}
}
