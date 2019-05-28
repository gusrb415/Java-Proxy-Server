package comp4621;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Helper static methods for Request Handler
 */
class Helper {
    final static String CRLF = "\r\n";
    private static ThreadPoolExecutor threadPool = ProxyServer.threadPool;
    private final static int BUFFER_SIZE = 131072;
    private final static byte[] CRLF_BYTES = CRLF.getBytes();

    @SuppressWarnings("deprecation")
    static void writeToClient(InputStream is, OutputStream... outputStreams) throws IOException {
        StringBuilder headerSb = new StringBuilder();
        DataInputStream dis = new DataInputStream(is);
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
        writeToAll(headerSb.toString() + CRLF, outputStreams);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[BUFFER_SIZE];
            if (!chunked) {
                int bytesRead = 0;
                while (bytesRead < length || length == -1) {
                    int read = dis.read(buffer);
                    if (read == -1) {
                        break;
                    }
                    bos.write(buffer, 0, read);
                    bytesRead += read;
                    writeToAll(bos.toByteArray(), outputStreams);
                    bos.reset();
                }
            } else {
                while (true) {
                    String hexChunkSize = dis.readLine();
                    bos.write(hexChunkSize.getBytes());
                    bos.write(CRLF_BYTES);
                    if (hexChunkSize.equals("0"))
                        break;
                    int chunkSize = hexToDec(hexChunkSize) + 2;
                    while (chunkSize > 0) {
                        int read = dis.read(buffer, 0, chunkSize);
                        bos.write(buffer, 0, read);
                        chunkSize -= read;
                    }
                    writeToAll(bos.toByteArray(), outputStreams);
                    bos.reset();
                }
                bos.write(CRLF_BYTES);
            }
        } catch (SocketTimeoutException ignored) {
        }
        writeToAll(bos.toByteArray(), outputStreams);
        bos.close();
        dis.close();

        for (OutputStream outputStream : outputStreams) {
            outputStream.close();
        }
    }

    static Socket sendRequest(Map<String, String> headers, byte[] requestBody, InputStream dis) throws IOException {
        String[] hostArr = headers.get("Host").split(":");
        boolean isHttps = headers.get(null).contains("CONNECT");
        int port = (hostArr.length > 1) ? Integer.parseInt(hostArr[1]) : 80;

        Socket socket = new Socket(hostArr[0], port);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        threadPool.execute(() -> {
            try {
                OutputStream dos = socket.getOutputStream();
                if(!isHttps) {
                    headers.forEach((key, value) -> {
                        try {
                            String line = (key == null ? value : (key + ": " + value)) + CRLF;
                            writeToAll(line, dos);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    });
                    writeToAll(CRLF, dos);
                    if (requestBody != null) {
                        writeToAll(requestBody, dos);
                        writeToAll(CRLF_BYTES, dos);
                    }
                }

                if(isHttps && dis != null) {
                    communicateDirectly(dis, dos);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        return socket;
    }

    static void writeToAll(String line, OutputStream... outputStreams) throws IOException {
        writeToAll(line.getBytes(), outputStreams);
    }

    private static void writeToAll(byte[] line, OutputStream... outputStreams) throws IOException {
        writeToAll(line, line.length, outputStreams);
    }

    private static void writeToAll(byte[] line, int size, OutputStream... outputStreams) throws IOException {
        for (OutputStream outputStream : outputStreams) {
            outputStream.write(line, 0, size);
            outputStream.flush();
        }
    }

    private static int hexToDec(String hex) {
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

    static void communicateDirectly(InputStream inputStream, OutputStream outputStream) throws IOException {
        try {
            byte[] buffer = new byte[4096];
            int read = inputStream.read(buffer);
            while (read > -1) {
                writeToAll(buffer, read, outputStream);
                read = inputStream.read(buffer);
            }
        } catch (SocketTimeoutException | SocketException ignored) {
        }
    }
}
