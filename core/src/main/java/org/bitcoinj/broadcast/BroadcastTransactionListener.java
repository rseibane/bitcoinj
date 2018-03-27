package org.bitcoinj.broadcast;

import org.bitcoinj.core.Transaction;

public interface BroadcastTransactionListener {

    void onBroadcastSuccess(Transaction tx);

    void onDoubleSpendDetected(Transaction broadcastedTx, Transaction detectedTx);

    void onProgress(int totalBroadcasts, int target, boolean isMined);

}
