package org.bitcoinj.broadcast.group;

/**
 * An interface for receiving progress information on the propagation of the tx, from 0.0 to 1.0
 */
public interface BroadcastProgressCallback {
    /**
     * onBroadcastProgress will be invoked on the provided executor when the progress of the transaction
     * broadcast has changed, because the transaction has been announced by another peer or because the transaction
     * was found inside a mined block (in this case progress will go to 1.0 immediately). Any exceptions thrown
     * by this callback will be logged and ignored.
     */
    void onBroadcastProgress(double progress);
}