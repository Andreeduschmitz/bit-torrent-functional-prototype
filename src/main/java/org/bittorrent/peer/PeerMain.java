package org.bittorrent.peer;

import org.bittorrent.tracker.TrackerMain;
import org.bittorrent.utils.BitTorrentUtils;

import java.util.Scanner;

public class PeerMain {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Insira o IP do Tracker:");
        String ip = BitTorrentUtils.requestIp(scanner);
        System.out.println("Insira a porta na qual deseja iniciar o Peer:");
        int port = BitTorrentUtils.requestPort(scanner);

        String trackerIp = args[0];
        int trackerPort = TrackerMain.TRACKER_PORT;
        int peerPort = Integer.parseInt(args[1]);
        String torrentName = args[2];
        String filesDirectory = args[3];

        Peer peer = new Peer(trackerIp, trackerPort, peerPort, torrentName, filesDirectory);
        peer.start();
    }
}