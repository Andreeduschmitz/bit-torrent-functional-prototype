package org.bittorrent.tracker;

import org.bittorrent.message.DataType;
import org.bittorrent.message.RequestMessage;
import org.bittorrent.message.RequestType;
import org.bittorrent.peer.PeerInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Tracker {
    private final int port;
    private DatagramSocket socket;
    private final Map<String, PeerInfo> peers = new ConcurrentHashMap<>();

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
                byte[] requestMessage = Arrays.copyOf(packet.getData(), packet.getLength());

                // IP e porta usados somente para log, para lógica usado IP e Porta da mensagem
                InetAddress clientAddress = packet.getAddress();
                int clientPort = packet.getPort();

                handleMessage(requestMessage, clientAddress.getHostAddress(), clientPort);
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

    private void handleMessage(byte[] requestMessage, String clientIp, int clientPort) {
        System.out.println("Tracker recebeu uma requisição de " + clientIp + ":" + clientPort);
        RequestMessage request;

        try (ByteArrayInputStream bytes = new ByteArrayInputStream(requestMessage); ObjectInputStream in = new ObjectInputStream(bytes)) {
            request = (RequestMessage) in.readObject();
        } catch(Exception e) {
            System.out.println("Erro ao receber a mensagem no Tracker: " + e.getMessage());
            return;
        }

        RequestMessage response;

        switch (request.getRequestType()) {
            case JOIN_TRACKER:
                response = handleJoin(request);
                break;
            case UPDATE_TRACKER:
                response = handleUpdate(request);
                break;
            default:
                // Mensagem de erro
        }
    }

    private RequestMessage handleJoin(RequestMessage request) {
        this.registerPeer(request);
        return this.sendPeerList(request);
    }

    private RequestMessage handleUpdate(RequestMessage request) {
        String peerAddress = this.generateAddressFromRequest(request);

        List<String> peerPieces = (List<String>) request.getData().get(DataType.PIECE_LIST);

        PeerInfo peerInfo = this.peers.get(peerAddress);

        if (peerInfo == null) {
            this.registerPeer(request);
        } else {
            peerInfo.getPieces().clear();
            peerInfo.getPieces().addAll(peerPieces);
            peerInfo.setLastSeen(System.currentTimeMillis());
            System.out.println("Peer atualizado: " + peerAddress + " com peças: " + peerPieces);
        }


        return sendPeerList(request);
    }

    private void registerPeer(RequestMessage request) {
        String peerIp = (String) request.getData().get(DataType.IP);
        int peerPort = (int) request.getData().get(DataType.PORT);
        String peerAddress = peerIp + ":" + peerPort;
        Set<String> piecesList = (Set<String>) request.getData().get(DataType.PIECE_LIST);

        PeerInfo peerInfo = this.peers.get(peerAddress);

        if (peerInfo == null) {
            peerInfo = new PeerInfo(peerIp, peerPort, piecesList);
            peers.put(peerAddress, peerInfo);
            System.out.println("Novo peer adicionado: " + peerAddress);
        } else {
            peerInfo.getPieces().clear();
            peerInfo.getPieces().addAll(piecesList);
            peerInfo.setLastSeen(System.currentTimeMillis());
            System.out.println("Peer atualizado: " + peerAddress + " com peças: " + piecesList);
        }
    }

    private RequestMessage sendPeerList(RequestMessage request) {
        String peerAddress = this.generateAddressFromRequest(request);

        Map<String, PeerInfo> peers = this.peers.entrySet().stream()
            .filter(entry -> !entry.getValue().getPeerAddress().equals(peerAddress))
            .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue
            ));

        RequestMessage requestMessage = new RequestMessage(peerAddress, RequestType.UPDATE_TRACKER);
        requestMessage.getData().put(DataType.SUCCESS, true);
        requestMessage.getData().put(DataType.PIECES_PER_PEER, peers);

        System.out.println("Enviada lista de peers para: " + peerAddress);
        return requestMessage;
    }

    private String generateAddressFromRequest(RequestMessage request) {
        String peerIp = (String) request.getData().get(DataType.IP);
        int peerPort = (int) request.getData().get(DataType.PORT);
        return peerIp + ":" + peerPort;
    }
}
