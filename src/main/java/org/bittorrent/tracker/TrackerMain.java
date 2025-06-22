package org.bittorrent.tracker;

public class TrackerMain {
    public static final int TRACKER_PORT = 8000; // Porta padrão do Tracker

    public static void main(String[] args) {
        Tracker tracker = new Tracker(TRACKER_PORT);
        tracker.start();
    }
}