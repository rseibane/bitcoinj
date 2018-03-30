package org.bitcoinj.broadcast;

import org.bitcoinj.core.RejectMessage;
import org.bitcoinj.core.Transaction;

public interface BroadcastTransactionListener {

    void onBroadcastSuccess(Transaction tx, boolean isMined);

    void onProgress(double percentage, int totalBroadcasts, int target);

    void onDoubleSpendDetected(Transaction broadcastedTx, Transaction detectedTx);

    void onBroadcastRejected(Transaction tx, RejectMessage rejectMessage);
}
