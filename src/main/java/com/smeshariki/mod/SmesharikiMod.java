package com.smeshariki.mod;

import com.smeshariki.mod.sync.ServerSync;
import com.smeshariki.mod.versions.VcsServer;
import com.smeshariki.mod.versions.VersionsPayloads;

import net.fabricmc.api.ModInitializer;

/** Управление игрой и миром: раздача модов клиентам при входе, версии мира (снимки и откат), переключатель проверки аккаунтов в сети (клиент). */
public class SmesharikiMod implements ModInitializer {
	public static final String ID = "smesharikimod";
	public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ID);

	@Override
	public void onInitialize() {
		ServerSync.init();
		VersionsPayloads.register();
		VcsServer.init();
	}
}
