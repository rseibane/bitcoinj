package org.bitcoinj.broadcast;

import org.bitcoinj.core.RejectMessage;
import org.bitcoinj.core.Transaction;

public class BroadcastTransactionListenerAdapter implements BroadcastTransactionListener {

    @Override
    public void onBroadcastSuccess(Transaction tx, boolean isMined) {

    }

    @Override
    public void onProgress(double percentage, int totalBroadcasts, int target) {

    }

    @Override
    public void onDoubleSpendDetected(Transaction broadcastedTx, Transaction detectedTx) {

    }

    @Override
    public void onBroadcastRejected(Transaction tx, RejectMessage rejectMessage) {

    }

}
