package com.pixelmc.wenzhouai.service;

/** Binds a player's stop click to one request and its model worker, up to the history commit. */
final class WzRunControl {
    private final String requestId;
    private Thread worker;
    private boolean stopped;
    private boolean finished;

    WzRunControl(String requestId) {
        this.requestId = requestId;
    }

    synchronized void begin() throws InterruptedException {
        if (stopped) throw new InterruptedException();
        worker = Thread.currentThread();
    }

    synchronized boolean stop(String expected) {
        if (finished || expected.isEmpty() || !requestId.equals(expected)) return false;
        if (!stopped) {
            stopped = true;
            if (worker != null) worker.interrupt();
        }
        return true;
    }

    synchronized boolean stopped() {
        return stopped;
    }

    synchronized boolean finish() {
        // A voluntary stop still saves the turn and any received usage. Access is rechecked separately.
        if (!finished && stopped && worker == Thread.currentThread()) Thread.interrupted();
        finished = true;
        worker = null;
        return stopped;
    }
}
