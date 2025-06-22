package org.bittorrent.peer;

import java.util.Set;

public class PeerInfo {
    private final String peerAddress;
    private final String ip;
    private final int port;
    private Set<String> pieces;
    private long lastSeen;

    public PeerInfo(String ip, int port, Set<String> pieces) {
        this.ip = ip;
        this.port = port;
        this.pieces = pieces;
        this.lastSeen = System.currentTimeMillis();
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

    public Set<String> getPieces() {
        return pieces;
    }

    public long getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }

    @Override
    public String toString() {
        return ip + ":" + port + ":" + String.join(",", pieces);
    }
}