package org.bittorrent.tracker;

import org.bittorrent.peer.PeerInfo;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Tracker {
    private final int port;
    private DatagramSocket socket;
    private final Map<String, Map<String, PeerInfo>> torrents = new ConcurrentHashMap<>();

    public Tracker(int port) {
        this.port = port;
    }

    public void start() {
        System.out.println("Tracker iniciado na porta " + port);

        try {
            this.socket = new DatagramSocket(port);
            byte[] buffer = new byte[1024];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                this.socket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                InetAddress clientAddress = packet.getAddress();
                int clientPort = packet.getPort();

                handleMessage(message, clientAddress.getHostAddress(), clientPort);
            }
        } catch (IOException e) {
            System.err.println("Erro no Tracker: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (this.socket != null && !this.socket.isClosed()) {
                this.socket.close();
            }
        }
    }

    private void handleMessage(String message, String clientIp, int clientPort) {
        System.out.println("Tracker recebeu de " + clientIp + ":" + clientPort + " -> " + message);
        String[] parts = message.split(":", 3);
        String command = parts[0];
        String torrentName = parts[1];

        // Garante que a estrutura de mapa para o torrent existe
        this.torrents.putIfAbsent(torrentName, new ConcurrentHashMap<>());

        switch (command) {
            case "JOIN":
                handleJoin(torrentName, clientIp, clientPort);
                break;
            case "UPDATE":
                String piecesStr = (parts.length > 2) ? parts[2] : "";
                handleUpdate(torrentName, clientIp, clientPort, piecesStr);
                break;
        }
    }

    private void handleJoin(String torrentName, String clientIp, int clientPort) {
        // Quando um novo peer entra, ele pode ou não ter peças.
        // O primeiro 'join' é tratado como uma solicitação de lista.
        // A lógica de atualização cuidará de adicionar as peças.
        sendPeerList(torrentName, clientIp, clientPort);
    }

    private void handleUpdate(String torrentName, String clientIp, int clientPort, String piecesStr) {
        Map<String, PeerInfo> peers = this.torrents.get(torrentName);
        String peerAddress = clientIp + ":" + clientPort;

        Set<String> pieces = Arrays.stream(piecesStr.split(","))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        PeerInfo peerInfo = peers.get(peerAddress);
        if (peerInfo == null) {
            // Novo peer se junta com suas peças iniciais
            peerInfo = new PeerInfo(clientIp, clientPort, pieces);
            peers.put(peerAddress, peerInfo);
            System.out.println("Novo peer adicionado: " + peerAddress + " com peças: " + piecesStr);
        } else {
            // Peer existente atualiza sua lista de peças
            peerInfo.getPieces().clear();
            peerInfo.getPieces().addAll(pieces);
            peerInfo.setLastSeen(System.currentTimeMillis());
            System.out.println("Peer atualizado: " + peerAddress + " com peças: " + piecesStr);
        }

        // Após uma atualização, envia a lista atualizada de peers para o solicitante
        sendPeerList(torrentName, clientIp, clientPort);
    }


    private void sendPeerList(String torrentName, String clientIp, int clientPort) {
        Map<String, PeerInfo> peers = torrents.get(torrentName);
        if (peers == null) return;

        // Constrói a string da lista de peers: PEER_LIST:TORRENT|IP1:PORTA1:PEÇA1,PEÇA2|IP2:PORTA2:PEÇA3...
        String peerListStr = peers.values().stream()
                .map(PeerInfo::toString)
                .collect(Collectors.joining("|"));

        String responseMessage = "PEER_LIST:" + torrentName + "|" + peerListStr;

        try {
            byte[] responseData = responseMessage.getBytes();
            InetAddress address = InetAddress.getByName(clientIp);
            DatagramPacket responsePacket = new DatagramPacket(responseData, responseData.length, address, clientPort);
            this.socket.send(responsePacket);
            System.out.println("Enviada lista de peers para " + clientIp + ":" + clientPort);
        } catch (IOException e) {
            System.err.println("Erro ao enviar lista de peers: " + e.getMessage());
        }
    }
}
