package org.bitcoinj.broadcast;

import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoinj.core.Transaction;

public interface TransactionBroadcaster {

    @Deprecated
    ListenableFuture<Transaction> future();

    ListenableFuture<Transaction> broadcast();

}
