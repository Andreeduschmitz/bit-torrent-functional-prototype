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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Tracker {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String TRACKER_TAG = "[Tracker]: ";

    private final int trackerPort;
    private final String trackerIp;
    private DatagramSocket trackerSocket;
    private final ScheduledExecutorService executor;
    private final Map<String, List<PeerInfo>> piecesInfoMap = new ConcurrentHashMap<>();// Key:piece -> Value: List<PeerInfo>

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
        this.log("Tracker iniciado na porta " + trackerPort, false);

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
            this.log("Erro no socket: " + e.getMessage(), true);
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

            this.log("Recebeu uma requisição de " + clientAddress, false);
            RequestMessage request;

            try (ByteArrayInputStream bytes = new ByteArrayInputStream(requestMessage); ObjectInputStream in = new ObjectInputStream(bytes)) {
                request = (RequestMessage) in.readObject();
            } catch (Exception e) {
                this.log("Erro ao desserializar a mensagem: " + e.getMessage(), true);
                return;
            }

            RequestMessage response = null;

            switch (request.getRequestType()) {
                case JOIN_TRACKER:
                case UPDATE_TRACKER:
                    response = this.handleJoinOrUpdate(request);
                    break;
                default:
                    this.log("Recebeu uma requisição sem tipo definido do Peer: " + clientAddress, false);
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
            this.log("Erro no processamento da mensagem: " + e.getMessage(), true);
        }
    }

    private RequestMessage handleJoinOrUpdate(RequestMessage request) {
        this.registerOrUpdatePeerInfo(request);
        return this.sendPeerList(request);
    }

    private void registerOrUpdatePeerInfo(RequestMessage request) {
        PeerInfo peerInfo = BitTorrentUtils.generatePeerInfoFromRequest(request);
        Set<String> peerPieces = BitTorrentUtils.extractData(request.getData(), DataType.PIECE_LIST);

        for (String piece : peerPieces) {
            List<PeerInfo> peersWithPiece = this.piecesInfoMap.get(piece);

            if (peersWithPiece == null) {
                this.piecesInfoMap.put(piece, new ArrayList<>(List.of(peerInfo)));
            } else if (peersWithPiece.stream().noneMatch(peerInList -> peerInList.getPeerAddress() != null && peerInList.getPeerAddress().equals(peerInfo.getPeerAddress()))) {
                this.piecesInfoMap.get(piece).add(peerInfo);
            }

        }

        this.log("Atualizando piecesInfoMap com os pedaços do peer: " + peerInfo.getPeerAddress() + ". Pedaços: " + peerPieces, false);
    }

    private RequestMessage sendPeerList(RequestMessage request) {
        PeerInfo peerInfo = BitTorrentUtils.generatePeerInfoFromRequest(request);
        RequestMessage requestMessage = new RequestMessage(this.trackerIp + ":" + this.trackerPort, RequestType.UPDATE_TRACKER);
        requestMessage.getData().put(DataType.SUCCESS, true);
        requestMessage.getData().put(DataType.PIECES_INFO_MAP, this.piecesInfoMap);

        this.log("Enviada lista de pedaços para: " + peerInfo.getPeerAddress(), false);
        return requestMessage;
    }

    private void log(String message, boolean error) {
        String timestamp = LocalDateTime.now().format(this.formatter);

        if (error) {
            System.err.println(timestamp + TRACKER_TAG + message);
        } else {
            System.out.println(timestamp + TRACKER_TAG + message);
        }
    }
}