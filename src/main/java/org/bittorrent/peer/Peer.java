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
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Peer {

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
            String peerIp = InetAddress.getLocalHost().getHostAddress();
            this.peerInfo = new PeerInfo(peerIp, peerPort);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }

        this.filesDirectory = Paths.get("Peer_" + this.peerInfo.getPeerAddress());
        this.executor = Executors.newScheduledThreadPool(3);
    }

    public void start() {
        System.out.println(PEER_TAG + "Iniciando peer " + this.peerInfo.getPeerAddress());
        this.createFilesDirectory();
        this.scanPiecesFromDirectory();
        this.executor.submit(this::startServer);
        this.startTrackerUpdater();
        this.executor.submit(this::startDownloader);
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
        try {
            Files.list(this.filesDirectory)
                    .map(path -> path.getFileName().toString())
                    .forEach(this.peerPieces::add);

            System.out.println(PEER_TAG + "Peças do diretório atualizadas. Contendo: " + this.peerPieces);
        } catch (IOException e) {
            System.err.println(PEER_TAG + "Erro ao escanear diretório de peças: " + e.getMessage());
        }
    }

    private void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(this.peerInfo.getPort())) {
            System.out.println(PEER_TAG + "Servidor ouvindo na porta " + this.peerInfo.getPort());

            while (true) {
                Socket socket = serverSocket.accept();
                this.executor.submit(() -> this.handleMessage(socket));
            }
        } catch (IOException e) {
            System.err.println(PEER_TAG + "Erro no servidor: " + e.getMessage());
        }
    }

    // Lógica recepção de mensagens entre peers
    private void handleMessage(Socket socket) {
        TCPConnection connection;

        try {
            connection = new TCPConnection(socket);
        } catch (IOException e) {
            System.err.println(PEER_TAG + "Erro ao receber a mensagem: " + e.getMessage());
            return;
        }

        RequestMessage request;

        try {
            request = connection.receiveMessage();
        } catch (Exception e) {
            System.err.println(PEER_TAG + "Erro ao desserializar a mensagem: " + e.getMessage());
            return;
        }

        if (request == null) {
            System.out.println(PEER_TAG + "Mensagem nula após desserialização");
            return;
        }

        RequestMessage response = null;

        switch (request.getRequestType()) {
            case PIECE_REQUEST:
                response = this.sharePiece(request);
                break;
            default:
                response = null; //aqui gera um resposta de erro
        }

        connection.sendMessage(response);
    }

    private RequestMessage sharePiece(RequestMessage request) {
        String pieceName = BitTorrentUtils.extractData(request.getData(), DataType.PIECE_NAME);
        PeerInfo peerInfoFromRequest = BitTorrentUtils.generatePeerInfoFromRequest(request);

        if (!this.peerPieces.contains(pieceName)) {
            //Retorna um erro
            return null;
        }

        try {
            byte[] fileData = FileUtils.readBytesFromFile(this.buildFilepath(pieceName));

            RequestMessage message = new RequestMessage(this.peerInfo.getPeerAddress(), RequestType.PIECE_RESPONSE);
            message.getData().put(DataType.SUCCESS, true);
            message.getData().put(DataType.PIECE_NAME, pieceName);
            message.getData().put(DataType.PIECE_DATA, fileData);

            return message;
        } catch (IOException e) {
            System.err.println(PEER_TAG + "Erro ao compartilhar o pedaço " + pieceName + " com o peer " + peerInfoFromRequest.getPeerAddress() + e.getMessage());
            //Retorna um erro
            return null;
        }
    }

    private void startTrackerUpdater() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::sendUpdateToTracker, 0, 30, TimeUnit.SECONDS);
    }

    private void sendUpdateToTracker() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TCPConnection.CONNECTION_TIMEOUT_MS);
            RequestMessage request = new RequestMessage(this.peerInfo.getPeerAddress(), RequestType.UPDATE_TRACKER);
            request.getData().put(DataType.PIECE_LIST, this.peerPieces);

            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(byteOut);
            out.writeObject(request);

            byte[] data = byteOut.toByteArray();
            InetAddress ip = InetAddress.getByName(this.trackerIp);
            DatagramPacket sendPackage = new DatagramPacket(data, data.length, ip, trackerPort);
            socket.send(sendPackage);

            System.out.println(PEER_TAG + "Envio de pedaços para o Tracker realizado com sucesso. Aguardando lista do Tracker atualizada.");

            byte[] receiveBuffer = new byte[65535];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            socket.receive(receivePacket);

            ByteArrayInputStream byteIn = new ByteArrayInputStream(receivePacket.getData(), 0, receivePacket.getLength());
            ObjectInputStream in = new ObjectInputStream(byteIn);

            RequestMessage requestMessage = (RequestMessage) in.readObject();
            this.piecesInfoMap.clear();
            this.piecesInfoMap.putAll(BitTorrentUtils.extractData(requestMessage.getData(), DataType.PIECES_INFO_MAP));

            System.out.println(PEER_TAG + "piecesInfoMap atualizado com a lista Tracker.");
        } catch (Exception e) {
            System.err.println(PEER_TAG + "Erro ao enviar update para o Tracker: " + e.getMessage());
        }
    }

    private void startDownloader() {
        try {
            while(true) {
                String pieceName;

                synchronized(this.downloadStrategyLock) {
                    pieceName = isFirstDownloadExecution ? this.findRarestMissingPiece() : this.findRandomMissingPiece();
                }

                // Se não houver nada para baixar, aguarda antes de verificar novamente
                if (pieceName == null) {
                    System.out.println(PEER_TAG + "Nenhuma peça nova para baixar no momento. Realizará a tentativa novamente em: ");
                    Thread.sleep(DOWNLOAD_BIG_INTERVAL);
                    continue;
                }

                System.out.println(PEER_TAG + "Peça mais rara a ser baixada: " + pieceName);
                List<PeerInfo> peersWithPiece = this.piecesInfoMap.get(pieceName);
                PeerInfo chosenPeer = peersWithPiece.get(new Random().nextInt(peersWithPiece.size()));

                this.downloadPiece(chosenPeer, pieceName);
                Thread.sleep(DOWNLOAD_BIG_INTERVAL);
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
            requestMessage.getData().put(DataType.PIECE_NAME, pieceName);

            out.writeObject(requestMessage);
            RequestMessage response = (RequestMessage) in.readObject();

            if (response.getRequestType() != RequestType.PIECE_RESPONSE) {
                System.err.println(PEER_TAG + "O Peer " + recipientPeer.getPeerAddress() + " falhou em enviar arquivo " + pieceName);
                return;
            }

            FileUtils.createFileFromBytes(buildFilepath(pieceName), BitTorrentUtils.extractData(requestMessage.getData(), DataType.PIECE_DATA));
            this.scanPiecesFromDirectory();

            System.out.println(PEER_TAG + "Pedaço " + pieceName + " obtido com sucesso de " + recipientPeer.getPeerAddress());
        } catch (Exception e) {
            System.err.println(PEER_TAG +"Erro ao solicitar pedaço ao peer " + recipientPeer.getPeerAddress() + ": " + e.getMessage());
        }
    }

    private String buildFilepath(String fileName) {
        return Paths.get(FILES_BASE_PATH + this.filesDirectory, fileName).toString();
    }
}