package com.doomscroll.cef.api;

/** CEF baslatilmadan ONCE ayarlanmasi gereken secenekler. */
public final class CefLaunchOptions {
	/** Bos ise Chromium varsayilani. Google girisi gibi "gomulu tarayici" engelleri icin guncel bir Chrome kimligi verilir. */
	public static volatile String userAgent = "";

	/** Calisma zamaninda degistirilebilir: null degilse her istegin User-Agent basligi bu olur, Client-Hints silinir. */
	public static volatile String headerUserAgentOverride = null;

	/** Tarayici boyama kare hizi (1..60, CEF ust siniri 60). Tarayici acilirken okunur; sonra CefBrowserView.setFrameRate. */
	public static volatile int frameRate = 60;

	/** Alan adi tabanli reklam/izleyici engelleyici (AdBlock). Calisma zamaninda degistirilebilir. */
	public static volatile boolean adBlock = true;

	/**
	 * doomscroll:// yerel sayfalar: adres -> HTML (null = 404). CEF IO is parcaciginda cagrilir; mod ana menuleri
	 * (doomscroll://home/...) buradan uretir. CEF baslamadan once ayarlanmali.
	 */
	public static volatile java.util.function.Function<String, String> localPages = null;

	private CefLaunchOptions() {}
}
