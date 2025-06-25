package org.bittorrent.connection;

import org.bittorrent.message.RequestMessage;
import org.bittorrent.peer.PeerInfo;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class TCPConnection {

    public static final int CONNECTION_TIMEOUT_MS = 5000;

    private final Socket socket;
    private final ObjectOutputStream out;
    private final ObjectInputStream in;
    private final PeerInfo peerInfo;

    public TCPConnection(PeerInfo peerInfo) throws IOException {
        this.peerInfo = peerInfo;
        this.socket = new Socket(peerInfo.getIp(), peerInfo.getPort());
        this.socket.setSoTimeout(CONNECTION_TIMEOUT_MS);
        this.out = new ObjectOutputStream(socket.getOutputStream());
        this.in = new ObjectInputStream(socket.getInputStream());
    }

    public TCPConnection(Socket socket) throws IOException {
        this.peerInfo = new PeerInfo(socket.getInetAddress().getHostAddress(), socket.getPort());
        this.socket = socket;
        this.socket.setSoTimeout(CONNECTION_TIMEOUT_MS);
        this.out = new ObjectOutputStream(socket.getOutputStream());
        this.in = new ObjectInputStream(socket.getInputStream());
    }

    public void sendMessage(RequestMessage message) {
        if (this.socket.isClosed()) return;

        try {
            this.out.writeObject(message);
            this.out.flush();
        } catch (IOException e) {
            System.err.println("Erro ao enviar mensagem para " + this.peerInfo.getPeerAddress() + ". Erro: " + e.getMessage());
            this.disconnect();
        }
    }

    public RequestMessage receiveMessage() throws IOException, ClassNotFoundException {
        if (this.socket.isClosed()) return null;

        try {
            Object obj = in.readObject();

            if (obj instanceof RequestMessage requestMessage) {
                return requestMessage;
            } else {
                System.err.println("Erro na desserialização da mensagem do peer : " + this.peerInfo.getPeerAddress());
                return null;
            }

        } catch (EOFException e) {
            System.out.println("Conexão fechada pelo peer " + this.peerInfo.getPeerAddress());
            this.disconnect();
            return null;
        } catch (Exception e) {
            System.err.println("Erro ao receber mensagem de " + this.peerInfo.getPeerAddress() + ". Erro: " + e.getMessage());
            this.disconnect();
            throw e;
        }
    }

    public void disconnect() {
        try {
            if (this.socket == null || this.socket.isClosed()) return;
            this.socket.close();
        } catch (IOException e) {
            System.err.println("Erro ao fechar conexão: " + e);
        }
    }

    public PeerInfo getPeerInfo() {
        return peerInfo;
    }
}