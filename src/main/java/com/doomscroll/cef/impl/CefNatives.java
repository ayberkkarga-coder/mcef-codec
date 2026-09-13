package com.doomscroll.cef.impl;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Downloads, verifies and extracts CinemaMod's codec-enabled java-cef binaries.
 * Layout: config/mcef-codec/libraries/<platform>/ (jcef.path points here).
 */
public final class CefNatives {
	static final Logger LOGGER = LoggerFactory.getLogger("mcef-codec");

	/** CinemaMod java-cef branch 6478 (Chromium 126, October 2024). Previous: eaeb3d4 (CEF 116). */
	public static final String JAVA_CEF_COMMIT = "81dba0ec8425dd746698cee72c686cc1dcabd85a"; // CinemaMod branch 6478: CEF 126.2.19 / Chromium 126, with codecs
	public static final String MIRROR = "https://mcef-download.cinemamod.com";

	public static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("mcef-codec");
	public static final Path LIBRARIES = DIR.resolve("libraries");
	public static final Path CACHE = DIR.resolve("cache");

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

	private CefNatives() {}

	public static String platform() {
		String os = System.getProperty("os.name").toLowerCase(Locale.US);
		String arch = System.getProperty("os.arch").toLowerCase(Locale.US);
		String o = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : "linux";
		String a = (arch.equals("aarch64") || arch.equals("arm64")) ? "arm64" : "amd64";
		return o + "_" + a;
	}

	public static Path platformDir() {
		return LIBRARIES.resolve(platform());
	}

	private static String url(String suffix) {
		return MIRROR + "/java-cef-builds/" + JAVA_CEF_COMMIT + "/" + platform() + suffix;
	}

	/**
	 * Gets the binaries ready. Called on a background thread.
	 * Downloads nothing if the remote sha256 matches the local sha256 and the folder is not empty.
	 */
	public static void ensure(DoubleConsumer progress, java.util.function.Consumer<String> stage) throws IOException, InterruptedException {
		Files.createDirectories(LIBRARIES);
		Files.createDirectories(CACHE);

		Path shaFile = LIBRARIES.resolve(platform() + ".tar.gz.sha256");
		stage.accept("DOWNLOADING");
		String remoteSha = fetchText(url(".tar.gz.sha256")).trim();
		String localSha = Files.isRegularFile(shaFile) ? Files.readString(shaFile).trim() : "";
		boolean installed = Files.isDirectory(platformDir()) && Files.list(platformDir()).findAny().isPresent();

		if (installed && remoteSha.equals(localSha)) {
			LOGGER.info("java-cef binaries are up to date ({})", platformDir());
			return;
		}

		Path tar = LIBRARIES.resolve(platform() + ".tar.gz");
		download(url(".tar.gz"), tar, progress);

		// Integrity: pull the 64-hex-digit digest out of the mirror's sha256 file and compare it with the downloaded archive
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9a-fA-F]{64}").matcher(remoteSha);
		if (!m.find()) {
			Files.deleteIfExists(tar);
			throw new IOException("no digest found in the mirror sha256 file: " + url(".tar.gz.sha256"));
		}
		String expected = m.group().toLowerCase();
		String actual = sha256Hex(tar);
		if (!expected.equals(actual)) {
			Files.deleteIfExists(tar);
			throw new IOException("java-cef archive is corrupt or has been tampered with: expected " + expected + ", got " + actual);
		}
		LOGGER.info("java-cef archive verified (sha256 {})", expected.substring(0, 12));

		// Version change: clear the platform folder so leftovers from the old binaries cannot conflict
		if (Files.isDirectory(platformDir())) {
			try (java.util.stream.Stream<Path> walk = Files.walk(platformDir())) {
				walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
					try { Files.deleteIfExists(p); } catch (IOException ignored) { }
				});
			}
		}

		stage.accept("EXTRACTING");
		extract(tar, LIBRARIES, progress);
		Files.deleteIfExists(tar);
		Files.writeString(shaFile, remoteSha, StandardCharsets.UTF_8);
		LOGGER.info("java-cef binaries installed: {}", platformDir());
	}

	private static String sha256Hex(Path file) throws IOException {
		try {
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			try (InputStream in = Files.newInputStream(file)) {
				byte[] buf = new byte[1 << 16];
				int n;
				while ((n = in.read(buf)) > 0) {
					md.update(buf, 0, n);
				}
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
	}

		private static String fetchText(String url) throws IOException, InterruptedException {
		HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "MCEF").GET().build(),
				HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + r.statusCode() + " - " + url);
		}
		return r.body();
	}

	private static void download(String url, Path target, DoubleConsumer progress) throws IOException, InterruptedException {
		Path part = target.resolveSibling(target.getFileName() + ".part");
		HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "MCEF").GET().build(),
				HttpResponse.BodyHandlers.ofInputStream());
		if (r.statusCode() / 100 != 2) {
			throw new IOException("HTTP " + r.statusCode() + " - " + url);
		}
		long total = r.headers().firstValueAsLong("Content-Length").orElse(-1L);
		long done = 0;
		try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				done += n;
				if (total > 0) {
					progress.accept((double) done / total);
				}
			}
		}
		Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
		LOGGER.info("downloaded: {} ({} MB)", target.getFileName(), done / 1_048_576);
	}

	private static void extract(Path tarGz, Path outDir, DoubleConsumer progress) throws IOException {
		long size = Files.size(tarGz);
		long read = 0;
		try (TarArchiveInputStream tar = new TarArchiveInputStream(new GzipCompressorInputStream(Files.newInputStream(tarGz)))) {
			TarArchiveEntry e;
			while ((e = tar.getNextEntry()) != null) {
				if (e.isDirectory()) {
					continue;
				}
				Path out = outDir.resolve(e.getName()).normalize();
				if (!out.startsWith(outDir)) {
					throw new IOException("suspicious tar entry: " + e.getName());
				}
				Files.createDirectories(out.getParent());
				try (OutputStream os = Files.newOutputStream(out)) {
					byte[] buf = new byte[1 << 16];
					int n;
					while ((n = tar.read(buf)) > 0) {
						os.write(buf, 0, n);
						read += n;
						progress.accept(Math.min(1.0, (double) read / (size * 2.6)));
					}
				}
			}
		}
	}
}
