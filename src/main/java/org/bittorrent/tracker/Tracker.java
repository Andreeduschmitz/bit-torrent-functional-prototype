package org.bittorrent.tracker;

import org.bittorrent.message.DataType;
import org.bittorrent.message.RequestMessage;
import org.bittorrent.message.RequestType;
import org.bittorrent.peer.PeerInfo;
import org.bittorrent.utils.BitTorrentUtils;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

public class Tracker {
    private final int port;
    private DatagramSocket socket;
    private final ScheduledExecutorService executor;
    private final Map<String, PeerInfo> peersInfoMap = new ConcurrentHashMap<>();

    private static final String TRACKER_TAG = "[Tracker]: ";

    public Tracker(int port) {
        this.port = port;
        this.executor = Executors.newScheduledThreadPool(3);
    }

    public void start() {
        System.out.println(TRACKER_TAG + "Tracker iniciado na porta " + port);

        try {
            this.socket = new DatagramSocket(port);
            byte[] buffer = new byte[1024];

            // Loop de recepção das mensagens
            while (!this.socket.isClosed()) {
                DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                this.socket.receive(datagramPacket);
                this.executor.submit(() -> this.handleMessage(datagramPacket));
            }
        } catch (IOException e) {
            System.err.println(TRACKER_TAG + "Erro no socket: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (this.socket != null && !this.socket.isClosed()) {
                this.socket.close();
            }
        }
    }

    private void handleMessage(DatagramPacket datagramPacket) {
        try {
            byte[] requestMessage = Arrays.copyOf(datagramPacket.getData(), datagramPacket.getLength());

            // IP e porta usados somente para log e envio da resposta, para lógica usado IP e Porta da mensagem
            InetAddress clientIp = datagramPacket.getAddress();
            int clientPort = datagramPacket.getPort();
            String clientAddress = clientIp + ":" + clientPort;

            System.out.println(TRACKER_TAG + "Recebeu uma requisição de " + clientAddress);
            RequestMessage request;

            try (ByteArrayInputStream bytes = new ByteArrayInputStream(requestMessage); ObjectInputStream in = new ObjectInputStream(bytes)) {
                request = (RequestMessage) in.readObject();
            } catch(Exception e) {
                System.out.println(TRACKER_TAG + "Erro ao receber a mensagem: " + e.getMessage());
                return;
            }

            RequestMessage response = null;

            switch (request.getRequestType()) {
                case JOIN_TRACKER:
                    response = this.handleJoin(request);
                    break;
                case UPDATE_TRACKER:
                    response = this.handleUpdate(request);
                    break;
                default:
                    System.out.println(TRACKER_TAG + "Recebeu uma requisição sem tipo definido do peer: " + clientAddress);
            }

            if (response != null) {
                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                ObjectOutputStream out = new ObjectOutputStream(byteOut);
                out.writeObject(response);
                out.flush();
                byte[] responseData = byteOut.toByteArray();

                DatagramPacket responsePacket = new DatagramPacket(responseData, responseData.length, clientIp, clientPort);
                socket.send(responsePacket);
            }
        } catch (Exception e){
            System.err.println(TRACKER_TAG + "Erro no processamento da mensagem: " + e.getMessage());
        }
    }

    private RequestMessage handleJoin(RequestMessage request) {
        this.registerPeer(request);
        return this.sendPeerList(request);
    }

    private RequestMessage handleUpdate(RequestMessage request) {
        String peerAddress = this.generateAddressFromRequest(request);

        List<String> peerPieces = BitTorrentUtils.extractData(request.getData(), DataType.PIECE_LIST);
        PeerInfo peerInfo = this.peersInfoMap.get(peerAddress);

        if (peerInfo == null) {
            this.registerPeer(request);
        } else {
            peerInfo.getPieces().clear();
            peerInfo.getPieces().addAll(peerPieces);
            peerInfo.setLastSeen(System.currentTimeMillis());
            System.out.println(TRACKER_TAG + "Peer atualizado: " + peerAddress + " com peças: " + peerPieces);
        }


        return sendPeerList(request);
    }

    private void registerPeer(RequestMessage request) {
        String peerIp = BitTorrentUtils.extractData(request.getData(), DataType.IP);
        int peerPort = BitTorrentUtils.extractData(request.getData(), DataType.PORT);
        String peerAddress = peerIp + ":" + peerPort;
        Set<String> piecesList = BitTorrentUtils.extractData(request.getData(), DataType.PIECE_LIST);

        PeerInfo peerInfo = this.peersInfoMap.get(peerAddress);

        if (peerInfo == null) {
            peerInfo = new PeerInfo(peerIp, peerPort, piecesList);
            peersInfoMap.put(peerAddress, peerInfo);
            System.out.println(TRACKER_TAG + "Novo peer adicionado: " + peerAddress);
        } else {
            peerInfo.getPieces().clear();
            peerInfo.getPieces().addAll(piecesList);
            peerInfo.setLastSeen(System.currentTimeMillis());
            System.out.println(TRACKER_TAG + "Peer atualizado: " + peerAddress + " com peças: " + piecesList);
        }
    }

    private RequestMessage sendPeerList(RequestMessage request) {
        String peerAddress = this.generateAddressFromRequest(request);

        Map<String, PeerInfo> peers = this.peersInfoMap.entrySet().stream()
            .filter(entry -> !entry.getValue().getPeerAddress().equals(peerAddress))
            .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue
            ));

        RequestMessage requestMessage = new RequestMessage(peerAddress, RequestType.UPDATE_TRACKER);
        requestMessage.getData().put(DataType.SUCCESS, true);
        requestMessage.getData().put(DataType.PIECES_PER_PEER, peers);

        System.out.println(TRACKER_TAG + "Enviada lista de peers para: " + peerAddress);
        return requestMessage;
    }

    private String generateAddressFromRequest(RequestMessage request) {
        String peerIp = BitTorrentUtils.extractData(request.getData(), DataType.IP);
        int peerPort = BitTorrentUtils.extractData(request.getData(), DataType.PORT);
        return peerIp + ":" + peerPort;
    }
}