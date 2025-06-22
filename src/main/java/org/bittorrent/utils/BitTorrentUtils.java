package org.bittorrent.utils;

import java.util.Scanner;
import java.util.regex.Pattern;

public class BitTorrentUtils {
    private static final Pattern IP_PATTERN = Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)$");

    public static int requestPort(Scanner scanner) {
        int port = -1;

        while (true) {
            String input = scanner.nextLine();

            try {
                port = Integer.parseInt(input);
                if (port >= 1 && port <= 65535) {
                    return port;
                } else {
                    System.out.println("O número da porta deve estar entre 1 e 65535.");
                }
            } catch (NumberFormatException e) {
                System.out.println("Entrada inválida. Digite um número inteiro.");
            }
        }
    }

    public static String requestIp(Scanner scanner) {

        while (true) {
            String entrada = scanner.nextLine().trim();

            if (IP_PATTERN.matcher(entrada).matches()) {
                return entrada;
            } else {
                System.out.println("Endereço IP inválido. Tente novamente.");
            }
        }
    }
}
