package comp4621;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Helper static methods for Request Handler
 */
class Helper {
    final static String CRLF = "\r\n";
    private static ThreadPoolExecutor threadPool = ProxyServer.threadPool;
    private final static int BUFFER_SIZE = 16384;
    private final static byte[] CRLF_BYTES = CRLF.getBytes();

    @SuppressWarnings("deprecation")
    static void writeToClient(DataInputStream dis, DataOutputStream cacheDos, DataOutputStream proxyToClientDos) throws IOException {
        StringBuilder headerSb = new StringBuilder();
        String line = dis.readLine();
        int length = -1;
        boolean chunked = false;
        while (!line.isEmpty()) {
            if (line.toLowerCase().startsWith("content-length")) {
                length = Integer.parseInt(line.split(" ")[1]);
            } else if (line.toLowerCase().startsWith("transfer-encoding")) {
                chunked = line.split(" ")[1].toLowerCase().equals("chunked");
            }

            headerSb.append(line).append(CRLF);
            line = dis.readLine();
        }
        if (length != -1)
            headerSb.append("Content-Length: ").append(length).append(CRLF);
        writeToAll(headerSb.toString() + CRLF, cacheDos, proxyToClientDos);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            if (!chunked) {
                int bytesRead = 0;
                while (bytesRead < length || length == -1) {
                    int read = dis.read(buffer, 0, BUFFER_SIZE);
                    if (read == -1) {
                        break;
                    }
                    bos.write(buffer, 0, read);

                    bytesRead += read;
                }
            } else {
                while (true) {
                    String hexChunkSize = dis.readLine();
                    bos.write(hexChunkSize.getBytes());
                    bos.write(CRLF_BYTES);
                    if (hexChunkSize.equals("0"))
                        break;
                    int chunkSize = getDecimal(hexChunkSize) + 2;
                    while (chunkSize > 0) {
                        int read = dis.read(buffer, 0, chunkSize);
                        bos.write(buffer, 0, read);
                        chunkSize -= read;
                    }
                }
                bos.write(CRLF_BYTES);
            }
        } catch (SocketTimeoutException ignored) {
        }
        writeToAll(bos.toByteArray(), cacheDos, proxyToClientDos);
        bos.close();
        dis.close();
        if (cacheDos != null) cacheDos.close();
    }

    static Socket sendRequest(Map<String, String> headers, String requestBody) throws IOException {
        String host = headers.get("Host");
        Socket socket = new Socket(host, 80);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        threadPool.execute(() -> {
            try {
                byte[] lineBreak = "\r\n".getBytes();
                OutputStream os = socket.getOutputStream();
                headers.forEach((key, value) -> {
                    try {
                        os.write((key == null) ? value.getBytes() : (key + ": " + value).getBytes());
                        os.write(lineBreak);
                    } catch (IOException ignored) {
                    }
                });
                os.write(lineBreak);
                if (requestBody != null) {
                    os.write(requestBody.getBytes());
                    os.write(lineBreak);
                }
                os.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        return socket;
    }

    private static void writeToAll(String line, DataOutputStream bwForCache, DataOutputStream proxyToClientDos) throws IOException {
        if (bwForCache != null) {
            bwForCache.writeBytes(line);
            bwForCache.flush();
        }
        proxyToClientDos.writeBytes(line);
        proxyToClientDos.flush();
    }

    private static void writeToAll(byte[] line, DataOutputStream bwForCache, DataOutputStream proxyToClientDos) throws IOException {
        if (bwForCache != null) {
            bwForCache.write(line);
            bwForCache.flush();
        }
        proxyToClientDos.write(line);
        proxyToClientDos.flush();
    }

    private static int getDecimal(String hex) {
        String digits = "0123456789ABCDEF";
        hex = hex.toUpperCase();
        int val = 0;
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            int d = digits.indexOf(c);
            val = 16 * val + d;
        }
        return val;
    }
}
