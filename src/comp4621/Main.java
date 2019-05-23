package comp4621;

import java.util.Scanner;

public class Main {
    public static void main(String[] args) {
        String[] blockList = {
                "abc"
        };

        Thread command = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);

            String instruction = "Type \"clear\" to clear caches or Type \"close\" to close server.";
            System.out.println(instruction);
            String input;
            while (ProxyServer.running) {
                input = scanner.nextLine().toLowerCase();
                if (input.equals("close"))
                    ProxyServer.close();
                else if (input.equals("clear"))
                    RequestHandler.clearCaches();
                else
                    System.out.println(instruction);
            }
            scanner.close();
        });

        command.setDaemon(true);
        command.start();
        RequestHandler.blockUrl(args);
        RequestHandler.blockUrl(blockList);
        RequestHandler.clearCaches();
        ProxyServer.listen();
    }
}
