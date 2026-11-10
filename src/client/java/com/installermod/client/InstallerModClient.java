package com.installermod.client;

import net.fabricmc.api.ClientModInitializer;

public class InstallerModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		SyncClient.init();
	}
}
