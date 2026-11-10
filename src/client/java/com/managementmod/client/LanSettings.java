package com.managementmod.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.MultiplayerOptionsScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;

/**
 * Сетевая игра без проверки аккаунтов. Встроенный сервер игры всегда работает в online-режиме, и игроки с пиратскими
 * аккаунтами (или вы сами) не могут зайти: «не прошёл авторизацию». Пока настройка включена, а мир открыт для сети,
 * проверка отключается — как {@code online-mode=false} у выделенного сервера. Кнопка — на экране настроек сетевой игры
 * (пауза → «Сетевая игра»), значение хранится в {@code config/managementmod_lan.txt}.
 */
final class LanSettings {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("managementmod_lan.txt");

	/** Отключать проверку аккаунтов у сервера, открытого для сети. */
	private static boolean offline = migrate() | load();
	/** Мы уже отключили проверку на текущем встроенном сервере (чтобы вернуть её при выключении настройки). */
	private static boolean applied;

	private LanSettings() {
	}

	static void init() {
		// кнопка на экране настроек сетевой игры, слева сверху
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof MultiplayerOptionsScreen)) return;
			Button button = Button.builder(label(), b -> {
				offline = !offline;
				save();
				b.setMessage(label());
				apply(Minecraft.getInstance());
			}).bounds(6, 6, 170, 20)
					.tooltip(Tooltip.create(Component.literal("Включено — зайти в ваш мир могут игроки с любыми аккаунтами, в том числе пиратскими (как online-mode=false). Выключено — обычная проверка.")))
					.build();
			try {
				Screens.getWidgets(screen).add(button);
			} catch (RuntimeException e) {
				// экран не принимает чужие кнопки — без переключателя игра работает как обычно
			}
		});
	}

	private static Component label() {
		return Component.literal("Проверка аккаунтов: " + (offline ? "выкл" : "вкл"));
	}

	/** Каждый тик клиента: держит режим проверки сервера в соответствии с настройкой, пока мир открыт для сети. */
	static void tick(Minecraft mc) {
		apply(mc);
	}

	private static void apply(Minecraft mc) {
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null) {
			applied = false;
			return;
		}
		if (offline && server.isPublished() && server.usesAuthentication()) {
			server.execute(() -> server.setUsesAuthentication(false));
			applied = true;
			notice("Сеть: проверка аккаунтов отключена");
		} else if (!offline && applied) {
			server.execute(() -> server.setUsesAuthentication(true));
			applied = false;
			notice("Сеть: проверка аккаунтов включена");
		}
	}

	/** Настройка из прежнего файла {@code buildmod_lan.txt} (когда переключатель был в строителе) переезжает в новый. */
	private static boolean migrate() {
		Path old = FabricLoader.getInstance().getConfigDir().resolve("buildmod_lan.txt");
		try {
			if (Files.exists(old) && !Files.exists(FILE)) Files.move(old, FILE);
		} catch (IOException ignored) {
		}
		return false;
	}

	private static void notice(String text) {
		Minecraft.getInstance().gui.hud.setOverlayMessage(Component.literal(text), false);
	}

	private static boolean load() {
		try {
			if (Files.exists(FILE)) return Boolean.parseBoolean(Files.readString(FILE, StandardCharsets.UTF_8).trim());
		} catch (IOException ignored) {
		}
		return false;
	}

	private static void save() {
		try {
			Files.writeString(FILE, Boolean.toString(offline), StandardCharsets.UTF_8);
		} catch (IOException ignored) {
		}
	}
}
