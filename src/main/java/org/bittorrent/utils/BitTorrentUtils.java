package org.bittorrent.utils;

import org.bittorrent.message.DataType;
import org.bittorrent.message.RequestMessage;
import org.bittorrent.peer.PeerInfo;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
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

    public static String getLocalIPv4() throws SocketException {
        Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();

        while (nics.hasMoreElements()) {
            NetworkInterface nic = nics.nextElement();
            if (nic.isUp() && !nic.isLoopback()) {
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        }
        throw new RuntimeException("Não encontrou IP de rede local");
    }

    public static PeerInfo generatePeerInfoFromRequest(RequestMessage request) {
        String peerIp = BitTorrentUtils.extractData(request.getData(), DataType.IP);
        int peerPort = BitTorrentUtils.extractData(request.getData(), DataType.PORT);
        return new PeerInfo(peerIp, peerPort);
    }

    @SuppressWarnings("unchecked")
    public static  <T> T extractData(Map<DataType, Object> data, DataType dataType) {
        return (T) data.get(dataType);
    }
}
