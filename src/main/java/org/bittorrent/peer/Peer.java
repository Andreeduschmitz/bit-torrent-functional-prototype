package org.bittorrent.peer;

import org.bittorrent.connection.TCPConnection;
import org.bittorrent.message.DataType;
import org.bittorrent.message.RequestMessage;
import org.bittorrent.message.RequestType;
import org.bittorrent.utils.BitTorrentUtils;
import org.bittorrent.utils.FileUtils;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class Peer {

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final String PEER_TAG = "[Peer]: ";
    private static final String FILES_BASE_PATH = "./peerFiles/";
    private static final int DOWNLOAD_SMALL_INTERVAL = 5000;
    private static final int DOWNLOAD_BIG_INTERVAL = 10000;

    private final PeerInfo peerInfo;
    private final String trackerIp;
    private final int trackerPort;
    private final Path filesDirectory;
    private final Set<String> peerPieces = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, List<PeerInfo>> piecesInfoMap = new HashMap<>();//Key:piece -> Value: List<PeerInfo>
    private final ScheduledExecutorService executor;
    private final Object downloadStrategyLock = new Object();
    private Boolean isFirstDownloadExecution = true;

    public Peer(String trackerIp, int trackerPort, int peerPort) {
        this.trackerIp = trackerIp;
        this.trackerPort = trackerPort;

        try {
            String peerIp = BitTorrentUtils.getLocalIPv4();
            this.peerInfo = new PeerInfo(peerIp, peerPort);
        } catch (SocketException e) {
            throw new RuntimeException(e);
        }

        this.filesDirectory = Paths.get(FILES_BASE_PATH + "Peer_" + this.peerInfo.getPeerAddress());
        this.executor = Executors.newScheduledThreadPool(3);
    }

    public void start() {
        this.log("Iniciando peer " + this.peerInfo.getPeerAddress(), false);
        this.createFilesDirectory();
        this.executor.submit(this::startServer);
        this.startTrackerUpdater();
        this.executor.schedule(this::startDownloader, 3, TimeUnit.SECONDS);
    }

    private void createFilesDirectory() {
        if (!Files.exists(this.filesDirectory)) {
            try {
                Files.createDirectories(this.filesDirectory);
            } catch (IOException e) {
                throw new RuntimeException("Não foi possível criar o diretório de arquivos do peer", e);
            }
        }
    }

    private void scanPiecesFromDirectory() {
        this.peerPieces.clear();

        try {
            Files.list(this.filesDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(this.peerPieces::add);

            this.log("Peças do diretório atualizadas. Contendo: " + this.peerPieces, false);
        } catch (IOException e) {
            this.log("Erro ao escanear diretório de peças: " + e.getMessage(), true);
        }
    }

    private void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(this.peerInfo.getPort())) {
            this.log("Servidor ouvindo na porta " + this.peerInfo.getPort(), false);

            while (true) {
                Socket socket = serverSocket.accept();
                this.executor.submit(() -> this.handleMessage(socket));
            }
        } catch (IOException e) {
            this.log("Erro no servidor: " + e.getMessage(), true);
        }
    }

    // Lógica recepção de mensagens entre peers
    private void handleMessage(Socket socket) {
        TCPConnection connection;

        try {
            connection = new TCPConnection(socket);
        } catch (IOException e) {
            this.log("Erro ao receber a mensagem: " + e.getMessage(), true);
            return;
        }

        RequestMessage request;

        try {
            request = connection.receiveMessage();
        } catch (Exception e) {
            this.log("Erro ao desserializar a mensagem: " + e.getMessage(), true);
            return;
        }

        if (request == null) {
            this.log("Mensagem nula após desserialização", false);
            return;
        }

        RequestMessage response = null;

        switch (request.getRequestType()) {
            case PIECE_REQUEST:
                response = this.sharePiece(request);
                break;
            default:
                response = this.buildErrorResponseMessage("Requisição inválida");
        }

        connection.sendMessage(response);
    }

    private RequestMessage sharePiece(RequestMessage request) {
        String pieceName = BitTorrentUtils.extractData(request.getData(), DataType.PIECE_NAME);
        PeerInfo peerInfoFromRequest = BitTorrentUtils.generatePeerInfoFromRequest(request);

        if (!this.peerPieces.contains(pieceName)) {
            this.log("O peer " + this.peerInfo.getPeerAddress() + " recebeu uma solicitação do pedaço " + pieceName + " porém não contém o mesmo", false);
            return this.buildErrorResponseMessage("O peer solicitado não possui o pedaço solicitado.");
        }

        try {
            byte[] fileData = FileUtils.readBytesFromFile(this.buildFilepath(pieceName));

            RequestMessage message = new RequestMessage(this.peerInfo.getPeerAddress(), RequestType.PIECE_RESPONSE);
            message.getData().put(DataType.SUCCESS, true);
            message.getData().put(DataType.PIECE_NAME, pieceName);
            message.getData().put(DataType.PIECE_DATA, fileData);

            return message;
        } catch (IOException e) {
            this.log("Erro ao compartilhar o pedaço " + pieceName + " com o peer " + peerInfoFromRequest.getPeerAddress() + e.getMessage(), true);
            return this.buildErrorResponseMessage("Ocorreu um erro inesperado ao compartilhar o pedaço.");
        }
    }

    private void startTrackerUpdater() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::sendUpdateToTracker, 0, 30, TimeUnit.SECONDS);
    }

    private void sendUpdateToTracker() {
        this.scanPiecesFromDirectory();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TCPConnection.CONNECTION_TIMEOUT_MS);
            RequestMessage request = new RequestMessage(this.peerInfo.getPeerAddress(), RequestType.UPDATE_TRACKER);
            request.getData().put(DataType.IP, this.peerInfo.getIp());
            request.getData().put(DataType.PORT, this.peerInfo.getPort());
            request.getData().put(DataType.PIECE_LIST, this.peerPieces);

            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(byteOut);
            out.writeObject(request);

            byte[] data = byteOut.toByteArray();
            InetAddress ip = InetAddress.getByName(this.trackerIp);
            DatagramPacket sendPackage = new DatagramPacket(data, data.length, ip, trackerPort);
            socket.send(sendPackage);

            this.log("Envio de pedaços para o Tracker realizado com sucesso. Aguardando lista do Tracker atualizada.", false);

            byte[] receiveBuffer = new byte[65535];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(receivePacket);

            ByteArrayInputStream byteIn = new ByteArrayInputStream(receivePacket.getData(), 0, receivePacket.getLength());
            ObjectInputStream in = new ObjectInputStream(byteIn);

            RequestMessage requestMessage = (RequestMessage) in.readObject();
            this.piecesInfoMap.clear();
            this.piecesInfoMap.putAll(BitTorrentUtils.extractData(requestMessage.getData(), DataType.PIECES_INFO_MAP));

            this.log("piecesInfoMap atualizado com a lista Tracker.", false);
        } catch (Exception e) {
            this.log("Erro ao enviar update para o Tracker: " + e.getMessage(), true);
        }
    }

    private void startDownloader() {
        try {
            while (true) {
                this.scanPiecesFromDirectory();
                String pieceName;

                synchronized(this.downloadStrategyLock) {
                    pieceName = isFirstDownloadExecution ? this.findRarestMissingPiece() : this.findRandomMissingPiece();
                }

                if (pieceName == null) {
                    this.log("Nenhuma peça nova para baixar no momento. Tentando novamente em " + (DOWNLOAD_BIG_INTERVAL / 1000) + " segundos.", false);
                    Thread.sleep(DOWNLOAD_BIG_INTERVAL);
                    continue;
                }

                this.log("Tentando baixar a peça: " + pieceName, false);
                List<PeerInfo> peersWithPiece = this.piecesInfoMap.get(pieceName);

                List<PeerInfo> eligiblePeers = peersWithPiece.stream()
                        .filter(this::isEligiblePeer)
                        .toList();

                if (eligiblePeers.isEmpty()) {
                    this.log("Nenhum peer elegível encontrado para a peça '" + pieceName + "'. Procurando outra peça.", false);
                    Thread.sleep(1000);
                    continue;
                }

                int randomIndex = ThreadLocalRandom.current().nextInt(eligiblePeers.size());
                PeerInfo chosenPeer = eligiblePeers.get(randomIndex);

                this.log("Peer escolhido para download da peça '" + pieceName + "': " + chosenPeer.getPeerAddress(), false);
                this.downloadPiece(chosenPeer, pieceName);

                Thread.sleep(DOWNLOAD_SMALL_INTERVAL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println(PEER_TAG + "Thread de download interrompida.");
        }
    }

    private String findRandomMissingPiece() {
        synchronized(this.piecesInfoMap) {
            List<String> missingPieces = this.piecesInfoMap.keySet().stream()
                    .filter(piece -> !this.peerPieces.contains(piece))
                    .toList();

            if (missingPieces.isEmpty()) {
                return null;
            }

            int randomIndex = new Random().nextInt(missingPieces.size());
            return missingPieces.get(randomIndex);
        }
    }


    private String findRarestMissingPiece() {
        synchronized(this.piecesInfoMap) {
            return this.piecesInfoMap.entrySet().stream()
                    .filter(entry -> !this.peerPieces.contains(entry.getKey()))
                    .min(Comparator.comparingInt(entry -> entry.getValue().size()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }
    }


    private void downloadPiece(PeerInfo recipientPeer, String pieceName) {
        synchronized(this.downloadStrategyLock) {
            this.isFirstDownloadExecution = false;
        }

        try (Socket socket = new Socket(recipientPeer.getIp(), recipientPeer.getPort());
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

            RequestMessage requestMessage = new RequestMessage(this.peerInfo.getPeerAddress(), RequestType.PIECE_REQUEST);
            requestMessage.getData().put(DataType.IP, this.peerInfo.getIp());
            requestMessage.getData().put(DataType.PORT, this.peerInfo.getPort());
            requestMessage.getData().put(DataType.PIECE_NAME, pieceName);

            out.writeObject(requestMessage);
            RequestMessage response = (RequestMessage) in.readObject();

            if (response == null || response.getRequestType() != RequestType.PIECE_RESPONSE) {
                this.log("O Peer " + recipientPeer.getPeerAddress() + " falhou em enviar arquivo " + pieceName, true);
                return;
            }

            Boolean success = BitTorrentUtils.extractData(response.getData(), DataType.SUCCESS);
            String errormessage = BitTorrentUtils.extractData(response.getData(), DataType.MESSAGE);

            if (success == null || !success) {
                this.log("O Peer " + recipientPeer.getPeerAddress() + " falhou em enviar arquivo " + pieceName + ". Motivo: " + errormessage, true);
                return;
            }

            FileUtils.createFileFromBytes(buildFilepath(pieceName), BitTorrentUtils.extractData(response.getData(), DataType.PIECE_DATA));
            this.scanPiecesFromDirectory();

            this.log("Pedaço " + pieceName + " obtido com sucesso de " + recipientPeer.getPeerAddress(), false);
        } catch (Exception e) {
            this.log("Erro ao solicitar pedaço ao peer " + recipientPeer.getPeerAddress() + ". ", true);
            e.printStackTrace();
        }
    }

    private RequestMessage buildErrorResponseMessage(String errorMessage) {
        RequestMessage requestMessage = new RequestMessage(this.peerInfo.getPeerAddress(), RequestType.PIECE_REQUEST);
        requestMessage.getData().put(DataType.IP, this.peerInfo.getIp());
        requestMessage.getData().put(DataType.PORT, this.peerInfo.getPort());
        requestMessage.getData().put(DataType.SUCCESS, false);
        requestMessage.getData().put(DataType.MESSAGE, errorMessage);

        return requestMessage;
    }

    private String buildFilepath(String fileName) {
        return this.filesDirectory.resolve(fileName).toString();
    }

    private boolean isEligiblePeer(PeerInfo peer) {
        return peer != null && peer.getPeerAddress() != null && !peer.getPeerAddress().equals(this.peerInfo.getPeerAddress());
    }

    private void log(String message, boolean error) {
        String timestamp = LocalDateTime.now().format(this.formatter);

        if (error) {
            System.err.println(timestamp + PEER_TAG + message);
        } else {
            System.out.println(timestamp + PEER_TAG + message);
        }
    }
}