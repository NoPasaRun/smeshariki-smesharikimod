package com.installermod;

import com.installermod.sync.ServerSync;
import net.fabricmc.api.ModInitializer;

public class InstallerMod implements ModInitializer {
	@Override
	public void onInitialize() {
		ServerSync.init();
	}
}
