package com.doomscroll.cef.api;

/** Options that must be set BEFORE CEF is started. */
public final class CefLaunchOptions {
	/** Empty = Chromium default. A current Chrome identity is set here to get past "embedded browser" blocks such as Google sign-in. */
	public static volatile String userAgent = "";

	/** Can be changed at runtime: if not null, every request's User-Agent header becomes this value and the Client-Hints headers are removed. */
	public static volatile String headerUserAgentOverride = null;

	/** Browser paint frame rate (1..60, CEF's upper limit is 60). Read when a browser is opened; after that, use CefBrowserView.setFrameRate. */
	public static volatile int frameRate = 60;

	/** Domain-based ad/tracker blocker (AdBlock). Can be changed at runtime. */
	public static volatile boolean adBlock = true;

	/**
	 * doomscroll:// local pages: URL -> HTML (null = 404). Called on the CEF IO thread; the mod generates its main menus
	 * (doomscroll://home/...) from here. Must be set before CEF starts.
	 */
	public static volatile java.util.function.Function<String, String> localPages = null;

	private CefLaunchOptions() {}
}
