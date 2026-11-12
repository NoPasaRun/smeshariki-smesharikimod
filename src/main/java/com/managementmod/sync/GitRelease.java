package com.managementmod.sync;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Установка с GitHub: последний релиз репозитория {@code <владелец>/<имя>} содержит файл {@code manifest.json} и сами файлы (моды, шейдерпак, мир).
 * Класс не знает про Minecraft: только сеть, JSON и диск, поэтому его можно проверять отдельно.
 *
 * <p>Манифест: {@code {"files":[{"name":"levelmod-1.0.0.jar","category":"mod","size":123,"sha256":"…"}, {"name":"world.zip","category":"world","world":"Ромашковая Долина",…}]}}.
 * Категории: {@code mod}, {@code resourcepack}, {@code shaderpack}, {@code world}. Файл из манифеста берётся из ресурсов того же релиза по имени.
 *
 * <p>Скачивать разрешено только по https и только с узлов GitHub (адреса переадресации проверяются на каждом шаге); для проверок можно указать
 * свой {@code apiBase} на {@code http://localhost} или {@code http://127.0.0.1}. Каждый файл сверяется по SHA-256 из манифеста.
 */
public final class GitRelease {
	private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
	private static final Set<String> HOSTS = Set.of("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "raw.githubusercontent.com");
	private static final int MAX_HOPS = 6;

	/** Что поставить: файл манифеста, откуда его взять и (для мира) под каким именем лежит в {@code saves/}. */
	public record Entry(FileEntry file, String url, String world) {
	}

	/** Итог проверки релиза: метка релиза и то, чего на диске нет или что отличается. */
	public record Plan(String tag, List<Entry> entries) {
	}

	private GitRelease() {
	}

	private static String hostOf(URI uri) {
		return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
	}

	private static boolean local(String host) {
		return host.equals("localhost") || host.equals("127.0.0.1");
	}

	/** Можно ли ходить по этому адресу: https и узлы GitHub; для проверок — свой {@code apiBase} на локальном узле. */
	static boolean allowed(URI uri, URI apiBase) {
		String host = hostOf(uri);
		if (host.isEmpty()) return false;
		if ("https".equals(uri.getScheme()) && HOSTS.contains(host)) return true;
		return local(hostOf(apiBase)) && host.equals(hostOf(apiBase)) && uri.getPort() == apiBase.getPort();
	}

	private static HttpClient client() {
		return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
	}

	/** Открывает адрес, сам идя по переадресациям (каждый шаг проверяется по списку узлов). */
	private static HttpResponse<InputStream> open(HttpClient client, URI first, URI apiBase) throws IOException, InterruptedException {
		URI uri = first;
		for (int hop = 0; hop < MAX_HOPS; hop++) {
			if (!allowed(uri, apiBase)) throw new IOException("адрес не разрешён: " + uri.getHost());
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).header("User-Agent", "ManagementMod").header("Accept", "application/vnd.github+json, application/octet-stream, */*").GET().build();
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			int code = response.statusCode();
			if (code >= 300 && code < 400) {
				String location = response.headers().firstValue("Location").orElseThrow(() -> new IOException("переадресация без адреса"));
				response.body().close();
				uri = uri.resolve(location);
				continue;
			}
			if (code != 200) {
				response.body().close();
				throw new IOException("ответ " + code + " от " + uri.getHost());
			}
			return response;
		}
		throw new IOException("слишком много переадресаций");
	}

	private static String text(HttpClient client, URI uri, URI apiBase, long limit) throws IOException, InterruptedException {
		try (InputStream in = open(client, uri, apiBase).body()) {
			byte[] data = in.readNBytes((int) limit + 1);
			if (data.length > limit) throw new IOException("ответ слишком большой");
			return new String(data, StandardCharsets.UTF_8);
		}
	}

	private static SyncCategory category(String name) {
		return switch (name == null ? "" : name) {
			case "mod" -> SyncCategory.MOD;
			case "resourcepack" -> SyncCategory.RESOURCE_PACK;
			case "shaderpack" -> SyncCategory.SHADER_PACK;
			case "world" -> SyncCategory.WORLD;
			default -> null;
		};
	}

	/** Имя мира для папки {@code saves/}: простое имя, без путей и скрытых папок. */
	static boolean safeWorldName(String name) {
		if (name == null || name.isEmpty() || name.length() > 100 || name.startsWith(".") || name.endsWith(" ") || name.endsWith(".")) return false;
		for (int i = 0; i < name.length(); i++) if (name.charAt(i) < 32 || "/\\:*?\"<>|".indexOf(name.charAt(i)) >= 0) return false;
		return true;
	}

	private static String hex(byte[] data) {
		StringBuilder b = new StringBuilder();
		for (byte x : data) b.append(String.format("%02x", x));
		return b.toString();
	}

	/** SHA-256 файла на диске. */
	public static String sha256(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
			return hex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

	/**
	 * Читает последний релиз и составляет список того, что надо поставить: файла нет на диске или его сумма отличается; мир ставится, только если папки
	 * с таким именем в {@code saves/} ещё нет (чужой мир не трогаем).
	 */
	public static Plan fetch(String apiBase, String repo, Path gameDir) throws IOException, InterruptedException {
		if (repo == null || !REPO.matcher(repo).matches()) throw new IOException("неверное имя репозитория: " + repo);
		URI base = URI.create(apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase);
		HttpClient client = client();
		JsonObject release = JsonParser.parseString(text(client, URI.create(base + "/repos/" + repo + "/releases/latest"), base, 1 << 20)).getAsJsonObject();
		String tag = release.has("tag_name") ? release.get("tag_name").getAsString() : "";
		Map<String, String> urls = new HashMap<>();
		for (JsonElement e : release.getAsJsonArray("assets")) {
			JsonObject a = e.getAsJsonObject();
			urls.put(a.get("name").getAsString(), a.get("browser_download_url").getAsString());
		}
		String manifestUrl = urls.get("manifest.json");
		if (manifestUrl == null) throw new IOException("в релизе нет manifest.json");
		JsonObject manifest = JsonParser.parseString(text(client, URI.create(manifestUrl), base, 1 << 20)).getAsJsonObject();
		JsonArray files = manifest.getAsJsonArray("files");
		List<Entry> entries = new ArrayList<>();
		for (JsonElement element : files) {
			JsonObject f = element.getAsJsonObject();
			SyncCategory category = category(f.has("category") ? f.get("category").getAsString() : null);
			if (category == null) continue;
			String name = f.get("name").getAsString();
			FileEntry file = new FileEntry(category, name, f.get("size").getAsLong(), f.get("sha256").getAsString().toLowerCase(Locale.ROOT), "", tag);
			if (!file.isValid()) throw new IOException("неверная запись в манифесте: " + name);
			String url = urls.get(name);
			if (url == null) throw new IOException("в релизе нет файла " + name);
			String world = "";
			if (category == SyncCategory.WORLD) {
				world = f.has("world") ? f.get("world").getAsString() : "";
				if (!safeWorldName(world)) throw new IOException("неверное имя мира в манифесте");
				if (Files.exists(gameDir.resolve("saves").resolve(world))) continue;
			} else {
				Path local = gameDir.resolve(category.dir).resolve(name);
				if (Files.isRegularFile(local) && sha256(local).equals(file.sha256())) continue;
			}
			entries.add(new Entry(file, url, world));
		}
		return new Plan(tag, entries);
	}

	/** Скачивает файл в {@code target}, возвращает SHA-256 скачанного; {@code progress} получает число байт, пришедших за раз. */
	public static String download(String apiBase, String url, Path target, LongConsumer progress) throws IOException, InterruptedException {
		URI base = URI.create(apiBase);
		try (InputStream in = open(client(), URI.create(url), base).body(); java.io.OutputStream out = Files.newOutputStream(target)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0; ) {
				if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
				digest.update(buffer, 0, n);
				out.write(buffer, 0, n);
				progress.accept(n);
			}
			return hex(digest.digest());
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}
}
