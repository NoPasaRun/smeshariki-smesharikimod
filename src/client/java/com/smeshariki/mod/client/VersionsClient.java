package com.smeshariki.mod.client;

import java.util.ArrayList;
import java.util.List;

import com.smeshariki.mod.versions.VersionsPayloads;

/** Список версий мира, присланный сервером (только хосту), и номер текущей. */
final class VersionsClient {
	static final VersionsClient INSTANCE = new VersionsClient();

	int head;
	/** Откат идёт без остановки мира; откат сейчас идёт. */
	boolean live, busy;
	List<VersionsPayloads.Versions.Info> versions = new ArrayList<>();

	private VersionsClient() {
	}
}
