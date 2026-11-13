package com.managementmod.client;

import com.managementmod.ManagementMod;
import com.managementmod.sync.GitRelease;
import com.managementmod.sync.SyncConfig;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

/**
 * Установка с GitHub для того, у кого нет хоста (сам хост или игрок один): один раз за запуск игры, на экране заголовка, проверяется последний релиз
 * репозитория из {@code config/managementmod.json} ({@code gitRepo}). Есть что ставить — показывается то же окно подтверждения, что и при раздаче с хоста;
 * без согласия ничего не скачивается и не ставится. Сетевая ошибка только пишется в журнал.
 */
final class GitUpdate {
	private static boolean checked;

	private GitUpdate() {
	}

	static void tick(Minecraft mc) {
		if (checked || !(mc.gui.screen() instanceof TitleScreen title)) return;
		checked = true;
		SyncConfig config = SyncConfig.load();
		String repo = config.gitRepo == null ? "" : config.gitRepo.trim();
		if (repo.isEmpty() || !config.gitAutoCheck) return;
		String api = config.gitApiBase == null || config.gitApiBase.isBlank() ? "https://api.github.com" : config.gitApiBase.trim();
		var game = FabricLoader.getInstance().getGameDir();
		Thread thread = new Thread(() -> {
			try {
				GitRelease.Plan plan = GitRelease.fetch(api, repo, game);
				if (plan.entries().isEmpty() && plan.obsolete().isEmpty()) {
					ManagementMod.LOGGER.info("Установка с GitHub: {} {}, всё на месте", repo, plan.tag());
					return;
				}
				GitSession session = new GitSession(api, repo, plan, game);
				mc.execute(() -> {
					if (mc.gui.screen() == title) mc.gui.setScreen(new SyncScreen(session, title, () -> mc.gui.setScreen(title)));
				});
			} catch (Exception e) {
				ManagementMod.LOGGER.info("Установка с GitHub: проверка {} не удалась ({})", repo, e.toString());
			}
		}, "ManagementMod-Git-Check");
		thread.setDaemon(true);
		thread.start();
	}
}
