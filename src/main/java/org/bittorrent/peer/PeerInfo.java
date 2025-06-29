package org.bittorrent.peer;

import java.io.Serializable;

public class PeerInfo implements Serializable {
    private final String peerAddress;
    private final String ip;
    private final int port;

    public PeerInfo(String ip, int port) {
        this.ip = ip;
        this.port = port;
        this.peerAddress = ip + ":" + port;
    }

    public String getPeerAddress() {
        return peerAddress;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }
}