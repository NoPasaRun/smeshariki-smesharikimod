package com.managementmod;

import com.managementmod.sync.ServerSync;
import com.managementmod.versions.VcsServer;
import com.managementmod.versions.VersionsPayloads;

import net.fabricmc.api.ModInitializer;

/** Управление игрой и миром: раздача модов клиентам при входе, версии мира (снимки и откат), переключатель проверки аккаунтов в сети (клиент). */
public class ManagementMod implements ModInitializer {
	public static final String ID = "managementmod";
	public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ID);

	@Override
	public void onInitialize() {
		ServerSync.init();
		VersionsPayloads.register();
		VcsServer.init();
	}
}
