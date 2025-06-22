package org.bittorrent.tracker;

import org.bittorrent.message.DataType;
import org.bittorrent.message.RequestMessage;
import org.bittorrent.peer.PeerInfo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
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
                InetAddress clientAddress = packet.getAddress();
                byte[] requestMessage = Arrays.copyOf(packet.getData(), packet.getLength());
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

        switch (request.getRequestType()) {
            case JOIN_TRACKER:
                handleJoin(request);
                break;
            case UPDATE_TRACKER:
                handleUpdate(request);
                break;

            default:
                // Mensagem de erro
        }
    }

    private void handleJoin(RequestMessage request) {
        // Quando um novo peer entra, ele pode ou não ter peças.
        // O primeiro 'join' é tratado como uma solicitação de lista.
        // A lógica de atualização cuidará de adicionar as peças.
        sendPeerList(request.get);
    }

    private void handleUpdate(RequestMessage request) {
        Map<String, PeerInfo> peers = this.peers.get(torrentName);
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

    private void registerPeer(RequestMessage request) {

    }

    private void sendPeerList(String clientIp, int clientPort) {
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
