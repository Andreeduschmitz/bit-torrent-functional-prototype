package org.bittorrent.peer;

import org.bittorrent.tracker.TrackerMain;
import org.bittorrent.utils.BitTorrentUtils;

import java.util.Scanner;

public class PeerMain {
    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);

        System.out.println("Insira o IP do Tracker:");
        String trackerIp = BitTorrentUtils.requestIp(scanner);
        int trackerPort = TrackerMain.TRACKER_PORT;
        System.out.println("Insira a porta na qual deseja iniciar o Peer:");
        int peerPort = BitTorrentUtils.requestPort(scanner);

        Peer peer = new Peer(trackerIp, trackerPort, peerPort);
        peer.start();
    }
}