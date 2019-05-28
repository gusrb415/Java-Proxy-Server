package comp4621;

import jdk.internal.util.xml.impl.Input;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request Handler
 * Each request from the client is handled by this class.
 * HTTPS request is just used to directly connect the client and the server.
 * HTTP requests are looked into and passed to the server.
 * The responses from the server is cached as well.
 * However, for HTTP protocol other than GET, no cached file is used to return
 * to the client since the request body may be different.
 *
 * For writing back to the client, since HTTP response may be in a format of
 * bytes or texts, DataOutputStream is used since it handles both data.
 */
class RequestHandler implements Runnable {
	private Socket clientToProxySocket;
	private InputStream proxyToClientIs;
	private OutputStream proxyToClientOs;

	private static Map<String, File> cache = new ConcurrentHashMap<>();
	private static List<String> blockedSites = new ArrayList<>();

	private final static String CRLF = Helper.CRLF;
	private final static String CACHE_FOLDER = "Cached";
	private final static String HTTP_OK = "HTTP/1.1 200 OK" + CRLF + "Proxy-agent: ProxyServer/1.0" + CRLF;
	private final static String HTTP_NOT_FOUND = "HTTP/1.1 404 NOT FOUND" + CRLF + "Proxy-agent: ProxyServer/1.0" + CRLF;

	RequestHandler(Socket clientSocket) {
		this.clientToProxySocket = clientSocket;
		try {
			clientToProxySocket.setSoTimeout(3000);
			proxyToClientIs = clientSocket.getInputStream();
			proxyToClientOs = clientSocket.getOutputStream();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	static void clearCaches() {
		try {
			Files.walk(new File(CACHE_FOLDER).toPath())
					.map(Path::toFile)
					.forEach(file -> {
						if (!file.delete())
							System.out.println(file.getName() + " cannot be deleted.");
					});
		} catch (IOException ignored) {
		}

		cache.clear();

		File cacheFolder = new File(CACHE_FOLDER);
		if (!cacheFolder.mkdir())
			System.out.println("Cache folder cannot be created");
	}

	static void blockUrl(String... urls) {
		for (String url : urls) {
			if (url.isEmpty())
				continue;
			blockedSites.add(url);
			System.out.println(url + " added to the block list");
		}
	}

	@Override
	public void run() {
		Map<String, String> requestHeaders = new HashMap<>();

		/*
		Save all headers into map for sending to the server.
		 */
		BufferedReader proxyToClientBr = new BufferedReader(new InputStreamReader(proxyToClientIs));
		try {
			String line = proxyToClientBr.readLine();
			boolean firstLine = true;
			while (!line.isEmpty()) {
				if (firstLine) {
					requestHeaders.put(null, line);
					firstLine = false;
				} else {
					int splitIndex = line.indexOf(':');
					requestHeaders.put(line.substring(0, splitIndex), line.substring(splitIndex + 2));
				}
				line = proxyToClientBr.readLine();
			}
		} catch (SocketTimeoutException ignored) {
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Error reading request from client");
			return;
		}

		/*
		No header is a wrong request.
		 */
		if (requestHeaders.isEmpty())
			return;

		/*
		Read request body up to the content-length if exist.
		 */
		int contentLength = (requestHeaders.get("Content-Length") != null)
				? Integer.parseInt(requestHeaders.get("Content-Length"))
				: (requestHeaders.get("Content-length") != null)
				? Integer.parseInt(requestHeaders.get("Content-length"))
				: -1;
		byte[] requestBody = null;
		if (contentLength != -1) {
			try {
				StringBuilder sb = new StringBuilder();
				char[] buffer = new char[1024];
				int readCount = 0;
				while (readCount < contentLength) {
					int read = proxyToClientBr.read(buffer);
					sb.append(buffer, 0, read);
					readCount += read;
				}
				requestBody = sb.toString().getBytes();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}


		/*
		Check if any url contains any blocked keywords
		 */
		String host = requestHeaders.get("Host");
		if (blockedSites.stream().anyMatch(host::contains)) {
			System.out.println("Blocked site requested : " + host + "\n");
			try {
				Helper.writeToAll(HTTP_NOT_FOUND + CRLF, proxyToClientOs);
				clientToProxySocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return;
		}

		/*
		Handles differently for
		1. HTTP GET found in Cache
		2. Rest
		 */
		String[] requestString = requestHeaders.get(null).split(" ");
		String connectionType = requestString[0];
		String urlString = requestString[1];
		if (cache.get(urlString) != null && connectionType.equals("GET")) {
			System.out.println("Cached Copy found for : " + urlString + "\n");
			sendCachedPageToClient(cache.get(urlString));
		} else {
			System.out.println("HTTP " + connectionType + " for: " + urlString + "\n");
			sendNonCachedToClient(urlString, requestHeaders, requestBody);
		}

		/*
		Close the socket would close the streams as well.
		 */
		try {
			clientToProxySocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void sendCachedPageToClient(File cachedFile) {
		try {
			Helper.writeToClient(new FileInputStream(cachedFile), proxyToClientOs);
		} catch (IOException e) {
			System.out.println(cachedFile.getName() + " not found locally.");
			if (!cachedFile.delete())
				System.out.println(cachedFile.getName() + " cannot be deleted.");
		}
	}

	private void sendNonCachedToClient(String urlString, Map<String, String> headers, byte[] requestBody) {
		try {
			int fileExtensionIndex = urlString.lastIndexOf(".");
			String fileName = urlString.substring(0, fileExtensionIndex)
					.replaceAll("[^0-9a-zA-Z]", "_");
			String fileExtension = urlString.substring(fileExtensionIndex);

			boolean checkHtml = fileExtension.contains("/");
			fileExtension = fileExtension.replaceAll("[^0-9a-zA-Z.]", "_");
			fileName += fileExtension + ((checkHtml || fileExtension.isEmpty()) ? ".html" : "");

			fileName = fileName.substring(0, Math.min(fileName.length(), 200));
			File fileToCache = new File(CACHE_FOLDER + "/" + fileName);

			boolean isHttps = headers.get(null).contains("CONNECT");
			FileOutputStream fileToCacheBW = (!isHttps) ? new FileOutputStream(fileToCache) : null;
			try {
				Socket proxyToServerSocket = Helper.sendRequest(headers, requestBody, proxyToClientIs);
				if (isHttps) {
					Helper.writeToAll(HTTP_OK + CRLF, proxyToClientOs);
					Helper.communicateDirectly(proxyToServerSocket.getInputStream(), proxyToClientOs);
				} else {
					Helper.writeToClient(proxyToServerSocket.getInputStream(), fileToCacheBW, proxyToClientOs);
					cache.put(urlString, fileToCache);
				}
				proxyToServerSocket.close();
			} catch (FileNotFoundException e) {
				if (!isHttps) {
					fileToCacheBW.close();
					Files.delete(fileToCache.toPath());
				}
				System.out.println(urlString + " not found in the remote server.");
				System.out.println();
			} catch (IOException ignored) {
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}