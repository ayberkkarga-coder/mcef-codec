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
 * CEF life cycle: download the binaries (background) -> start on the render thread -> pump every frame -> shut down.
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

	/** Logs failed resource loads (HTTP >= 400 or a network error); for diagnosing why a site does not play. */
	private static final org.cef.handler.CefResourceRequestHandlerAdapter RESOURCE_LOGGER = new org.cef.handler.CefResourceRequestHandlerAdapter() {
		/** Runtime identity switch: replaces the User-Agent header and strips the Chromium Client-Hints headers (no restart needed). */
		@Override
		public boolean onBeforeResourceLoad(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest request) {
			// Ad filters (EasyList/AdGuard/hosts): the request must never go out
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
	 * Ephemeral request context shared by the screens: cookies stay in memory, nothing is written to disk.
	 * Each screen could have had its own context, but CEF spawns a separate render process per
	 * context; with dozens of screens that brings the machine to its knees. One shared ephemeral context
	 * is enough for "my session on my tablet must not mix with a page someone else opened".
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
						CefNatives.LOGGER.warn("could not create ephemeral request context, the shared profile will be used: {}", t.toString());
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
		// CEF does not rasterize content in an OSR browser it considers "hidden" (background only).
		// Mark every browser explicitly visible so that text/video gets drawn.
		b.setWindowVisibility(true);
		b.resize(1280, 720);
		synchronized (browsers) {
			browsers.add(b);
			ALL_BROWSERS.add(b);
		}
		return b;
	}

	/** Finds the matching CefBrowser among the browsers of all running runtimes (for the audio handler). */
	static @Nullable OsrBrowser findBrowser(org.cef.browser.CefBrowser browser) {
		if (browser == null) {
			return null; // the last audio callbacks of a closing browser can arrive with null
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

	/** Do two URLs share the same registered domain (last two labels)? False for an empty/invalid URL. */
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
		String out = String.format(java.util.Locale.ROOT, "CEF pump (painting+events, render thread): %.2f ms/frame, %.0f frames/s, %d browsers",
				pumps == 0 ? 0.0 : pumpNanos / 1e6 / pumps, pumps / sec, ALL_BROWSERS.size());
		pumpNanos = 0L;
		pumps = 0L;
		perfStart = now;
		return out;
	}

	/** On game exit: close the browsers, stop CEF, leave no jcef_helper behind. */
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
			CefNatives.LOGGER.warn("error while shutting down CEF", e);
		}
	}

	/** Windows: crashed/hung jcef_helper processes (same approach as MCEF 2.x). */
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
				CefNatives.LOGGER.warn("jcef_helper.exe is still running, killing it");
				new ProcessBuilder("taskkill", "/F", "/IM", "jcef_helper.exe").start();
			}
		} catch (Exception e) {
			CefNatives.LOGGER.warn("jcef_helper check failed", e);
		}
	}

	/** Runs on the render thread. */
	private static CefRuntime startCef() {
		System.setProperty("jcef.path", CefNatives.platformDir().toAbsolutePath().toString());

		// GPU ON (like CinemaMod). The real cause of the black screen was not the GPU but frames not flowing;
		// driveFrames()/invalidate() fixes that. The GPU rasterizer draws text/images/video.
		// Same switch set as CinemaMod/MCEF (these binaries work with them).
		String[] switches = new String[]{
				"--autoplay-policy=no-user-gesture-required",
				"--enable-widevine-cdm",
				"--disable-blink-features=AutomationControlled" // hide the automation trace to get past Google's "not secure" block
		};
		// Fix the broken export name in the branch 6478 build before the DLL is loaded (idempotent)
		PeExportFix.apply(CefNatives.platformDir().resolve("jcef.dll"));
		if (!CefApp.startup(switches)) {
			throw new IllegalStateException("CefApp.startup failed");
		}

		org.cef.CefApp.addAppHandler(new org.cef.handler.CefAppHandlerAdapter(switches) {
			@Override
			public void onBeforeCommandLineProcessing(String processType, org.cef.callback.CefCommandLine cmd) {
				cmd.appendSwitchWithValue("autoplay-policy", "no-user-gesture-required");
			}

			@Override
			public void onRegisterCustomSchemes(org.cef.callback.CefSchemeRegistrar registrar) {
				// doomscroll://home/... local pages (main menu): standard, secure, CORS + fetch enabled
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
		// user_agent_product deliberately empty: the "MCEF/2" suffix tripped some CDN bot filters (script 403)
		String ua = com.doomscroll.cef.api.CefLaunchOptions.userAgent;
		if (ua != null && !ua.isBlank()) {
			settings.user_agent = ua;
		}

		AdBlock.init(); // ad lists (disk/download) in the background
		CefApp app = CefApp.getInstance(switches, settings);
		CefClient client = app.createClient();
		client.addDisplayHandler(new CefDisplayHandlerAdapter() {
			@Override
			public boolean onConsoleMessage(org.cef.browser.CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
				// Page -> Java message channel (status reports etc.)
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
				// The in-page watcher (doomscroll auto-advance) asked for the next video -> send a trusted arrow key
				if ("__DS_NEXT__".equals(message)) {
					CefNatives.LOGGER.info("auto-advance: next video (in-page)");
					return true;
				}
				// Write warnings/errors to the MC log (the only clue for problems like "why does the site not play")
				if (message != null && message.contains("frame is sandboxed")) {
					return true; // sandbox warning from ad iframes: noise
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
				// No window in OSR; the page switches to its own fullscreen layout (the video fills the view)
				CefNatives.LOGGER.info("[fullscreen] page fullscreen: {}", fullscreen);
			}
		});
		// New window/popup: cannot be opened in OSR. A same-site target opens in the same browser, a foreign one (ad) is blocked.
		client.addLifeSpanHandler(new org.cef.handler.CefLifeSpanHandlerAdapter() {
			@Override
			public boolean onBeforePopup(org.cef.browser.CefBrowser browser, org.cef.browser.CefFrame frame, String targetUrl, String targetFrameName) {
				// No popup opens on its own (on film sites even a same-site redirect can be an ad).
				// The URL is reported to the listener; it can be opened by command if wanted.
				CefNatives.LOGGER.info("[popup] blocked: {}", targetUrl);
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
		// Audio: CEF hands the audio to us instead of the operating system; we feed it to the Minecraft sound engine (OpenAL).
		// That way mods like Sound Physics (walls/underwater/echo) and 3D positional audio work.
		client.addAudioHandler(new org.cef.handler.CefAudioHandlerAdapter() {
			@Override
			public boolean getAudioParameters(org.cef.browser.CefBrowser browser, org.cef.misc.CefAudioParameters params) {
				return true; // true = keep capturing (false CANCELS the capture); parameters are the CEF defaults (48 kHz stereo)
			}

			@Override
			public void onAudioStreamStarted(org.cef.browser.CefBrowser browser, org.cef.misc.CefAudioParameters params, int channels) {
				OsrBrowser b = findBrowser(browser);
				if (b != null) {
					// The fork's JNI may pass params as null; if the rate stays 0 it is derived from the first packet (10 ms packet -> frames*100)
					int rate = params == null ? 0 : params.sampleRate;
					b.audioStarted(rate, channels);
					CefNatives.LOGGER.info("audio stream started: {} Hz (0=from packet), {} channels", rate, channels);
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
				CefNatives.LOGGER.warn("audio stream error: {}", text);
			}
		});
		return new CefRuntime(app, client);
	}

	/** Asynchronous setup state. */
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
							CefNatives.LOGGER.info("CEF ready (java-cef with codecs {})", CefNatives.JAVA_CEF_COMMIT.substring(0, 7));
						} catch (Throwable e) {
							CefNatives.LOGGER.error("could not start CEF", e);
							stage = Stage.FAILED;
							future.completeExceptionally(e);
						}
					});
				} catch (Throwable e) {
					CefNatives.LOGGER.error("could not prepare CEF binaries", e);
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
