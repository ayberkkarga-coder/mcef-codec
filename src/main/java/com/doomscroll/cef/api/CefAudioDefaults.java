package com.doomscroll.cef.api;

/**
 * Fork'un JNI koprusu ses parametrelerini (ornekleme hizi) iletmiyor; CEF sistem cikisinin
 * hizini kullanir (Windows'ta genelde 48000). Farkli bir cihazda ses bozuk/hizli gelirse buradan degistirilir.
 */
public final class CefAudioDefaults {
	/** 0 = otomatik olc (1.5 sn boyunca gelen frame/saniye). Sabit deger vermek icin Hz yaz. */
	public static volatile int sampleRate = 0;

	private CefAudioDefaults() {}

	/**
	 * Halka tampon hedefi (ms). Okuyucu bu civarda tutar: kucuk = az gecikme ama takilma riski,
	 * buyuk = guvenli. 20..200. Degisiklik aninda etkilidir.
	 */
	public static volatile int targetBacklogMs = 60;
}
