package com.doomscroll.cef.api;

import com.doomscroll.cef.impl.CefRuntime;

import java.util.concurrent.CompletableFuture;

/**
 * Entry point. {@link #initialize()} runs asynchronously: first the Chromium binaries are downloaded/verified,
 * then CEF is started on the render thread. Once it is ready, {@link Initialization#isDone()} returns true.
 */
public interface CefService {

	static Initialization initialize() {
		return CefRuntime.initialize();
	}

	static CompletableFuture<CefService> getInstanceFuture() {
		return initialize().getFuture();
	}

	/** New browser. Must only be called on the render thread, and only once the service is ready. */
	default CefBrowserView createBrowser(String url, boolean transparent) {
		return createBrowser(url, transparent, false);
	}

	/**
	 * New browser.
	 *
	 * @param ephemeral if true, cookies and the session are kept in a shared context that is not written to disk
	 *                  and is separate from the persistent profile. This keeps a page someone else opened from running in
	 *                  the same context as the session you are signed in to.
	 */
	CefBrowserView createBrowser(String url, boolean transparent, boolean ephemeral);

	/** Work outside the pages: pumps the message loop (called by the mixin). */
	void pump();

	/** Message loop cost since the last call: avg. ms/frame and browser count. Resets the counters. */
	String perfInfo();

	/** Ad blocker status: on/off, list size, number of blocked requests. */
	String adBlockInfo();

	interface Initialization {
		Stage getStage();

		/** 0..100, or -1 if unknown */
		float getPercentage();

		CompletableFuture<CefService> getFuture();

		default boolean isDone() {
			return getStage() == Stage.DONE;
		}

		enum Stage {
			NOT_STARTED,
			DOWNLOADING,
			EXTRACTING,
			INITIALIZING,
			DONE,
			FAILED
		}
	}
}
