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
    static void writeToClient(DataInputStream dis, DataOutputStream... dataOutputStreams) throws IOException {
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
        writeToAll(headerSb.toString() + CRLF, dataOutputStreams);

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
                    writeToAll(bos.toByteArray(), dataOutputStreams);
                    bos.reset();
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
                    writeToAll(bos.toByteArray(), dataOutputStreams);
                    bos.reset();
                }
                bos.write(CRLF_BYTES);
            }
        } catch (SocketTimeoutException ignored) {
        }
        writeToAll(bos.toByteArray(), dataOutputStreams);
        bos.close();
        dis.close();

        for (DataOutputStream dataOutputStream : dataOutputStreams) {
            dataOutputStream.close();
        }
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

    private static void writeToAll(String line, DataOutputStream... outputStreams) throws IOException {
        writeToAll(line.getBytes(), outputStreams);
    }

    private static void writeToAll(byte[] line, DataOutputStream... outputStreams) throws IOException {
        for (DataOutputStream outputStream : outputStreams) {
            outputStream.write(line);
            outputStream.flush();
        }
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

    static void communicateDirectly(InputStream inputStream, OutputStream outputStream) throws IOException {
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
