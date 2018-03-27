/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.broadcast.group;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.bitcoinj.broadcast.BroadcastTransactionListener;
import org.bitcoinj.broadcast.TransactionBroadcaster;
import org.bitcoinj.broadcast.group.strategy.RatioOfConnectedRandomlyPeerGroupStrategy;
import org.bitcoinj.core.*;
import org.bitcoinj.core.listeners.PreMessageReceivedEventListener;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.Executor;

import static com.google.common.base.Preconditions.checkState;

/**
 * Represents a single transaction broadcast that we are performing. A broadcast occurs after a new transaction is created
 * (typically by a {@link Wallet} and needs to be sent to the network. A broadcast can succeed or fail. A success is
 * defined as seeing the transaction be announced by peers via inv messages, thus indicating their acceptance. A failure
 * is defined as not reaching acceptance within a timeout period, or getting an explicit reject message from a peer
 * indicating that the transaction was not acceptable.
 */
public class PeerGroupTransactionBroadcaster implements TransactionBroadcaster {

    /**
     * Used for shuffling the peers before broadcast: unit tests can replace this to make themselves deterministic.
     */
    @VisibleForTesting
    public static Random random = new Random();

    private static final Logger log = LoggerFactory.getLogger(PeerGroupTransactionBroadcaster.class);

    private final SettableFuture<Transaction> future = SettableFuture.create();
    private final PeerGroup peerGroup;
    private final Transaction tx;
    private int minConnections;
    private BroadcastPeerGroupStrategy peerGroupBroadcastStrategy;

    @Nullable
    private BroadcastProgressCallback callback;
    @Nullable
    private Executor progressCallbackExecutor;

    private int numSeemPeers;
    private boolean mined;

    // Tracks which nodes sent us a reject message about this broadcast, if any. Useful for debugging.
    private Map<Peer, RejectMessage> rejects = Collections.synchronizedMap(new HashMap<Peer, RejectMessage>());

    public PeerGroupTransactionBroadcaster(PeerGroup peerGroup, Transaction tx) {
        this.peerGroup = peerGroup;
        this.tx = tx;
        this.minConnections = Math.max(1, peerGroup.getMinBroadcastConnections());
        this.peerGroupBroadcastStrategy = new RatioOfConnectedRandomlyPeerGroupStrategy(.5f, .25f);
    }

    // Only for mock broadcasts.
    private PeerGroupTransactionBroadcaster(Transaction tx) {
        this(null, tx);
    }

    @VisibleForTesting
    public static PeerGroupTransactionBroadcaster createMockBroadcast(Transaction tx, final SettableFuture<Transaction> future) {
        return new PeerGroupTransactionBroadcaster(tx) {
            @Override
            public ListenableFuture<Transaction> broadcast() {
                return future;
            }

            @Override
            public ListenableFuture<Transaction> future() {
                return future;
            }
        };
    }

    public ListenableFuture<Transaction> future() {
        return future;
    }

    public void setMinConnections(int minConnections) {
        this.minConnections = minConnections;
    }

    private PreMessageReceivedEventListener rejectionListener = new PreMessageReceivedEventListener() {
        @Override
        public Message onPreMessageReceived(Peer peer, Message m) {
            if (m instanceof RejectMessage) {
                RejectMessage rejectMessage = (RejectMessage) m;
                if (tx.getHash().equals(rejectMessage.getRejectedObjectHash())) {
                    rejects.put(peer, rejectMessage);
                    int size = rejects.size();
                    long threshold = Math.round(peerGroupBroadcastStrategy.getBroadcastTargetSize() / 2.0);
                    if (size > threshold) {
                        log.warn("Threshold for considering broadcast rejected has been reached ({}/{})", size, threshold);
                        future.setException(new RejectedTransactionException(tx, rejectMessage));
                        peerGroup.removePreMessageReceivedEventListener(this);
                    }
                }
            }
            return m;
        }
    };

    public ListenableFuture<Transaction> broadcast() {
        peerGroup.addPreMessageReceivedEventListener(Threading.SAME_THREAD, rejectionListener);
        log.info("Waiting for {} peers required for broadcast, we have {} ...", minConnections, peerGroup.getConnectedPeers().size());
        peerGroup.waitForPeers(minConnections).addListener(new EnoughAvailablePeers(), Threading.SAME_THREAD);
        return future;
    }

    @Override
    public void broadcast(Transaction tx, BroadcastTransactionListener listener) {

    }

    private class EnoughAvailablePeers implements Runnable {
        @Override
        public void run() {
            // We now have enough connected peers to send the transaction.
            // This can be called immediately if we already have enough. Otherwise it'll be called from a peer
            // thread.

            // We will send the tx simultaneously to half the connected peers and wait to hear back from at least half
            // of the other half, i.e., with 4 peers connected we will send the tx to 2 randomly chosen peers, and then
            // wait for it to show up on one of the other two. This will be taken as sign of network acceptance. As can
            // be seen, 4 peers is probably too little - it doesn't taken many broken peers for tx propagation to have
            // a big effect.
            List<Peer> peers = peerGroupBroadcastStrategy.choosePeers(peerGroup);
            // Prepare to send the transaction by adding a listener that'll be called when confidence changes.
            // Only bother with this if we might actually hear back:
            if (minConnections > 1)
                tx.getConfidence().addEventListener(new ConfidenceChange());
            // Bitcoin Core sends an inv in this case and then lets the peer request the tx data. We just
            // blast out the TX here for a couple of reasons. Firstly it's simpler: in the case where we have
            // just a single connection we don't have to wait for getdata to be received and handled before
            // completing the future in the code immediately below. Secondly, it's faster. The reason the
            // Bitcoin Core sends an inv is privacy - it means you can't tell if the peer originated the
            // transaction or not. However, we are not a fully validating node and this is advertised in
            // our version message, as SPV nodes cannot relay it doesn't give away any additional information
            // to skip the inv here - we wouldn't send invs anyway.
            int numConnected = peerGroup.getConnectedPeers().size();
            log.info("broadcastTransaction: We have {} peers, adding {} to the memory pool", numConnected, tx.getHashAsString());
            for (Peer peer : peers) {
                try {
                    peer.sendMessage(tx);
                    // We don't record the peer as having seen the tx in the memory pool because we want to track only
                    // how many peers announced to us.
                } catch (Exception e) {
                    log.error("Caught exception sending to {}", peer, e);
                }
            }
            // If we've been limited to talk to only one peer, we can't wait to hear back because the
            // remote peer won't tell us about transactions we just announced to it for obvious reasons.
            // So we just have to assume we're done, at that point. This happens when we're not given
            // any peer discovery source and the user just calls connectTo() once.
            if (minConnections == 1) {
                peerGroup.removePreMessageReceivedEventListener(rejectionListener);
                future.set(tx);
            }
        }
    }

    private class ConfidenceChange implements TransactionConfidence.Listener {
        @Override
        public void onConfidenceChanged(TransactionConfidence conf, ChangeReason reason) {
            // The number of peers that announced this tx has gone up.
            int numSeenPeers = conf.numBroadcastPeers() + rejects.size();
            boolean mined = tx.getAppearsInHashes() != null;
            log.info("broadcastTransaction: {}:  TX {} seen by {} peers{}", reason, tx.getHashAsString(),
                    numSeenPeers, mined ? " and mined" : "");

            // Progress callback on the requested thread.
            onBroadcastProgress(numSeenPeers, mined);

            if (numSeenPeers >= peerGroupBroadcastStrategy.getBroadcastTargetSize() || mined) {
                // We've seen the min required number of peers announce the transaction, or it was included
                // in a block. Normally we'd expect to see it fully propagate before it gets mined, but
                // it can be that a block is solved very soon after broadcast, and it's also possible that
                // due to version skew and changes in the relay rules our transaction is not going to
                // fully propagate yet can get mined anyway.
                //
                // Note that we can't wait for the current number of connected peers right now because we
                // could have added more peers after the broadcast took place, which means they won't
                // have seen the transaction. In future when peers sync up their memory pools after they
                // connect we could come back and change this.
                //
                // We're done! It's important that the PeerGroup lock is not held (by this thread) at this
                // point to avoid triggering inversions when the Future completes.
                log.info("broadcastTransaction: {} complete", tx.getHash());
                peerGroup.removePreMessageReceivedEventListener(rejectionListener);
                conf.removeEventListener(this);
                future.set(tx);  // RE-ENTRANCY POINT
            }
        }
    }

    private void onBroadcastProgress(int numSeenPeers, boolean mined) {
        synchronized (this) {
            this.numSeemPeers = numSeenPeers;
            this.mined = mined;
        }
        notifyBroadcastProgress(numSeenPeers, mined);
    }

    private void notifyBroadcastProgress(int numSeenPeers, boolean mined) {
        final BroadcastProgressCallback callback;
        Executor executor;
        synchronized (this) {
            callback = this.callback;
            executor = this.progressCallbackExecutor;
        }
        if (callback != null) {
            int targetPeers = peerGroupBroadcastStrategy.getBroadcastTargetSize();
            final double progress = Math.min(1.0, mined ? 1.0 : numSeenPeers / (double) targetPeers);
            checkState(progress >= 0.0 && progress <= 1.0, progress);
            try {
                if (executor == null)
                    callback.onBroadcastProgress(progress);
                else
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            callback.onBroadcastProgress(progress);
                        }
                    });
            } catch (Throwable e) {
                log.error("Exception during progress callback", e);
            }
        }
    }

    /**
     * Sets the given callback for receiving progress values, which will run on the user thread. See
     * {@link org.bitcoinj.utils.Threading} for details.  If the broadcast has already started then the callback will
     * be invoked immediately with the current progress.
     */
    public void setProgressCallback(BroadcastProgressCallback callback) {
        setProgressCallback(callback, Threading.USER_THREAD);
    }

    /**
     * Sets the given callback for receiving progress values, which will run on the given executor. If the executor
     * is null then the callback will run on a network thread and may be invoked multiple times in parallel. You
     * probably want to provide your UI thread or Threading.USER_THREAD for the second parameter. If the broadcast
     * has already started then the callback will be invoked immediately with the current progress.
     */
    public void setProgressCallback(BroadcastProgressCallback callback, @Nullable Executor executor) {
        boolean shouldInvoke;
        int num;
        boolean mined;
        synchronized (this) {
            this.callback = callback;
            this.progressCallbackExecutor = executor;
            num = this.numSeemPeers;
            mined = this.mined;
            shouldInvoke = peerGroupBroadcastStrategy.getBroadcastTargetSize() > 0;
        }
        if (shouldInvoke)
            notifyBroadcastProgress(num, mined);
    }
}
