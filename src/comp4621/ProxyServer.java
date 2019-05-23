package comp4621;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

class ProxyServer {
	static volatile boolean running = true;
	private static final String IP = "127.0.0.1";
	private static final int port = 4621;
	private static ServerSocket serverSocket;
	static ThreadPoolExecutor threadPool = (ThreadPoolExecutor) Executors.newCachedThreadPool();

	static {
		try {
			serverSocket = new ServerSocket(port, 50, InetAddress.getByName(IP));
			System.out.println("Proxy Server open at " + IP + ", Port:" + port + ".");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	static void listen() {
		while (running) {
			try {
				threadPool.execute(new RequestHandler(serverSocket.accept()));
			} catch(Exception ignored) {
			}
		}
	}

	static void close() {
		System.out.println("Closing Server..");
		running = false;

		try {
			serverSocket.close();
		} catch (IOException ignored) {
		}
	}
}
