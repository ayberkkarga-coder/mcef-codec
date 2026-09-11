package com.doomscroll.cef.api;

import com.doomscroll.cef.impl.CefRuntime;

import java.util.concurrent.CompletableFuture;

/**
 * Giris noktasi. {@link #initialize()} asenkron calisir: once Chromium ikilileri indirilir/dogrulanir,
 * sonra render is parcaciginda CEF baslatilir. Hazir olunca {@link Initialization#isDone()} true doner.
 */
public interface CefService {

	static Initialization initialize() {
		return CefRuntime.initialize();
	}

	static CompletableFuture<CefService> getInstanceFuture() {
		return initialize().getFuture();
	}

	/** Yeni tarayici. Sadece render is parcaciginda ve hazir olduktan sonra cagrilmali. */
	CefBrowserView createBrowser(String url, boolean transparent);

	/** Sayfa disi is: mesaj dongusunu pompalar (mixin cagirir). */
	void pump();

	/** Son cagridan bu yana mesaj dongusu maliyeti: ort. ms/kare ve tarayici sayisi. Sayaclari sifirlar. */
	String perfInfo();

	/** Reklam engelleyici durumu: acik/kapali, liste boyutu, engellenen istek sayisi. */
	String adBlockInfo();

	interface Initialization {
		Stage getStage();

		/** 0..100 ya da bilinmiyorsa -1 */
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
