package com.managementmod.client;

import java.util.ArrayList;
import java.util.List;

import com.managementmod.versions.VersionsPayloads;

/** Список версий мира, присланный сервером (только хосту), и номер текущей. */
final class VersionsClient {
	static final VersionsClient INSTANCE = new VersionsClient();

	int head;
	List<VersionsPayloads.Versions.Info> versions = new ArrayList<>();

	private VersionsClient() {
	}
}
