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
 * doomscroll:// local pages (main menu etc.): the mod generates the HTML ({@link CefLaunchOptions#localPages}).
 * If the generator returns null, a 404 page is served. New instance per request; runs on the CEF IO thread.
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
			CefNatives.LOGGER.warn("could not generate local page: {}", url, t);
		}
		found = html != null;
		if (html == null) {
			html = "<!doctype html><meta charset=\"utf-8\"><title>doomscroll</title>"
					+ "<body style=\"background:#141418;color:#ddd;font-family:sans-serif;padding:40px\">"
					+ "<h2>Page not found</h2><p>" + escape(url) + "</p></body>";
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
