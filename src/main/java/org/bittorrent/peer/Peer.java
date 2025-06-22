package org.bittorrent.peer;

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
    private final String peerId;
    private final String trackerIp;
    private final int trackerPort;
    private final int myPort; // Porta TCP para ouvir outros peers
    private final String torrentName;
    private final Path filesDirectory;
    private final Set<String> myPieces = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, List<String>> networkPieces = new HashMap<>(); // Pedaço -> Lista de IPs:PORTA

    public Peer(String trackerIp, int trackerPort, int myPort, String torrentName, String directory) {
        this.trackerIp = trackerIp;
        this.trackerPort = trackerPort;
        this.myPort = myPort;
        this.torrentName = torrentName;
        this.filesDirectory = Paths.get(directory);
        this.peerId = "Peer@" + myPort;
    }

    public void start() {
        System.out.println("[" + peerId + "] iniciando...");
        this.createFilesDirectory();
        this.scanInitialPieces();
        this.startServerThread();
        this.startTrackerUpdater();
        this.startDownloader();
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

    private void scanInitialPieces() {
        try {
            Files.list(this.filesDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(fileName -> fileName.startsWith(this.torrentName + "_"))
                    .forEach(this.myPieces::add);
            System.out.println("[" + this.peerId + "] Peças iniciais: " + this.myPieces);
        } catch (IOException e) {
            System.err.println("[" + this.peerId + "] Erro ao escanear peças iniciais: " + e.getMessage());
        }
    }

    private void startServerThread() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(this.myPort)) {
                System.out.println("[" + this.peerId + "] Servidor P2P ouvindo na porta " + this.myPort);
                while (true) {
                    Socket clientSocket = serverSocket.accept();
                    new Thread(() -> handlePeerRequest(clientSocket)).start();
                }
            } catch (IOException e) {
                System.err.println("[" + this.peerId + "] Erro no servidor P2P: " + e.getMessage());
            }
        }).start();
    }

    // Lógica para enviar um pedaço para outro peer
    private void handlePeerRequest(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             OutputStream out = clientSocket.getOutputStream()) {

            String request = in.readLine(); // Formato: "DOWNLOAD:NOME_DA_PECA"
            if (request != null && request.startsWith("DOWNLOAD:")) {
                String pieceName = request.substring(9);
                System.out.println("[" + this.peerId + "] Recebeu solicitação de download para: " + pieceName);

                if (this.myPieces.contains(pieceName)) {
                    Path piecePath = this.filesDirectory.resolve(pieceName);
                    if (Files.exists(piecePath)) {
                        byte[] fileContent = Files.readAllBytes(piecePath);
                        out.write(fileContent);
                        out.flush();
                        System.out.println("[" + this.peerId + "] Enviou a peça " + pieceName + " para " + clientSocket.getRemoteSocketAddress());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[" + this.peerId + "] Erro ao lidar com a solicitação do peer: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.out.println("Erro ao fechar o socket do servidor P2P: " + e.getMessage());
            }
        }
    }

    // 3. Inicia o agendador para enviar updates para o Tracker
    private void startTrackerUpdater() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // Envia a lista de peças a cada 30 segundos
        scheduler.scheduleAtFixedRate(this::sendUpdateToTracker, 0, 30, TimeUnit.SECONDS);
    }

    private void sendUpdateToTracker() {
        try (DatagramSocket socket = new DatagramSocket()) {
            String piecesStr = String.join(",", this.myPieces);
            String message = "UPDATE:" + this.torrentName + ":" + piecesStr;
            byte[] buffer = message.getBytes();

            InetAddress trackerAddress = InetAddress.getByName(this.trackerIp);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, trackerAddress, this.trackerPort);
            socket.send(packet);

            System.out.println("[" + this.peerId + "] Enviou update para o Tracker: " + message);
        } catch (IOException e) {
            System.err.println("[" + this.peerId + "] Erro ao enviar update para o Tracker: " + e.getMessage());
        }
    }

    // 4. Inicia a lógica de download
    private void startDownloader() {
        new Thread(() -> {
            try {
                while(true) { // Loop para continuar baixando peças
                    getPeerListFromTracker(); // Atualiza a lista de peças da rede
                    String rarestPiece = findRarestMissingPiece();

                    if (rarestPiece != null) {
                        System.out.println("[" + this.peerId + "] Peça mais rara a ser baixada: " + rarestPiece);
                        List<String> peersWithPiece = this.networkPieces.get(rarestPiece);

                        // Escolhe um peer aleatório que tenha a peça
                        String targetPeerAddress = peersWithPiece.get(new Random().nextInt(peersWithPiece.size()));

                        downloadPiece(targetPeerAddress, rarestPiece);
                    } else {
                        System.out.println("[" + this.peerId + "] Nenhuma peça nova para baixar no momento. Aguardando...");
                        // Se não houver nada para baixar, aguarda antes de verificar novamente
                        Thread.sleep(15000);
                    }
                    // Espera um pouco antes de tentar o próximo download
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[" + this.peerId + "] Thread de download interrompida.");
            }
        }).start();
    }

    // Pede a lista de peers ao Tracker via UDP
    private void getPeerListFromTracker() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(5000); // Timeout de 5 segundos

            String message = "JOIN:" + this.torrentName;
            byte[] buffer = message.getBytes();

            InetAddress trackerAddress = InetAddress.getByName(this.trackerIp);
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, trackerAddress, this.trackerPort);
            socket.send(packet);

            // Espera a resposta do Tracker
            byte[] responseBuffer = new byte[2048];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            socket.receive(responsePacket);

            String response = new String(responsePacket.getData(), 0, responsePacket.getLength());
            parsePeerList(response);

        } catch (SocketTimeoutException e) {
            System.err.println("[" + peerId + "] Timeout ao contatar o Tracker. Tentando novamente...");
        } catch (IOException e) {
            System.err.println("[" + peerId + "] Erro ao obter lista de peers do Tracker: " + e.getMessage());
        }
    }

    // Processa a resposta do Tracker
    private void parsePeerList(String response) {
        // Formato: PEER_LIST:TORRENT|IP1:PORTA1:PEÇA1,PEÇA2|IP2:PORTA2:PEÇA3...
        if (!response.startsWith("PEER_LIST:")) return;

        synchronized (this.networkPieces) {
            this.networkPieces.clear();
            String[] parts = response.split("\\|");
            // parts[0] é PEER_LIST:TORRENT, então começamos do 1
            for (int i = 1; i < parts.length; i++) {
                String peerData = parts[i];
                String[] peerInfo = peerData.split(":", 3);
                if (peerInfo.length < 2) continue;

                String peerAddress = peerInfo[0] + ":" + peerInfo[1];

                // Ignora a si mesmo na lista
                if (Integer.parseInt(peerInfo[1]) == this.myPort) {
                    continue;
                }

                if (peerInfo.length > 2 && !peerInfo[2].isEmpty()) {
                    String[] pieces = peerInfo[2].split(",");
                    for (String piece : pieces) {
                        networkPieces.putIfAbsent(piece, new ArrayList<>());
                        networkPieces.get(piece).add(peerAddress);
                    }
                }
            }
        }
        System.out.println("[" + this.peerId + "] Visão da rede atualizada: " + this.networkPieces);
    }

    // Algoritmo para encontrar a peça mais rara que eu não possuo
    private String findRarestMissingPiece() {
        synchronized(this.networkPieces) {
            return this.networkPieces.entrySet().stream()
                    // Filtra apenas as peças que eu não tenho
                    .filter(entry -> !this.myPieces.contains(entry.getKey()))
                    // Encontra a peça com o menor número de peers (mais rara)
                    .min(Comparator.comparingInt(entry -> entry.getValue().size()))
                    // Mapeia para o nome da peça
                    .map(Map.Entry::getKey)
                    // Retorna a peça ou null se não houver nenhuma
                    .orElse(null);
        }
    }

    // Lógica de cliente TCP para baixar uma peça
    private void downloadPiece(String peerAddress, String pieceName) {
        String[] parts = peerAddress.split(":");
        String ip = parts[0];
        int port = Integer.parseInt(parts[1]);

        System.out.println("[" + this.peerId + "] Tentando baixar " + pieceName + " de " + peerAddress);

        try (Socket socket = new Socket(ip, port);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             InputStream in = socket.getInputStream()) {

            // 1. Envia a solicitação de download
            out.println("DOWNLOAD:" + pieceName);

            // 2. Lê a resposta (o conteúdo do arquivo)
            byte[] fileContent = in.readAllBytes();
            if (fileContent.length > 0) {
                // 3. Salva o arquivo no diretório
                Path newFilePath = this.filesDirectory.resolve(pieceName);
                Files.write(newFilePath, fileContent);

                // 4. Adiciona a peça à lista de peças que possuo
                this.myPieces.add(pieceName);
                System.out.println("[" + this.peerId + "] SUCESSO: Peça " + pieceName + " baixada e salva.");

                // 5. Envia um update imediato para o Tracker
                sendUpdateToTracker();
            } else {
                System.err.println("[" + this.peerId + "] FALHA: Recebeu 0 bytes para " + pieceName + " de " + peerAddress);
            }

        } catch (IOException e) {
            System.err.println("[" + this.peerId + "] FALHA ao baixar " + pieceName + " de " + peerAddress + ": " + e.getMessage());
        }
    }
}