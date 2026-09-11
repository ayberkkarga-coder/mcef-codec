package com.doomscroll.cef.impl;

import com.doomscroll.cef.api.CefLaunchOptions;
import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.nio.charset.StandardCharsets;

/**
 * doomscroll:// yerel sayfalari (ana menu vb.): HTML'i mod uretir ({@link CefLaunchOptions#localPages}).
 * Uretici null donerse 404 sayfasi. Her istek icin yeni ornek; CEF IO is parcaciginda calisir.
 */
final class LocalPageHandler extends CefResourceHandlerAdapter {
	private byte[] data = new byte[0];
	private int offset = 0;
	private boolean found = false;

	@Override
	public boolean processRequest(CefRequest request, CefCallback callback) {
		String url = request.getURL();
		String html = null;
		try {
			var f = CefLaunchOptions.localPages;
			html = f == null ? null : f.apply(url);
		} catch (Throwable t) {
			CefNatives.LOGGER.warn("yerel sayfa uretilemedi: {}", url, t);
		}
		found = html != null;
		if (html == null) {
			html = "<!doctype html><meta charset=\"utf-8\"><title>doomscroll</title>"
					+ "<body style=\"background:#141418;color:#ddd;font-family:sans-serif;padding:40px\">"
					+ "<h2>Sayfa yok</h2><p>" + escape(url) + "</p></body>";
		}
		data = html.getBytes(StandardCharsets.UTF_8);
		offset = 0;
		callback.Continue();
		return true;
	}

	@Override
	public void getResponseHeaders(CefResponse response, IntRef responseLength, StringRef redirectUrl) {
		response.setMimeType("text/html");
		response.setHeaderByName("Content-Type", "text/html; charset=utf-8", true);
		response.setHeaderByName("Cache-Control", "no-store", true);
		response.setStatus(found ? 200 : 404);
		response.setStatusText(found ? "OK" : "Not Found");
		responseLength.set(data.length);
	}

	@Override
	public boolean readResponse(byte[] dataOut, int bytesToRead, IntRef bytesRead, CefCallback callback) {
		if (offset >= data.length) {
			bytesRead.set(0);
			return false;
		}
		int n = Math.min(bytesToRead, data.length - offset);
		System.arraycopy(data, offset, dataOut, 0, n);
		offset += n;
		bytesRead.set(n);
		return true;
	}

	private static String escape(String s) {
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
