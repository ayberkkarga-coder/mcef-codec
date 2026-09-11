package com.doomscroll.cef.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CinemaMod java-cef dal 6478 (Chromium 126) derlemesinde N_CreateBrowser JNI fonksiyonu extern "C" olmadan
 * derlenmis; disa aktarma tablosunda C++ karisik adla duruyor ve JVM bulamiyor. Bu sinif DLL'nin
 * export ad tablosunda karisik adi duz JNI adiyla degistirir ve tabloyu yeniden siralar
 * (GetProcAddress ikili arama yapar; tablo sirali olmak zorunda). Islem bir kez yapilir, idempotenttir.
 */
final class PeExportFix {
	private static final String MANGLED =
			"?Java_org_cef_browser_CefBrowser_1N_N_1CreateBrowser@@YAEPEAUJNIEnv_@@PEAV_jobject@@1_JPEAV_jstring@@EE11@Z";
	private static final String PLAIN = "Java_org_cef_browser_CefBrowser_1N_N_1CreateBrowser";

	private PeExportFix() {}

	/** jcef.dll yuklenmeden ONCE cagrilir. Duz sembol zaten varsa hicbir sey yapmaz. */
	static void apply(Path dll) {
		try {
			if (!Files.isRegularFile(dll)) {
				return;
			}
			byte[] data = Files.readAllBytes(dll);
			ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
			int pe = b.getInt(0x3C);
			if (b.getInt(pe) != 0x00004550) { // "PE\0\0"
				return;
			}
			int numSections = b.getShort(pe + 6) & 0xFFFF;
			int optSize = b.getShort(pe + 20) & 0xFFFF;
			int opt = pe + 24;
			int magic = b.getShort(opt) & 0xFFFF;
			int exportRva = b.getInt(opt + (magic == 0x20B ? 112 : 96));
			if (exportRva == 0) {
				return;
			}
			int secTable = opt + optSize;
			int[][] secs = new int[numSections][3]; // va, vsize, rawOffset
			for (int i = 0; i < numSections; i++) {
				int s = secTable + 40 * i;
				secs[i][0] = b.getInt(s + 12);
				secs[i][1] = Math.max(b.getInt(s + 8), b.getInt(s + 16));
				secs[i][2] = b.getInt(s + 20);
			}
			int exp = rvaToOffset(secs, exportRva);
			int numNames = b.getInt(exp + 24);
			int namesOff = rvaToOffset(secs, b.getInt(exp + 32));
			int ordsOff = rvaToOffset(secs, b.getInt(exp + 36));

			List<Object[]> entries = new ArrayList<>(); // {nameBytes, nameRva, ordinal}
			int mangledIdx = -1;
			boolean plainExists = false;
			for (int i = 0; i < numNames; i++) {
				int rva = b.getInt(namesOff + 4 * i);
				int off = rvaToOffset(secs, rva);
				int end = off;
				while (data[end] != 0) end++;
				byte[] name = Arrays.copyOfRange(data, off, end);
				String s = new String(name, StandardCharsets.US_ASCII);
				if (s.equals(PLAIN)) plainExists = true;
				if (s.equals(MANGLED)) mangledIdx = i;
				entries.add(new Object[]{name, rva, (int) (b.getShort(ordsOff + 2 * i) & 0xFFFF), off});
			}
			if (plainExists) {
				CefNatives.LOGGER.info("jcef.dll export tablosu zaten duzgun");
				return;
			}
			if (mangledIdx < 0) {
				CefNatives.LOGGER.warn("jcef.dll: ne duz ne karisik N_CreateBrowser sembolu var; yama atlandi");
				return;
			}
			// 1) adi yerinde degistir (duz ad daha kisa, kalan kisim sifirlanir)
			Object[] e = entries.get(mangledIdx);
			int off = (int) e[3];
			byte[] plain = PLAIN.getBytes(StandardCharsets.US_ASCII);
			int oldLen = ((byte[]) e[0]).length;
			System.arraycopy(plain, 0, data, off, plain.length);
			Arrays.fill(data, off + plain.length, off + oldLen + 1, (byte) 0);
			e[0] = plain;
			// 2) ad tablosunu (ve ordinal tablosunu) bayt sirasina gore yeniden sirala
			entries.sort((x, y) -> Arrays.compareUnsigned((byte[]) x[0], (byte[]) y[0]));
			for (int i = 0; i < numNames; i++) {
				b.putInt(namesOff + 4 * i, (int) entries.get(i)[1]);
				b.putShort(ordsOff + 2 * i, (short) ((int) entries.get(i)[2]));
			}
			Path tmp = dll.resolveSibling(dll.getFileName() + ".patch.tmp");
			Files.write(tmp, data);
			Files.move(tmp, dll, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			CefNatives.LOGGER.info("jcef.dll export tablosu yamalandi: N_CreateBrowser duz JNI adina cevrildi");
		} catch (IOException | RuntimeException ex) {
			CefNatives.LOGGER.error("jcef.dll export yamasi basarisiz", ex);
		}
	}

	private static int rvaToOffset(int[][] secs, int rva) {
		for (int[] s : secs) {
			if (rva >= s[0] && rva < s[0] + s[1]) {
				return rva - s[0] + s[2];
			}
		}
		throw new IllegalStateException("RVA bolum disinda: " + Integer.toHexString(rva));
	}
}
