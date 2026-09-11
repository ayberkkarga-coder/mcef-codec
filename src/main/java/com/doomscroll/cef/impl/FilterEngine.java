package com.doomscroll.cef.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kucuk bir Adblock Plus / uBlock kural motoru (saf Java, Minecraft'a bagimli degil).
 *
 * Ag kurallari: ||alan^ , |baslangic, sonu|, * ve ^ joker/ayirici, secenekler: third-party/3p, ~third-party/1p,
 * tur (script, image, stylesheet, object, xmlhttprequest, subdocument, document, media, font, other, ping,
 * websocket), domain=a|b|~c, important/match-case (yok sayilir). @@ istisna. Desteklenmeyen secenekli
 * (redirect=, csp=, removeparam, rewrite, badfilter, header=, method=, popup...) ve /regex/ kurallar atlanir.
 * Hiz: her kuralin sinirlari belli en uzun (>=4) alfasayisal parcasi anahtar olur; istek URL'sinin parcalari
 * ile yalnizca ilgili kurallar denenir. Anahtarsiz kurallar "genel" listede her istekte denenir.
 *
 * Kozmetik kurallar: alanlar##secici (genel ya da alan adina ozel), alan#@#secici (istisna). Sahte sinif (:)
 * iceren, prosedurel (#?#, #$#, +js) kurallar atlanir. CSS metni alan adina gore uretilir (kucuk onbellek).
 */
public final class FilterEngine {
	// tur bitleri
	public static final int T_DOCUMENT = 1, T_SUBDOCUMENT = 2, T_SCRIPT = 4, T_IMAGE = 8, T_STYLESHEET = 16, T_OBJECT = 32,
			T_XHR = 64, T_MEDIA = 128, T_FONT = 256, T_OTHER = 512, T_PING = 1024, T_WEBSOCKET = 2048;

	static final class Rule {
		String pattern;          // kucuk harf, anchor'lar ve secenekler ayrildi
		boolean domainAnchor, startAnchor, endAnchor, exception;
		int typeMask;            // 0 = her tur
		int party;               // 0 her ikisi, 1 yalniz ayni site, 3 yalniz ucuncu taraf
		String[] domInc, domExc; // domain= (kucuk harf)
		String firstLit;         // ilk joker oncesi sabit parca (arama hizi icin)
	}

	private final Map<String, List<Rule>> blockIndex = new HashMap<>();
	private final List<Rule> blockGeneric = new ArrayList<>();
	private final Map<String, List<Rule>> allowIndex = new HashMap<>();
	private final List<Rule> allowGeneric = new ArrayList<>();
	private final Set<String> hostBlock = new HashSet<>();          // ||alan^ (secenek yok) hizli yol
	private final Set<String> genericHideOff = new HashSet<>();     // @@||alan^$generichide|elemhide: genel kozmetik kapali
	private final Set<String> docAllow = new HashSet<>();           // @@||alan^$document: sayfada hic engelleme yok
	private final List<String> cosmeticGeneric = new ArrayList<>();
	private final Map<String, List<String>> cosmeticByDomain = new HashMap<>();
	private final Map<String, Set<String>> cosmeticExcByDomain = new HashMap<>();
	private final Map<String, String> cssCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> e) {
			return size() > 48;
		}
	});
	private int netRules, cosRules, skipped;

	public int networkRuleCount() { return netRules; }
	public int cosmeticRuleCount() { return cosRules; }
	public int skippedCount() { return skipped; }

	public void addHost(String host) {
		hostBlock.add(host);
	}

	// ---------------- ayristirma ----------------

	/** Bir liste dosyasinin satirlarini ekler. */
	public void parse(Iterable<String> lines) {
		for (String raw : lines) {
			String line = raw.trim();
			if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') continue;
			if (line.contains("#@#")) { parseCosmetic(line, "#@#", true); continue; }
			if (line.contains("#?#") || line.contains("#$#") || line.contains("#%#") || line.contains("##+js") || line.contains("#@?#")) { skipped++; continue; }
			int hh = line.indexOf("##");
			if (hh >= 0) { parseCosmetic(line, "##", false); continue; }
			parseNetwork(line);
		}
	}

	private void parseCosmetic(String line, String sep, boolean exception) {
		int i = line.indexOf(sep);
		String domains = line.substring(0, i).trim();
		String sel = line.substring(i + sep.length()).trim();
		if (sel.isEmpty() || sel.indexOf(':') >= 0 || sel.startsWith("+js") || sel.length() > 300) { skipped++; return; }
		if (domains.isEmpty()) {
			if (!exception) { cosmeticGeneric.add(sel); cosRules++; }
			return;
		}
		for (String d : domains.toLowerCase(Locale.ROOT).split(",")) {
			d = d.trim();
			if (d.isEmpty() || d.startsWith("~") || d.indexOf('*') >= 0) continue; // negatif/joker alanlar atlanir
			if (exception) cosmeticExcByDomain.computeIfAbsent(d, k -> new HashSet<>()).add(sel);
			else cosmeticByDomain.computeIfAbsent(d, k -> new ArrayList<>()).add(sel);
			cosRules++;
		}
	}

	private static int typeBit(String opt) {
		switch (opt) {
			case "script": return T_SCRIPT;
			case "image": return T_IMAGE;
			case "stylesheet": return T_STYLESHEET;
			case "object": case "object-subrequest": return T_OBJECT;
			case "xmlhttprequest": case "xhr": return T_XHR;
			case "subdocument": case "frame": return T_SUBDOCUMENT;
			case "document": case "doc": case "popup": return T_DOCUMENT;
			case "media": return T_MEDIA;
			case "font": return T_FONT;
			case "other": return T_OTHER;
			case "ping": case "beacon": return T_PING;
			case "websocket": return T_WEBSOCKET;
			default: return -1;
		}
	}

	private void parseNetwork(String line) {
		Rule r = new Rule();
		String s = line;
		if (s.startsWith("@@")) { r.exception = true; s = s.substring(2); }
		// secenekler: son '$' (URL icinde $ nadir; regex kurallarini zaten atliyoruz)
		int dollar = s.lastIndexOf('$');
		String opts = null;
		if (dollar > 0) {
			String before = s.substring(0, dollar);
			if (before.length() >= 2 && before.startsWith("/") && before.endsWith("/")) { skipped++; return; } // regex + secenek
			opts = s.substring(dollar + 1);
			s = before;
		}
		if (s.length() >= 2 && s.startsWith("/") && s.endsWith("/")) { skipped++; return; } // regex kurali
		if (opts != null && r.exception) {
			// @@||alan^$generichide / $elemhide / $document : alan bazli istisnalar
			boolean gh = false, doc = false, other = false;
			for (String o : opts.split(",")) {
				String opt = o.trim().toLowerCase(Locale.ROOT);
				if (opt.equals("generichide") || opt.equals("elemhide") || opt.equals("ghide") || opt.equals("ehide")) gh = true;
				else if (opt.equals("document") || opt.equals("doc") || opt.equals("urlblock")) doc = true;
				else if (!opt.isEmpty()) other = true;
			}
			if ((gh || doc) && !other) {
				String h = s.toLowerCase(Locale.ROOT);
				if (h.startsWith("||") && h.endsWith("^") && h.indexOf('/') < 0 && h.indexOf('*') < 0) {
					h = h.substring(2, h.length() - 1);
					if (gh) genericHideOff.add(h);
					if (doc) docAllow.add(h);
					netRules++;
				} else {
					skipped++;
				}
				return;
			}
		}
		if (opts != null) {
			int negTypes = 0;
			for (String o : opts.split(",")) {
				String opt = o.trim().toLowerCase(Locale.ROOT);
				if (opt.isEmpty()) continue;
				if (opt.equals("third-party") || opt.equals("3p")) { r.party = 3; continue; }
				if (opt.equals("~third-party") || opt.equals("1p") || opt.equals("first-party")) { r.party = 1; continue; }
				if (opt.equals("important") || opt.equals("match-case") || opt.equals("all") || opt.equals("~match-case")) continue;
				if (opt.startsWith("domain=")) {
					List<String> inc = new ArrayList<>(), exc = new ArrayList<>();
					for (String d : opt.substring(7).split("\\|")) {
						if (d.isEmpty()) continue;
						if (d.startsWith("~")) exc.add(d.substring(1)); else inc.add(d);
					}
					if (!inc.isEmpty()) r.domInc = inc.toArray(new String[0]);
					if (!exc.isEmpty()) r.domExc = exc.toArray(new String[0]);
					continue;
				}
				boolean neg = opt.startsWith("~");
				int bit = typeBit(neg ? opt.substring(1) : opt);
				if (bit > 0) { if (neg) negTypes |= bit; else r.typeMask |= bit; continue; }
				skipped++;
				return; // desteklenmeyen secenek: kurali atla (yanlis pozitif olmasin)
			}
			if (negTypes != 0 && r.typeMask == 0) r.typeMask = ~negTypes & 0xFFFF;
		}
		if (s.startsWith("||")) { r.domainAnchor = true; s = s.substring(2); }
		else if (s.startsWith("|")) { r.startAnchor = true; s = s.substring(1); }
		if (s.endsWith("|")) { r.endAnchor = true; s = s.substring(0, s.length() - 1); }
		s = s.toLowerCase(Locale.ROOT);
		if (s.isEmpty() || s.equals("*")) { skipped++; return; }
		r.pattern = s;
		// hizli yol: ||alan^ secenek yok
		if (r.domainAnchor && !r.exception && opts == null && s.endsWith("^") && s.indexOf('/') < 0 && s.indexOf('*') < 0 && s.indexOf('^') == s.length() - 1) {
			hostBlock.add(s.substring(0, s.length() - 1));
			netRules++;
			return;
		}
		int star = indexOfAny(s, "*^");
		r.firstLit = star < 0 ? s : s.substring(0, star);
		String key = tokenOf(r);
		Map<String, List<Rule>> idx = r.exception ? allowIndex : blockIndex;
		List<Rule> gen = r.exception ? allowGeneric : blockGeneric;
		if (key == null) gen.add(r); else idx.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
		netRules++;
	}

	private static int indexOfAny(String s, String chars) {
		for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
		return -1;
	}

	private static boolean alnum(char c) {
		return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
	}

	/** Kuralin anahtar parcasi: iki yani joker olmayan, sinirlari belli, >=4 karakterlik en uzun alfasayisal dizi. */
	private static String tokenOf(Rule r) {
		String p = r.pattern;
		String best = null;
		int i = 0, n = p.length();
		while (i < n) {
			if (!alnum(p.charAt(i))) { i++; continue; }
			int j = i;
			while (j < n && alnum(p.charAt(j))) j++;
			// sol sinir: baslangic (domainAnchor/startAnchor ile) ya da joker olmayan ayirici
			boolean leftOk = (i == 0) ? (r.domainAnchor || r.startAnchor) : p.charAt(i - 1) != '*';
			// sag sinir: '^'/'/'/'.' gibi ayirici ya da (endAnchor ile) son; sondaysa ve anchor yoksa guvensiz
			boolean rightOk = (j == n) ? r.endAnchor : p.charAt(j) != '*';
			if (leftOk && rightOk && j - i >= 4 && (best == null || j - i > best.length())) best = p.substring(i, j);
			i = j;
		}
		return best;
	}

	// ---------------- ag eslestirme ----------------

	private static boolean isSep(char c) {
		return !(alnum(c) || c == '_' || c == '-' || c == '.' || c == '%');
	}

	private static boolean glob(String u, int ui, String p, int pi, boolean endAnchor) {
		int un = u.length(), pn = p.length();
		while (pi < pn) {
			char c = p.charAt(pi);
			if (c == '*') {
				while (pi < pn && p.charAt(pi) == '*') pi++;
				if (pi == pn) return true;
				for (int k = ui; k <= un; k++) if (glob(u, k, p, pi, endAnchor)) return true;
				return false;
			}
			if (ui >= un) return c == '^' && pi == pn - 1; // ^ sonda: URL sonu da ayiricidir
			char uc = u.charAt(ui);
			if (c == '^') { if (!isSep(uc)) return false; }
			else if (c != uc) return false;
			ui++; pi++;
		}
		return !endAnchor || ui == un;
	}

	private static boolean patternMatches(Rule r, String u) {
		String p = r.pattern;
		if (r.domainAnchor) {
			int s = u.indexOf("://");
			if (s < 0) return false;
			int hostStart = s + 3;
			int hostEnd = u.indexOf('/', hostStart);
			if (hostEnd < 0) hostEnd = u.length();
			for (int i = hostStart; i < hostEnd; i++) {
				if (i == hostStart || u.charAt(i - 1) == '.') {
					if (glob(u, i, p, 0, r.endAnchor)) return true;
				}
			}
			return false;
		}
		if (r.startAnchor) return glob(u, 0, p, 0, r.endAnchor);
		if (r.firstLit.isEmpty()) {
			for (int i = 0; i <= u.length(); i++) if (glob(u, i, p, 0, r.endAnchor)) return true;
			return false;
		}
		int from = 0;
		while (true) {
			int i = u.indexOf(r.firstLit, from);
			if (i < 0) return false;
			if (glob(u, i, p, 0, r.endAnchor)) return true;
			from = i + 1;
		}
	}

	private static boolean domainListMatches(String[] list, String pageHost) {
		if (pageHost == null || pageHost.isEmpty()) return false;
		for (String d : list) {
			if (pageHost.equals(d) || pageHost.endsWith("." + d)) return true;
		}
		return false;
	}

	private static boolean ruleApplies(Rule r, String u, int typeBit, boolean thirdParty, String pageHost) {
		if (r.typeMask != 0 && (r.typeMask & typeBit) == 0) return false;
		if (r.party == 3 && !thirdParty) return false;
		if (r.party == 1 && thirdParty) return false;
		if (r.domInc != null && !domainListMatches(r.domInc, pageHost)) return false;
		if (r.domExc != null && domainListMatches(r.domExc, pageHost)) return false;
		return patternMatches(r, u);
	}

	private static List<String> urlTokens(String u) {
		List<String> out = new ArrayList<>(16);
		int i = 0, n = u.length();
		while (i < n) {
			if (!alnum(u.charAt(i))) { i++; continue; }
			int j = i;
			while (j < n && alnum(u.charAt(j))) j++;
			if (j - i >= 4) out.add(u.substring(i, j));
			i = j;
		}
		return out;
	}

	private static Rule find(Map<String, List<Rule>> idx, List<Rule> generic, List<String> tokens, String u, int typeBit, boolean thirdParty, String pageHost) {
		for (String t : tokens) {
			List<Rule> rules = idx.get(t);
			if (rules == null) continue;
			for (Rule r : rules) if (ruleApplies(r, u, typeBit, thirdParty, pageHost)) return r;
		}
		for (Rule r : generic) if (ruleApplies(r, u, typeBit, thirdParty, pageHost)) return r;
		return null;
	}

	private static boolean inDomainSet(Set<String> set, String host) {
		if (set.isEmpty() || host == null || host.isEmpty()) return false;
		String h = host;
		while (true) {
			if (set.contains(h)) return true;
			int dot = h.indexOf('.');
			if (dot < 0) return false;
			h = h.substring(dot + 1);
			if (h.indexOf('.') < 0) return false;
		}
	}

	/** @@||alan^$document: bu sayfada hicbir istek engellenmez. */
	public boolean pageAllowed(String pageHost) {
		return inDomainSet(docAllow, pageHost);
	}

	/** Alan adi hizli yolu: alan ya da ust alanlari listede mi? */
	public boolean hostBlocked(String host) {
		String h = host;
		while (true) {
			if (hostBlock.contains(h)) return true;
			int dot = h.indexOf('.');
			if (dot < 0) return false;
			h = h.substring(dot + 1);
			if (h.indexOf('.') < 0) return false;
		}
	}

	/**
	 * Istek engellensin mi? url kucuk harfe cevrilir. pageHost: istegi yapan sayfanin alan adi (bos olabilir).
	 * Once istisnalar (@@) bakilir: eslesirse asla engellenmez.
	 */
	public boolean shouldBlock(String url, int typeBit, String requestHost, String pageHost) {
		if (pageAllowed(pageHost)) return false;
		String u = url.toLowerCase(Locale.ROOT);
		boolean thirdParty = pageHost == null || pageHost.isEmpty() || !sameSite(requestHost, pageHost);
		List<String> tokens = urlTokens(u);
		if (find(allowIndex, allowGeneric, tokens, u, typeBit, thirdParty, pageHost) != null) return false;
		if (hostBlocked(requestHost)) return true;
		return find(blockIndex, blockGeneric, tokens, u, typeBit, thirdParty, pageHost) != null;
	}

	static String siteOf(String host) {
		String[] p = host.split("\\.");
		int n = p.length;
		return n >= 2 ? p[n - 2] + "." + p[n - 1] : host;
	}

	static boolean sameSite(String a, String b) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
		return siteOf(a).equals(siteOf(b));
	}

	// ---------------- kozmetik ----------------

	/** Sayfa alan adi icin gizleme CSS'i ("" = kural yok). Onbellekli. */
	public String cosmeticCss(String host) {
		if (host == null || host.isEmpty()) return "";
		String h = host.toLowerCase(Locale.ROOT);
		String cached = cssCache.get(h);
		if (cached != null) return cached;
		Set<String> exc = new HashSet<>();
		List<String> specific = new ArrayList<>();
		String d = h;
		while (true) {
			Set<String> e = cosmeticExcByDomain.get(d);
			if (e != null) exc.addAll(e);
			List<String> sp = cosmeticByDomain.get(d);
			if (sp != null) specific.addAll(sp);
			int dot = d.indexOf('.');
			if (dot < 0) break;
			d = d.substring(dot + 1);
			if (d.indexOf('.') < 0) break;
		}
		boolean generic = !inDomainSet(genericHideOff, h);
		StringBuilder sb = new StringBuilder((generic ? cosmeticGeneric.size() * 24 : 0) + specific.size() * 24 + 64);
		appendChunks(sb, specific, exc);
		if (generic) appendChunks(sb, cosmeticGeneric, exc);
		String css = sb.toString();
		cssCache.put(h, css);
		return css;
	}

	/** Gecersiz tek bir secici tum grubu bozar: kucuk gruplar halinde yaz. */
	private static void appendChunks(StringBuilder sb, List<String> sels, Set<String> exc) {
		int n = 0;
		StringBuilder grp = new StringBuilder();
		for (String s : sels) {
			if (exc.contains(s)) continue;
			if (grp.length() > 0) grp.append(',');
			grp.append(s);
			if (++n >= 20) {
				sb.append(grp).append("{display:none!important}\n");
				grp.setLength(0);
				n = 0;
			}
		}
		if (grp.length() > 0) sb.append(grp).append("{display:none!important}\n");
	}

	public String stats() {
		return netRules + " ag kurali, " + hostBlock.size() + " alan, " + cosRules + " kozmetik, " + genericHideOff.size() + " generichide, " + docAllow.size() + " sayfa istisnasi, " + skipped + " atlanan";
	}
}
