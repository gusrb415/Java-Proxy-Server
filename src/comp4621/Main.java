package comp4621;

import java.util.Scanner;

/**
 * Main accepts websites to block for both HTTP and HTTPS (full url or keyword of the url can be used).
 * e.g. To block google.com, just google can be used.
 * Commands:
 * "clear": to clear caches.
 * "close": to close the server and remove caches.
 */
public class Main {
    public static void main(String[] args) {
        String[] blockList = {
                "abc.com"
        };

        Thread activeThreadCounter = new Thread(() -> {
            int counter = 0;
            try {
                while (ProxyServer.running) {
                    Thread.sleep(10_000);
                    System.out.println("The server has been running for " + ++counter * 10 + " seconds");
                    System.out.println("Current thread pool size: " + ProxyServer.threadPool.getPoolSize());
                    System.out.println();
                }
            } catch (InterruptedException ignored) {
            }
        });

        Thread command = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            String instruction = "Type \"clear\" to clear caches or Type \"close\" to close server.";
            System.out.println(instruction);
            String input;
            while (ProxyServer.running) {
                input = scanner.nextLine().toLowerCase();
                if (input.equals("close")) {
                    activeThreadCounter.interrupt();
                    ProxyServer.close();
                } else if (input.equals("clear"))
                    RequestHandler.clearCaches();
                else
                    System.out.println(instruction);
            }
            scanner.close();
        });

        command.setDaemon(true);
        command.start();
        activeThreadCounter.setDaemon(true);
        activeThreadCounter.start();
        RequestHandler.blockUrl(args);
        RequestHandler.blockUrl(blockList);
        RequestHandler.clearCaches();
        ProxyServer.listen();
    }
}
