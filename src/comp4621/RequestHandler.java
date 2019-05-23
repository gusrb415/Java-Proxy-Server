package comp4621;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

class RequestHandler implements Runnable {
	private Socket clientToProxySocket;
	private BufferedReader proxyToClientBr;
	private DataOutputStream proxyToClientDos;

	private static Map<String, File> cache = new ConcurrentHashMap<>();
	private static List<String> blockedSites = new ArrayList<>();
	private static ThreadPoolExecutor threadPool = ProxyServer.threadPool;

	private final static String CRLF = Helper.CRLF;
	private final static String CACHE_FOLDER = "Cached";
	private final static String HTTP_OK = "HTTP/1.1 200 OK" + CRLF + "Proxy-agent: ProxyServer/1.0" + CRLF;
	private final static String HTTP_NOT_FOUND = "HTTP/1.1 404 NOT FOUND" + CRLF +"Proxy-agent: ProxyServer/1.0" + CRLF;

	RequestHandler(Socket clientSocket) {
		this.clientToProxySocket = clientSocket;
		try {
			clientToProxySocket.setSoTimeout(3000);
			proxyToClientBr = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			proxyToClientDos = new DataOutputStream(clientSocket.getOutputStream());
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
			blockedSites.add(url);
			System.out.println(url + " added to the block list");
		}
	}

	@Override
	public void run() {
		Map<String, String> headers = new HashMap<>();
		int contentLength = -1;
		try {
			String line = proxyToClientBr.readLine();
			while (!line.isEmpty()) {
				if (line.toLowerCase().contains("content-length")) {
					contentLength = Integer.parseInt(line.split(" ")[1]);
				}
				if (line.contains("HTTP/1."))
					headers.put(null, line);
				else {
					int splitIndex = line.indexOf(':');
					headers.put(line.substring(0, splitIndex), line.substring(splitIndex + 2));
				}
				line = proxyToClientBr.readLine();
			}
		} catch (SocketTimeoutException ignored) {
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Error reading request from client");
			return;
		}

		if (headers.isEmpty())
			return;

		String requestBody = null;
		if (contentLength != -1) {
			try {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < contentLength; ++i) {
					char temp = (char) proxyToClientBr.read();
					sb.append(temp);
				}
				requestBody = sb.toString();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		String[] requestString = headers.get(null).split(" ");
		String connectionType = requestString[0];
		String urlString = requestString[1];


		if (blockedSites.stream().anyMatch(urlString::contains)) {
			System.out.println("Blocked site requested : " + urlString + "\n");
			try {
				proxyToClientDos.writeBytes(HTTP_NOT_FOUND + CRLF);
				proxyToClientDos.close();
				proxyToClientBr.close();
				clientToProxySocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return;
		}

		if (connectionType.equals("CONNECT")) {
			System.out.println("HTTPS Request for : " + urlString + "\n");
			handleHTTPSRequest(urlString);
		} else {
			if (cache.get(urlString) != null && connectionType.equals("GET")) {
				System.out.println("Cached Copy found for : " + urlString + "\n");
				sendCachedPageToClient(cache.get(urlString));
			} else {
				System.out.println("HTTP " + connectionType + " for: " + urlString + "\n");
				sendNonCachedToClient(urlString, headers, requestBody);
			}
		}

		try {
			proxyToClientBr.close();
			proxyToClientDos.close();
			clientToProxySocket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void sendCachedPageToClient(File cachedFile) {
		try {
			DataInputStream dis = new DataInputStream(new FileInputStream(cachedFile));
			Helper.writeToClient(dis, null, proxyToClientDos);
		} catch (IOException e) {
			System.out.println(cachedFile.getName() + " not found locally.");
			if(!cachedFile.delete())
				System.out.println(cachedFile.getName() + " cannot be deleted.");
		}
	}

	private void sendNonCachedToClient(String urlString, Map<String, String> headers, String requestBody) {
		try {
			int fileExtensionIndex = urlString.lastIndexOf(".");
			String fileName = urlString.substring(0, fileExtensionIndex)
					.replaceAll("[^0-9a-zA-Z]", "_");
			String fileExtension = urlString.substring(fileExtensionIndex);

			boolean checkHtml = fileExtension.contains("/");
			fileExtension = fileExtension.replaceAll("[^0-9a-zA-Z.]", "_");
			fileName += fileExtension + ((checkHtml || fileExtension.isEmpty()) ? ".html" : "");

			File fileToCache = new File(CACHE_FOLDER + "/" + fileName);
			DataOutputStream fileToCacheBW = new DataOutputStream(new FileOutputStream(fileToCache));
			try {
				Socket proxyToServerSocket = Helper.sendRequest(headers, requestBody);
				DataInputStream dis = new DataInputStream(proxyToServerSocket.getInputStream());
				Helper.writeToClient(dis, fileToCacheBW, proxyToClientDos);
				cache.put(urlString, fileToCache);
				fileToCacheBW.close();
			} catch (FileNotFoundException e) {
				fileToCacheBW.close();
				Files.delete(fileToCache.toPath());
				System.out.println(urlString + " not found in the remote server.");
				System.out.println();
			} catch (IOException ignored) {
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void handleHTTPSRequest(String urlWithPort) {
		String url = urlWithPort.split(":")[0];
		int port = Integer.parseInt(urlWithPort.split(":")[1]);
		try {
			Socket proxyToServerSocket = new Socket(InetAddress.getByName(url), port);
			proxyToServerSocket.setSoTimeout(3000);

			proxyToClientDos.writeBytes(HTTP_OK + CRLF);
			proxyToClientDos.flush();

			threadPool.execute(() -> {
				try {
					communicateDirectly(clientToProxySocket.getInputStream(), proxyToServerSocket.getOutputStream());
				} catch (IOException ignored) {
				}
			});
			communicateDirectly(proxyToServerSocket.getInputStream(), clientToProxySocket.getOutputStream());
			proxyToServerSocket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void communicateDirectly(InputStream inputStream, OutputStream outputStream) throws IOException {
		try {
			byte[] buffer = new byte[4096];
			int read;
			do {
				read = inputStream.read(buffer);
				if (read > 0) {
					outputStream.write(buffer, 0, read);
					if (inputStream.available() < 1) {
						outputStream.flush();
					}
				}
			} while (read >= 0);
		} catch (SocketTimeoutException | SocketException ignored) {
		}
	}
}