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
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Tracker {

    private static final String TRACKER_TAG = "[Tracker]: ";

    private final int trackerPort;
    private final String trackerIp;
    private DatagramSocket trackerSocket;
    private final ScheduledExecutorService executor;
    private final Map<String, List<PeerInfo>> piecesInfoMap = new ConcurrentHashMap<>(); // Key:piece -> Value: List<PeerInfo>

    public Tracker(int trackerPort) {
        this.trackerPort = trackerPort;

        try {
            this.trackerIp = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }

        this.executor = Executors.newScheduledThreadPool(3);
    }

    public void start() {
        System.out.println(TRACKER_TAG + "Tracker iniciado na porta " + trackerPort);

        try {
            this.trackerSocket = new DatagramSocket(trackerPort);
            byte[] buffer = new byte[1024];

            // Loop de recepção das mensagens
            while (!this.trackerSocket.isClosed()) {
                DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                this.trackerSocket.receive(datagramPacket);
                this.executor.submit(() -> this.handleMessage(datagramPacket));
            }
        } catch (IOException e) {
            System.err.println(TRACKER_TAG + "Erro no socket: " + e.getMessage());
        } finally {
            if (this.trackerSocket != null && !this.trackerSocket.isClosed()) {
                this.trackerSocket.close();
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
            } catch (Exception e) {
                System.out.println(TRACKER_TAG + "Erro ao desserializar a mensagem: " + e.getMessage());
                return;
            }

            RequestMessage response = null;

            switch (request.getRequestType()) {
                case JOIN_TRACKER:
                case UPDATE_TRACKER:
                    response = this.handleJoinOrUpdate(request);
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
                trackerSocket.send(responsePacket);
            }
        } catch (Exception e){
            System.err.println(TRACKER_TAG + "Erro no processamento da mensagem: " + e.getMessage());
        }
    }

    private RequestMessage handleJoinOrUpdate(RequestMessage request) {
        this.registerOrUpdatePeerInfo(request);
        return this.sendPeerList(request);
    }

    private void registerOrUpdatePeerInfo(RequestMessage request) {
        PeerInfo peerInfo = this.generatePeerInfoFromRequest(request);
        List<String> peerPieces = BitTorrentUtils.extractData(request.getData(), DataType.PIECE_LIST);

        for (String piece : peerPieces) {
            List<PeerInfo> peersWithPiece = this.piecesInfoMap.get(piece);

            if (peersWithPiece == null) {
                this.piecesInfoMap.put(piece, List.of(peerInfo));
            } else if (peersWithPiece.stream().noneMatch(peerInList -> peerInList.getPeerAddress() != null && peerInList.getPeerAddress().equals(peerInfo.getPeerAddress()))) {
                peersWithPiece.add(peerInfo);
            }

        }

        System.out.println(TRACKER_TAG + "Atualizando piecesInfoMap com os pedaços do peer: " + peerInfo.getPeerAddress());
    }

    private RequestMessage sendPeerList(RequestMessage request) {
        PeerInfo peerInfo = this.generatePeerInfoFromRequest(request);
        RequestMessage requestMessage = new RequestMessage(this.trackerIp + ":" + this.trackerPort, RequestType.UPDATE_TRACKER);
        requestMessage.getData().put(DataType.SUCCESS, true);
        requestMessage.getData().put(DataType.PIECES_INFO_MAP, this.piecesInfoMap);

        System.out.println(TRACKER_TAG + "Enviada lista de peers para: " + peerInfo.getPeerAddress());
        return requestMessage;
    }

    private PeerInfo generatePeerInfoFromRequest(RequestMessage request) {
        String peerIp = BitTorrentUtils.extractData(request.getData(), DataType.IP);
        int peerPort = BitTorrentUtils.extractData(request.getData(), DataType.PORT);
        return new PeerInfo(peerIp, peerPort);
    }
}