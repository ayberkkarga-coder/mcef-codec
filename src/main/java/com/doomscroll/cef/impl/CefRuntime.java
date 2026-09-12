package com.doomscroll.cef.impl;

import com.doomscroll.cef.api.CefBrowserView;
import com.doomscroll.cef.api.CefService;
import net.minecraft.client.Minecraft;
import org.cef.CefApp;
import org.cef.CefClient;
import org.jetbrains.annotations.Nullable;
import org.cef.handler.CefLoadHandler.ErrorCode;
import org.cef.CefSettings;
import org.cef.handler.CefDisplayHandlerAdapter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * CEF yasam dongusu: ikilileri indir (arka plan) -> render is parcaciginda baslat -> her karede pompala -> kapat.
 */
public final class CefRuntime implements CefService {
	/**
	 * Runs in every http(s) frame as soon as its document is committed, before the page's own scripts. Gives every
	 * iframe the fullscreen / autoplay / encrypted-media / picture-in-picture permissions (Chromium reads the
	 * allow attributes when the child document is created, so setting them later has no effect until the iframe
	 * reloads; embedded players on film sites are usually in the initial HTML). Without this the player's own
	 * fullscreen button does nothing.
	 */
	static final String EARLY_JS = "(function(){if(window.__dsEarly)return;window.__dsEarly=1;"
			+ "var NEED=['fullscreen','autoplay','encrypted-media','picture-in-picture'];"
			+ "var fix=function(f){try{if(!f||f.tagName!=='IFRAME')return;if(!f.hasAttribute('allowfullscreen'))f.setAttribute('allowfullscreen','');"
			+ "var a=f.getAttribute('allow')||'',add='';for(var i=0;i<NEED.length;i++){if(a.indexOf(NEED[i])<0)add+=((a||add)?'; ':'')+NEED[i];}if(add)f.setAttribute('allow',a+add);}catch(e){}};"
			+ "var scan=function(n){if(!n||n.nodeType!==1)return;fix(n);if(n.querySelectorAll){var l=n.querySelectorAll('iframe');for(var i=0;i<l.length;i++)fix(l[i]);}};"
			+ "scan(document.documentElement);"
			+ "try{new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type==='attributes'){fix(m.target);continue;}var an=m.addedNodes;for(var j=0;j<an.length;j++)scan(an[j]);}})"
			+ ".observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['allow','allowfullscreen']});}catch(e){}"
			+ "})();";

	private static final Object LOCK = new Object();
	private static Init init;
	private static CefRuntime instance;

	private final CefApp app;
	private final CefClient client;
	private final List<OsrBrowser> browsers = new java.util.concurrent.CopyOnWriteArrayList<>();
	private static final List<OsrBrowser> ALL_BROWSERS = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Basarisiz kaynak yuklemelerini (HTTP >= 400 ya da ag hatasi) loglar; site neden oynatmiyor teshisi icin. */
	private static final org.cef.handler.CefResourceRequestHandlerAdapter RESOURCE_LOGGER = new org.cef.handler.CefResourceRequestHandlerAdapter() {
		/** Calisma zamaninda kimlik degisimi: User-Agent basligini degistirir, Chromium Client-Hints basliklarini siler (yeniden baslatma gerekmez). */
		@Override
		public boolean onBeforeResourceLoad(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest request) {
			// Reklam filtreleri (EasyList/AdGuard/hosts): istek hic cikmasin
			org.cef.network.CefRequest.ResourceType rtype = request.getResourceType();
			boolean mainFrame = rtype == org.cef.network.CefRequest.ResourceType.RT_MAIN_FRAME;
			String pageUrl = mainFrame ? request.getReferrerURL() : (frame != null ? frame.getURL() : request.getReferrerURL());
			if (AdBlock.shouldBlock(request.getURL(), rtype == null ? "" : rtype.name(), mainFrame, pageUrl)) {
				return true;
			}
			String ua = com.doomscroll.cef.api.CefLaunchOptions.headerUserAgentOverride;
			if (ua != null && !ua.isBlank()) {
				java.util.Map<String, String> h = new java.util.HashMap<>();
				request.getHeaderMap(h);
				h.keySet().removeIf(k -> k != null && (k.equalsIgnoreCase("User-Agent") || k.toLowerCase().startsWith("sec-ch-ua")));
				h.put("User-Agent", ua);
				request.setHeaderMap(h);
			}
			return false;
		}

		@Override
		public void onResourceLoadComplete(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest request,
				org.cef.network.CefResponse response, org.cef.network.CefURLRequest.Status status, long receivedContentLength) {
			int code = response == null ? -1 : response.getStatus();
			if (code >= 400 || (status != org.cef.network.CefURLRequest.Status.UR_SUCCESS && status != org.cef.network.CefURLRequest.Status.UR_CANCELED)) {
				String url = request == null ? "?" : request.getURL();
				if (url.length() > 140) url = url.substring(0, 140) + "...";
				CefNatives.LOGGER.warn("[res {} {}] {}", status, code, url);
			}
		}
	};

	private CefRuntime(CefApp app, CefClient client) {
		this.app = app;
		this.client = client;
	}

	public static Initialization initialize() {
		synchronized (LOCK) {
			if (init == null) {
				init = new Init();
				init.start();
			}
			return init;
		}
	}

	static CefRuntime instance() {
		return instance;
	}

	/**
	 * Ekranlarin paylastigi gecici istek baglami: cerezler bellekte kalir, diske yazilmaz.
	 * Ekran basina ayri baglam verilebilirdi ama CEF her baglam icin ayri bir render sureci
	 * acar; onlarca ekranda bu makineyi dize getirir. Tek ortak gecici baglam,
	 * "tabletimdeki oturumum baskasinin actigi sayfayla karismasin" isini goruyor.
	 */
	private static volatile org.cef.browser.CefRequestContext ephemeralContext;

	private static org.cef.browser.CefRequestContext ephemeralContext() {
		org.cef.browser.CefRequestContext c = ephemeralContext;
		if (c == null) {
			synchronized (LOCK) {
				c = ephemeralContext;
				if (c == null) {
					try {
						c = org.cef.browser.CefRequestContext.createContext(null);
					} catch (Throwable t) {
						CefNatives.LOGGER.warn("gecici istek baglami olusturulamadi, ortak profil kullanilacak: {}", t.toString());
						c = null;
					}
					ephemeralContext = c;
				}
			}
		}
		return c;
	}

	@Override
	public CefBrowserView createBrowser(String url, boolean transparent, boolean ephemeral) {
		OsrBrowser b = new OsrBrowser(this, client, url, transparent, ephemeral ? ephemeralContext() : null);
		b.setCloseAllowed();
		b.createImmediately();
		// CEF, "gizli" saydigi OSR tarayicisinda icerigi rasterlestirmez (sadece arka plan).
		// Her tarayiciyi acikca gorunur isaretle ki metin/video cizilsin.
		b.setWindowVisibility(true);
		b.resize(1280, 720);
		synchronized (browsers) {
			browsers.add(b);
			ALL_BROWSERS.add(b);
		}
		return b;
	}

	/** Tum calisan runtime'lardaki tarayicilar arasinda CefBrowser esini bulur (ses handler'i icin). */
	static @Nullable OsrBrowser findBrowser(org.cef.browser.CefBrowser browser) {
		if (browser == null) {
			return null; // kapanan tarayicinin son ses geri cagrilari null gelebiliyor
		}
		for (OsrBrowser b : ALL_BROWSERS) {
			if (b == browser || b.getIdentifier() == browser.getIdentifier()) {
				return b;
			}
		}
		return null;
	}

	void forget(OsrBrowser b) {
		synchronized (browsers) {
			browsers.remove(b);
			ALL_BROWSERS.remove(b);
		}
	}

	private long pumpNanos = 0L;
	private long pumps = 0L;
	private long perfStart = System.nanoTime();

	/** Iki adresin kayitli alan adi (son iki etiket) ayni mi? Bos/gecersiz adreste false. */
	static boolean sameSite(String a, String b) {
		String ha = hostOf(a), hb = hostOf(b);
		if (ha.isEmpty() || hb.isEmpty()) return false;
		return tail(ha).equals(tail(hb));
	}

	private static String hostOf(String url) {
		try {
			String h = java.net.URI.create(url).getHost();
			return h == null ? "" : h.toLowerCase(java.util.Locale.ROOT);
		} catch (Exception e) {
			return "";
		}
	}

	private static String tail(String host) {
		String[] parts = host.split("\\.");
		int n = parts.length;
		return n >= 2 ? parts[n - 2] + "." + parts[n - 1] : host;
	}

	@Override
	public void pump() {
		long t0 = System.nanoTime();
		app.N_DoMessageLoopWork();
		pumpNanos += System.nanoTime() - t0;
		pumps++;
	}

	@Override
	public String adBlockInfo() {
		return AdBlock.info();
	}

	@Override
	public String perfInfo() {
		long now = System.nanoTime();
		double sec = Math.max(1e-3, (now - perfStart) / 1e9);
		String out = String.format(java.util.Locale.ROOT, "CEF pompa (boyama+olaylar, render is parcacigi): %.2f ms/kare, %.0f kare/sn, %d tarayici",
				pumps == 0 ? 0.0 : pumpNanos / 1e6 / pumps, pumps / sec, ALL_BROWSERS.size());
		pumpNanos = 0L;
		pumps = 0L;
		perfStart = now;
		return out;
	}

	/** Oyun kapanirken: tarayicilari kapat, CEF'i durdur, artik jcef_helper kalmasin. */
	public static void shutdown() {
		CefRuntime r = instance;
		if (r == null) {
			return;
		}
		instance = null;
		List<OsrBrowser> copy;
		synchronized (r.browsers) {
			copy = new ArrayList<>(r.browsers);
			r.browsers.clear();
		}
		for (OsrBrowser b : copy) {
			try {
				b.close();
			} catch (Exception ignored) {
			}
		}
		try {
			r.client.dispose();
			r.app.dispose();
		} catch (Exception e) {
			CefNatives.LOGGER.warn("CEF kapatilirken hata", e);
		}
	}

	/** Windows: cokmus/askida kalan jcef_helper surecleri (MCEF 2.x ile ayni yaklasim). */
	public static void killLingeringHelpers() {
		if (!CefNatives.platform().startsWith("windows")) {
			return;
		}
		try {
			Process p = new ProcessBuilder("tasklist").start();
			boolean running = false;
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.contains("jcef_helper.exe")) {
						running = true;
						break;
					}
				}
			}
			if (running) {
				CefNatives.LOGGER.warn("jcef_helper.exe hala calisiyor, kapatiliyor");
				new ProcessBuilder("taskkill", "/F", "/IM", "jcef_helper.exe").start();
			}
		} catch (Exception e) {
			CefNatives.LOGGER.warn("jcef_helper kontrolu basarisiz", e);
		}
	}

	/** Render is parcaciginda calisir. */
	private static CefRuntime startCef() {
		System.setProperty("jcef.path", CefNatives.platformDir().toAbsolutePath().toString());

		// GPU ACIK (CinemaMod gibi). Asil siyah-ekran sebebi GPU degil, kare akmamasiydi;
		// onu driveFrames()/invalidate() cozuyor. GPU'nun rasterlestiricisi metin/resim/video'yu cizer.
		// CinemaMod/MCEF ile ayni ayar seti (bu binariler onlarla calisiyor).
		String[] switches = new String[]{
				"--autoplay-policy=no-user-gesture-required",
				"--enable-widevine-cdm",
				"--disable-blink-features=AutomationControlled" // Google "guvenli degil" engeli icin otomasyon izini kapat
		};
		// Dal 6478 derlemesindeki bozuk export adini DLL yuklenmeden once duzelt (idempotent)
		PeExportFix.apply(CefNatives.platformDir().resolve("jcef.dll"));
		if (!CefApp.startup(switches)) {
			throw new IllegalStateException("CefApp.startup basarisiz");
		}

		org.cef.CefApp.addAppHandler(new org.cef.handler.CefAppHandlerAdapter(switches) {
			@Override
			public void onBeforeCommandLineProcessing(String processType, org.cef.callback.CefCommandLine cmd) {
				cmd.appendSwitchWithValue("autoplay-policy", "no-user-gesture-required");
			}

			@Override
			public void onRegisterCustomSchemes(org.cef.callback.CefSchemeRegistrar registrar) {
				// doomscroll://home/... yerel sayfalar (ana menu): standart, guvenli, CORS + fetch acik
				registrar.addCustomScheme("doomscroll", true, false, false, true, true, false, true);
			}

			@Override
			public void onContextInitialized() {
				CefApp.getInstance().registerSchemeHandlerFactory("doomscroll", "",
						(browser, frame, scheme, request) -> new LocalPageHandler());
			}
		});

		CefSettings settings = new CefSettings();
		settings.windowless_rendering_enabled = true;
		settings.log_file = CefNatives.DIR.resolve("cef.log").toAbsolutePath().toString();
		settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_INFO;
		settings.cache_path = CefNatives.CACHE.toAbsolutePath().toString();
		settings.background_color = settings.new ColorType(255, 0, 0, 0);
		// user_agent_product bilerek bos: "MCEF/2" eki bazi CDN bot filtrelerine takiliyordu (script 403)
		String ua = com.doomscroll.cef.api.CefLaunchOptions.userAgent;
		if (ua != null && !ua.isBlank()) {
			settings.user_agent = ua;
		}

		AdBlock.init(); // reklam listesi (disk/indirme) arka planda
		CefApp app = CefApp.getInstance(switches, settings);
		CefClient client = app.createClient();
		client.addDisplayHandler(new CefDisplayHandlerAdapter() {
			@Override
			public boolean onConsoleMessage(org.cef.browser.CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
				// Sayfa -> Java mesaj kanali (durum raporu vb.)
				if (message != null && message.startsWith("__DSB__")) {
					OsrBrowser b = findBrowser(browser);
					if (b != null) {
						b.dispatchChunk(message.substring(7));
					}
					return true;
				}
				if (message != null && message.startsWith("__DS__")) {
					OsrBrowser b = findBrowser(browser);
					if (b != null) {
						b.dispatchMessage(message.substring(6));
					}
					return true;
				}
				// Sayfa icindeki izleyici (doomscroll otomatik gecis) sonraki video istedi -> guvenilir ok tusu gonder
				if ("__DS_NEXT__".equals(message)) {
					CefNatives.LOGGER.info("otomatik gecis: sonraki video (sayfa ici)");
					return true;
				}
				// Uyari/hatalari MC log'una yaz (site neden oynatmiyor gibi sorunlarda tek ipucu bu)
				if (message != null && message.contains("frame is sandboxed")) {
					return true; // reklam iframe'lerinin sandbox uyarisi: gurultu
				}
				if (level == CefSettings.LogSeverity.LOGSEVERITY_ERROR || level == CefSettings.LogSeverity.LOGSEVERITY_WARNING || level == CefSettings.LogSeverity.LOGSEVERITY_FATAL) {
					String src = source == null ? "" : source;
					if (src.length() > 90) src = "..." + src.substring(src.length() - 90);
					CefNatives.LOGGER.warn("[js {}] {} ({}:{})", level, message, src, line);
				}
				return true;
			}

			@Override
			public void onFullscreenModeChange(org.cef.browser.CefBrowser browser, boolean fullscreen) {
				// OSR'de pencere yok; sayfa kendi icinde tam ekran duzenine gecer (video gorunumu kaplar)
				CefNatives.LOGGER.info("[fullscreen] sayfa tam ekran: {}", fullscreen);
			}
		});
		// Yeni pencere/popup: OSR'de acilamaz. Ayni sitedeki hedef ayni tarayicida acilir, yabanci (reklam) engellenir.
		client.addLifeSpanHandler(new org.cef.handler.CefLifeSpanHandlerAdapter() {
			@Override
			public boolean onBeforePopup(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, String targetUrl, String targetFrameName) {
				// Hicbir popup kendiliginden acilmaz (film sitelerinde ayni siteden yonlendirme de reklam olabiliyor).
				// Adres dinleyen tarafa bildirilir; istenirse komutla acilir.
				CefNatives.LOGGER.info("[popup] engellendi: {}", targetUrl);
				OsrBrowser b = findBrowser(browser);
				if (b != null && targetUrl != null && !targetUrl.isEmpty()) {
					String esc = targetUrl.replace("\\", "\\\\").replace("\"", "\\\"");
					b.dispatchMessage("{\"popup\":\"" + esc + "\"}");
				}
				return true;
			}
		});
		client.addLoadHandler(new org.cef.handler.CefLoadHandlerAdapter() {
			@Override
			public void onLoadStart(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest.TransitionType transitionType) {
				OsrBrowser b = findBrowser(browser);
				if (b != null) {
					b.noteLoadStart(frame == null ? null : frame.getIdentifier());
				}
				String url = frame == null ? null : frame.getURL();
				if (url != null && url.startsWith("http")) {
					frame.executeJavaScript(EARLY_JS, url, 0);
				}
			}

			@Override
			public void onLoadError(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
				if (errorCode != ErrorCode.ERR_ABORTED) {
					CefNatives.LOGGER.warn("[load-error] {} {} -> {}", errorCode, errorText, failedUrl);
				}
			}
		});
		client.addRequestHandler(new org.cef.handler.CefRequestHandlerAdapter() {
			@Override
			public org.cef.handler.CefResourceRequestHandler getResourceRequestHandler(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame,
					org.cef.network.CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator,
					org.cef.misc.BoolRef disableDefaultHandling) {
				return RESOURCE_LOGGER;
			}
		});
		// Ses: CEF sesi isletim sistemine degil bize verir; biz Minecraft ses motoruna (OpenAL) besleriz.
		// Boylece Sound Physics gibi modlar (duvar/su alti/yanki) ve 3B konumsal ses calisir.
		client.addAudioHandler(new org.cef.handler.CefAudioHandlerAdapter() {
			@Override
			public boolean getAudioParameters(org.cef.browser.CefBrowser browser, org.cef.misc.CefAudioParameters params) {
				return true; // true = yakalamaya devam (false yakalamayi IPTAL eder); parametreler CEF varsayilani (48 kHz stereo)
			}

			@Override
			public void onAudioStreamStarted(org.cef.browser.CefBrowser browser, org.cef.misc.CefAudioParameters params, int channels) {
				OsrBrowser b = findBrowser(browser);
				if (b != null) {
					// Fork'un JNI'si params'i null gecirebiliyor; hiz 0 kalirsa ilk paketten turetilir (10 ms paket -> frames*100)
					int rate = params == null ? 0 : params.sampleRate;
					b.audioStarted(rate, channels);
					CefNatives.LOGGER.info("ses akisi basladi: {} Hz (0=paketten), {} kanal", rate, channels);
				}
			}

			@Override
			public void onAudioStreamPacket(org.cef.browser.CefBrowser browser, float[] data, int frames, long pts) {
				OsrBrowser b = findBrowser(browser);
				if (b != null) {
					b.audioPacket(data, frames);
				}
			}

			@Override
			public void onAudioStreamStopped(org.cef.browser.CefBrowser browser) {
				OsrBrowser b = findBrowser(browser);
				if (b != null) {
					b.audioStopped();
				}
			}

			@Override
			public void onAudioStreamError(org.cef.browser.CefBrowser browser, String text) {
				CefNatives.LOGGER.warn("ses akisi hatasi: {}", text);
			}
		});
		return new CefRuntime(app, client);
	}

	/** Asenkron kurulum durumu. */
	private static final class Init implements Initialization {
		private volatile Stage stage = Stage.NOT_STARTED;
		private volatile float pct = -1f;
		private final CompletableFuture<CefService> future = new CompletableFuture<>();

		void start() {
			Thread t = new Thread(() -> {
				try {
					stage = Stage.DOWNLOADING;
					CefNatives.ensure(p -> pct = (float) (p * 100.0), s -> {
						stage = "EXTRACTING".equals(s) ? Stage.EXTRACTING : Stage.DOWNLOADING;
						pct = -1f;
					});
					stage = Stage.INITIALIZING;
					pct = -1f;
					Minecraft.getInstance().execute(() -> {
						try {
							CefRuntime r = startCef();
							instance = r;
							stage = Stage.DONE;
							future.complete(r);
							CefNatives.LOGGER.info("CEF hazir (codec'li java-cef {})", CefNatives.JAVA_CEF_COMMIT.substring(0, 7));
						} catch (Throwable e) {
							CefNatives.LOGGER.error("CEF baslatilamadi", e);
							stage = Stage.FAILED;
							future.completeExceptionally(e);
						}
					});
				} catch (Throwable e) {
					CefNatives.LOGGER.error("CEF ikilileri hazirlanamadi", e);
					stage = Stage.FAILED;
					future.completeExceptionally(e);
				}
			}, "mcef-codec-setup");
			t.setDaemon(true);
			t.start();
		}

		@Override
		public Stage getStage() { return stage; }

		@Override
		public float getPercentage() { return pct; }

		@Override
		public CompletableFuture<CefService> getFuture() { return future; }
	}
}
