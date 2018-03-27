package org.bitcoinj.broadcast;

import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoinj.core.Transaction;

public interface TransactionBroadcaster {

    void broadcast(Transaction tx, BroadcastTransactionListener listener);

    ListenableFuture<Transaction> future();

}
