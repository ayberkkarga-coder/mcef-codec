package com.doomscroll.cef.impl;

import com.doomscroll.cef.api.CefLaunchOptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reklam engelleyici: gercek engelleyicilerin yaptigi gibi filtre listeleriyle calisir.
 *  - Ag: istek CEF'ten cikmadan once (onBeforeResourceLoad) {@link FilterEngine} kurallariyla iptal edilir
 *    (alan adi, yol kaliplari, tur/ucuncu-taraf/domain secenekleri, @@ istisnalar).
 *  - Kozmetik: listelerin ##secici kurallari her cerceveye stil olarak enjekte edilir ({@link OsrBrowser#applyCosmetics}).
 *  - Sayfanin baslattigi bahis/casino sitesine gecisler engellenir; adres cubugundan yazilan adres engellenmez.
 *
 * Listeler (config/mcef-codec/adblock/, 7 gunde bir yenilenir):
 *  - EasyList (easylist.to)  - AdGuard Turkish filter (filters.adtidy.org, id 13)  - StevenBlack unified hosts
 *  - yerlesik cekirdek liste (indirme olmasa da).
 * YouTube'un kendi reklamlari ayni alan adindan geldigi icin burada degil, sayfa ici atlayici ile gecilir.
 */
public final class AdBlock {
	private static final Path DIR = CefNatives.DIR.resolve("adblock");
	private static final Path HOSTS = DIR.resolve("hosts.txt");
	private static final Path EASYLIST = DIR.resolve("easylist.txt");
	private static final Path ADGUARD_TR = DIR.resolve("adguard_turkish.txt");
	private static final String HOSTS_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts";
	private static final String EASYLIST_URL = "https://easylist.to/easylist/easylist.txt";
	private static final String ADGUARD_TR_URL = "https://filters.adtidy.org/extension/ublock/filters/13.txt";
	private static final long REFRESH_MS = 7L * 24 * 3600 * 1000;

	/** Engellenmeyecekler: video/CDN alanlari (liste hatasi olursa oynatma bozulmasin). */
	private static final String[] ALLOW = {
			"googlevideo.com", "ytimg.com", "youtube.com", "youtu.be", "ggpht.com", "gstatic.com", "googleapis.com",
			"tiktokcdn.com", "tiktokcdn-us.com", "tiktokv.com", "tiktok.com", "ttwstatic.com", "byteoversea.com",
			"cdninstagram.com", "instagram.com", "fbcdn.net", "twimg.com", "twitch.tv", "ttvnw.net", "jtvnw.net",
			"kick.com", "vimeo.com", "vimeocdn.com", "dailymotion.com", "dmcdn.net", "akamaihd.net",
			"jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com", "hcaptcha.com", "recaptcha.net"
	};

	/** Yerlesik cekirdek liste: indirme olmasa da en yaygin reklam/popup aglari. */
	private static final String[] CORE = {
			"doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com", "googletagmanager.com",
			"google-analytics.com", "adservice.google.com", "adnxs.com", "adsrvr.org", "taboola.com", "outbrain.com",
			"criteo.com", "criteo.net", "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "indexww.com",
			"smartadserver.com", "adform.net", "yieldmo.com", "sharethrough.com", "amazon-adsystem.com", "media.net",
			"moatads.com", "scorecardresearch.com", "quantserve.com", "bluekai.com", "popads.net", "popcash.net",
			"propellerads.com", "propellerclick.com", "onclickads.net", "onclasrv.com", "exoclick.com", "exosrv.com",
			"juicyads.com", "trafficjunky.net", "trafficjunky.com", "adsterra.com", "hilltopads.net", "hilltopads.com",
			"clickadu.com", "adcash.com", "mgid.com", "revcontent.com", "zeropark.com", "adsco.re", "a-ads.com",
			"coinzilla.io", "bidvertiser.com", "adbutler.com", "adroll.com", "tsyndicate.com", "trafficstars.com",
			"ero-advertising.com", "plugrush.com", "adtng.com", "adspyglass.com", "realsrv.com", "mopsrv.com",
			"traffichaus.com", "richpush.co", "pushame.com", "notix.io", "hotjar.com", "mouseflow.com", "fullstory.com",
			"mc.yandex.ru", "connect.facebook.net", "ads.tiktok.com", "analytics.tiktok.com", "ads-twitter.com",
			"monetag.com", "monetag.net", "adskeeper.com", "galaksion.com", "popunder.net", "popmyads.com",
			"adplxmd.com", "adnium.com", "adxpansion.com", "tubecorporate.com", "eroadvertising.com", "ad-maven.com",
			"admaven.com", "bemobtrcks.com", "linkvertise.com", "adf.ly", "shorte.st", "ouo.io", "clksite.com", "cpmstar.com"
	};

	/** Bahis/casino sitesi kalibi (film sitesi reklam linkleri): yalnizca sayfanin baslattigi, baska siteye giden gecislerde. */
	private static final java.util.regex.Pattern BET_HOST = java.util.regex.Pattern.compile(
			"bahis|casino|kumar|jackpot|rulet|slotin|betorspin|betist|betine|baywin|meritbet|jetbahis|marsbahis|sekabet|matbet|holiganbet"
			+ "|grandpashabet|casinomaxi|mobilbahis|bets10|betboo|superbetin|tipobet|restbet|piabet|betpas|onwin|hilbet|kralbet|betturkey"
			+ "|vdcasino|discountcasino|betnano|betwoon|betcio|sahabet|elexbet|imajbet|milanobet|pashagaming|betsmove|1xbet|betwinner"
			+ "|melbet|22bet|mostbet|pin-?up|1win|parimatch|paparabet|\\d+bet\\.|bet\\d+\\.|slot\\d*\\.|freespin|deneme-?bonus",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	private static volatile FilterEngine engine = coreEngine();
	private static volatile String listSource = "yerlesik";
	private static final AtomicLong blocked = new AtomicLong();
	private static final AtomicLong logged = new AtomicLong();
	private static volatile boolean started = false;

	private AdBlock() {}

	private static FilterEngine coreEngine() {
		FilterEngine e = new FilterEngine();
		for (String c : CORE) {
			e.addHost(c);
		}
		return e;
	}

	/** Listeleri diskten yukler, eskiyse/yoksa arka planda indirir. Bir kez cagrilir. */
	public static synchronized void init() {
		if (started) {
			return;
		}
		started = true;
		Thread t = new Thread(AdBlock::loadOrDownload, "mcef-codec-adblock");
		t.setDaemon(true);
		t.start();
	}

	private static boolean fresh(Path p) throws IOException {
		return Files.isRegularFile(p) && System.currentTimeMillis() - Files.getLastModifiedTime(p).toMillis() < REFRESH_MS;
	}

	private static void loadOrDownload() {
		try {
			Files.createDirectories(DIR);
			boolean any = Files.isRegularFile(HOSTS) || Files.isRegularFile(EASYLIST) || Files.isRegularFile(ADGUARD_TR);
			if (any) {
				rebuild("disk");
			}
			boolean changed = false;
			if (!fresh(HOSTS)) changed |= download(HOSTS_URL, HOSTS, "StevenBlack hosts");
			if (!fresh(EASYLIST)) changed |= download(EASYLIST_URL, EASYLIST, "EasyList");
			if (!fresh(ADGUARD_TR)) changed |= download(ADGUARD_TR_URL, ADGUARD_TR, "AdGuard Turkish");
			if (changed || !any) {
				rebuild("indirildi");
			}
		} catch (Exception e) {
			CefNatives.LOGGER.warn("reklam listesi yuklenemedi, yerlesik liste kullaniliyor: {}", e.toString());
		}
	}

	private static boolean download(String url, Path target, String name) {
		try {
			HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
					.header("User-Agent", "mcef-codec-adblock").GET().build();
			Path tmp = target.resolveSibling(target.getFileName() + ".part");
			HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
			if (resp.statusCode() / 100 != 2) {
				Files.deleteIfExists(tmp);
				CefNatives.LOGGER.warn("reklam listesi {} indirilemedi: HTTP {}", name, resp.statusCode());
				return false;
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			return true;
		} catch (Exception e) {
			CefNatives.LOGGER.warn("reklam listesi {} indirilemedi: {}", name, e.toString());
			return false;
		}
	}

	/** Tum kaynaklardan yeni bir motor kurar ve atomik olarak degistirir. */
	private static void rebuild(String source) throws IOException {
		long t0 = System.nanoTime();
		FilterEngine e = coreEngine();
		int hosts = 0;
		if (Files.isRegularFile(HOSTS)) {
			hosts = loadHosts(HOSTS, e);
		}
		if (Files.isRegularFile(EASYLIST)) {
			e.parse(Files.readAllLines(EASYLIST, StandardCharsets.UTF_8));
		}
		if (Files.isRegularFile(ADGUARD_TR)) {
			e.parse(Files.readAllLines(ADGUARD_TR, StandardCharsets.UTF_8));
		}
		engine = e;
		listSource = source + "; hosts " + hosts + "; " + e.stats();
		CefNatives.LOGGER.info("reklam filtreleri hazir ({} ms): {}", (System.nanoTime() - t0) / 1_000_000, listSource);
	}

	private static int loadHosts(Path file, FilterEngine e) throws IOException {
		int n = 0;
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String l = line.trim();
			if (l.isEmpty() || l.charAt(0) == '#') continue;
			String[] parts = l.split("\\s+");
			String host;
			if (parts.length >= 2 && (parts[0].equals("0.0.0.0") || parts[0].equals("127.0.0.1"))) host = parts[1];
			else if (parts.length == 1) host = parts[0];
			else continue;
			host = host.toLowerCase(Locale.ROOT);
			if (host.equals("0.0.0.0") || host.equals("localhost") || host.equals("localhost.localdomain")
					|| host.equals("broadcasthost") || host.equals("local") || host.startsWith("ip6-")) continue;
			e.addHost(host);
			n++;
		}
		return n;
	}

	private static int typeBitOf(String cefType) {
		if (cefType == null) return FilterEngine.T_OTHER;
		switch (cefType) {
			case "RT_MAIN_FRAME": return FilterEngine.T_DOCUMENT;
			case "RT_SUB_FRAME": return FilterEngine.T_SUBDOCUMENT;
			case "RT_STYLESHEET": return FilterEngine.T_STYLESHEET;
			case "RT_SCRIPT": case "RT_WORKER": case "RT_SHARED_WORKER": case "RT_SERVICE_WORKER": return FilterEngine.T_SCRIPT;
			case "RT_IMAGE": case "RT_FAVICON": return FilterEngine.T_IMAGE;
			case "RT_FONT_RESOURCE": return FilterEngine.T_FONT;
			case "RT_OBJECT": case "RT_PLUGIN_RESOURCE": return FilterEngine.T_OBJECT;
			case "RT_MEDIA": return FilterEngine.T_MEDIA;
			case "RT_XHR": return FilterEngine.T_XHR;
			case "RT_PING": case "RT_CSP_REPORT": return FilterEngine.T_PING;
			default: return FilterEngine.T_OTHER;
		}
	}

	/**
	 * Bu istek engellensin mi?
	 * @param cefType  CefRequest.ResourceType adi (RT_SCRIPT gibi)
	 * @param mainFrame ana sayfa gecisi mi
	 * @param pageUrl  istegi yapan sayfanin adresi (ana sayfa gecisinde: referrer)
	 */
	public static boolean shouldBlock(String url, String cefType, boolean mainFrame, String pageUrl) {
		if (!CefLaunchOptions.adBlock || url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
			return false;
		}
		String host = hostOf(url);
		if (host.isEmpty()) {
			return false;
		}
		for (String a : ALLOW) {
			if (host.equals(a) || host.endsWith("." + a)) {
				return false;
			}
		}
		String pageHost = hostOf(pageUrl);
		if (mainFrame) {
			if (pageHost.isEmpty()) {
				return false; // adres cubugu / ilk acilis: asla engelleme
			}
			if (FilterEngine.sameSite(host, pageHost)) {
				return false; // sitenin kendi sayfalari
			}
			if (BET_HOST.matcher(host).find()) {
				count("bahis sitesine yonlendirme", host);
				return true;
			}
		}
		boolean block = engine.shouldBlock(url, typeBitOf(cefType), host, pageHost);
		if (block) {
			count(mainFrame ? "sayfa yonlendirmesi" : "", host + pathOf(url));
		}
		return block;
	}

	private static void count(String what, String host) {
		long n = blocked.incrementAndGet();
		if (logged.incrementAndGet() <= 20) {
			CefNatives.LOGGER.info("[reklam] engellendi{}: {}", what.isEmpty() ? "" : " (" + what + ")", host);
		} else if (n % 500 == 0) {
			CefNatives.LOGGER.info("[reklam] toplam {} istek engellendi", n);
		}
	}

	/** Sayfa alan adi icin kozmetik gizleme CSS'i ("" = yok). */
	public static String cosmeticCss(String host) {
		if (!CefLaunchOptions.adBlock || host == null || host.isEmpty()) {
			return "";
		}
		return engine.cosmeticCss(host);
	}

	/** Log icin kisaltilmis yol (sorgu atilir). */
	private static String pathOf(String url) {
		int i = url.indexOf("://");
		if (i < 0) return "";
		int p = url.indexOf('/', i + 3);
		if (p < 0) return "";
		int q = url.indexOf('?', p);
		String path = q < 0 ? url.substring(p) : url.substring(p, q);
		return path.length() > 50 ? path.substring(0, 50) + "…" : path;
	}

	static String hostOf(String url) {
		if (url == null || url.isEmpty()) {
			return "";
		}
		try {
			String h = URI.create(url).getHost();
			return h == null ? "" : h.toLowerCase(Locale.ROOT);
		} catch (Exception e) {
			int i = url.indexOf("://");
			if (i < 0) return "";
			String rest = url.substring(i + 3);
			int end = rest.indexOf('/');
			String hp = end < 0 ? rest : rest.substring(0, end);
			int colon = hp.indexOf(':');
			return (colon < 0 ? hp : hp.substring(0, colon)).toLowerCase(Locale.ROOT);
		}
	}

	public static long blockedCount() {
		return blocked.get();
	}

	public static String info() {
		return (CefLaunchOptions.adBlock ? "acik" : "kapali") + " · " + listSource + " · " + blocked.get() + " istek engellendi";
	}
}
