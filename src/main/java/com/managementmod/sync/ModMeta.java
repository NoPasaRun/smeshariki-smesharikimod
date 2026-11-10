package com.managementmod.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.io.InputStreamReader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Данные из fabric.mod.json внутри jar-файла. */
public record ModMeta(String id, String version, String environment) {
	public static final ModMeta NONE = new ModMeta("", "", "*");

	public static ModMeta read(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) {
				return NONE;
			}
			try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				return new ModMeta(
					string(json, "id", ""),
					string(json, "version", ""),
					string(json, "environment", "*"));
			}
		} catch (IOException | RuntimeException e) {
			return NONE;
		}
	}

	private static String string(JsonObject json, String key, String fallback) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : fallback;
	}
}
