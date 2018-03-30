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
import com.sun.istack.internal.NotNull;
import org.bitcoinj.broadcast.BroadcastTransactionListener;
import org.bitcoinj.broadcast.DoubleSpentDetector;
import org.bitcoinj.broadcast.TransactionBroadcaster;
import org.bitcoinj.broadcast.group.strategy.RatioOfConnectedRandomlyPeerGroupStrategy;
import org.bitcoinj.core.*;
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener;
import org.bitcoinj.core.listeners.PreMessageReceivedEventListener;
import org.bitcoinj.utils.Threading;
import org.bitcoinj.wallet.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public static final float CONNECTED_PEERS_BROADCAST_RATIO = .5f;
    public static final float CONNECTED_PEERS_TARGET_RATIO = .25f;
    public static final int PREVENT_DOUBLE_SPEND_DEFAULT_SECONDS = 5;

    private static final Logger log = LoggerFactory.getLogger(PeerGroupTransactionBroadcaster.class);

    private final SettableFuture<Transaction> future = SettableFuture.create();
    private final Object lock = new Object();
    private final PeerGroup peerGroup;
    private final Transaction tx;
    private int minConnections;
    private int preventDoubleSpendSeconds;
    private BroadcastPeerGroupStrategy peerGroupBroadcastStrategy;

    private Executor broadcastListenerExecutor;
    private BroadcastTransactionListener broadcastListener;
    private ConfidenceChangeListener confidenceChangeListener;
    private RejectionListener rejectionListener;
    private TransactionReceivedListener transactionReceivedListener;
    private Timer stopPreventingDoubleSpendTimer;

    private int numSeemPeers;
    private boolean isMined;
    private boolean isBroadcastCompleted;
    private boolean isDoubleSpendSecondsElapsed;

    // Tracks which nodes sent us a reject message about this broadcast, if any. Useful for debugging.
    private Map<Peer, RejectMessage> rejects = Collections.synchronizedMap(new HashMap<Peer, RejectMessage>());

    public PeerGroupTransactionBroadcaster(PeerGroup peerGroup, Transaction tx) {
        this.peerGroup = peerGroup;
        this.tx = tx;
        this.minConnections = Math.max(1, peerGroup.getMinBroadcastConnections());
        this.preventDoubleSpendSeconds = PREVENT_DOUBLE_SPEND_DEFAULT_SECONDS;
        this.peerGroupBroadcastStrategy =
                new RatioOfConnectedRandomlyPeerGroupStrategy(CONNECTED_PEERS_BROADCAST_RATIO, CONNECTED_PEERS_TARGET_RATIO);
        this.broadcastListenerExecutor = Threading.USER_THREAD;
        this.confidenceChangeListener = new ConfidenceChangeListener();
        this.rejectionListener = new RejectionListener();
        this.transactionReceivedListener = new TransactionReceivedListener();
        this.stopPreventingDoubleSpendTimer = new Timer(true);
        this.numSeemPeers = 0;
        this.isMined = false;
        this.isBroadcastCompleted = false;
        this.isDoubleSpendSecondsElapsed = false;
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

    public PeerGroupTransactionBroadcaster setMinConnections(int minConnections) {
        this.minConnections = minConnections;
        return this;
    }

    public PeerGroupTransactionBroadcaster setPreventDoubleSpendSeconds(int seconds) {
        this.preventDoubleSpendSeconds = seconds;
        return this;
    }

    public PeerGroupTransactionBroadcaster setBroadcastTransactionListener(BroadcastTransactionListener broadcastListener) {
        return this.setBroadcastTransactionListener(broadcastListener, Threading.USER_THREAD);
    }

    public PeerGroupTransactionBroadcaster setBroadcastTransactionListener(BroadcastTransactionListener broadcastListener,
                                                                           @NotNull Executor executor) {
        boolean shouldInvoke;
        int num;
        boolean mined;
        synchronized (this) {
            this.broadcastListener = broadcastListener;
            this.broadcastListenerExecutor = executor;
            num = this.numSeemPeers;
            mined = this.isMined;
            shouldInvoke = peerGroupBroadcastStrategy.getBroadcastTargetSize() > 0;
        }
        if (shouldInvoke) {
            notifyBroadcastProgress(num, mined);
        }
        return this;
    }

    public ListenableFuture<Transaction> future() {
        return future;
    }

    public ListenableFuture<Transaction> broadcast() {
        peerGroup.addPreMessageReceivedEventListener(Threading.SAME_THREAD, rejectionListener);
        peerGroup.addOnTransactionBroadcastListener(Threading.SAME_THREAD, transactionReceivedListener);
        log.info("Waiting for {} peers required for broadcast, we have {} ...", minConnections, peerGroup.getConnectedPeers().size());
        peerGroup.waitForPeers(minConnections).addListener(new EnoughAvailablePeers(), Threading.SAME_THREAD);
        stopPreventingDoubleSpendTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                synchronized (lock){
                    if (!isDoubleSpendSecondsElapsed){
                        onDoubleSpendPreventionElapsed();
                    }
                }
            }
        }, preventDoubleSpendSeconds * 1000);
        return future;
    }

    private void onRejectionMessage(Peer peer, RejectMessage rejectMessage) {
        if (tx.getHash().equals(rejectMessage.getRejectedObjectHash())) {
            if (rejectMessage.getReasonCode() == RejectMessage.RejectCode.DUPLICATE) {
                notifyDoubleSpend(tx);
            } else {
                rejects.put(peer, rejectMessage);
                int size = rejects.size();
                long threshold = Math.round(peerGroupBroadcastStrategy.getBroadcastTargetSize() / 2.0);
                if (size > threshold) {
                    log.warn("Threshold for considering broadcast rejected has been reached ({}/{})", size, threshold);
                    notifyTransactionRejectedError(tx, rejectMessage);
                }
            }
        }
    }

    private void onBroadcastProgress(int numSeenPeers, boolean mined) {
        synchronized (this) {
            this.numSeemPeers = numSeenPeers;
            this.isMined = mined;
        }
        notifyBroadcastProgress(numSeenPeers, mined);
    }

    private void onBroadcastCompleted() {
        isBroadcastCompleted = true;
        notifyBroadcastSuccess();
    }

    private void onDoubleSpendPreventionElapsed() {
        isDoubleSpendSecondsElapsed = true;
        peerGroup.removeOnTransactionBroadcastListener(transactionReceivedListener);
        notifyBroadcastSuccess();
    }

    private void removeListeners() {
        peerGroup.removePreMessageReceivedEventListener(rejectionListener);
        peerGroup.removeOnTransactionBroadcastListener(transactionReceivedListener);
        tx.getConfidence().removeEventListener(confidenceChangeListener);
    }

    private void notifyBroadcastProgress(final int numSeenPeers, boolean mined) {
        final BroadcastTransactionListener listener;
        Executor executor;
        synchronized (this) {
            listener = this.broadcastListener;
            executor = this.broadcastListenerExecutor;
        }
        if (listener != null) {
            final int targetPeers = peerGroupBroadcastStrategy.getBroadcastTargetSize();
            final double progress = Math.min(1.0, mined ? 1.0 : numSeenPeers / (double) targetPeers);
            checkState(progress >= 0.0 && progress <= 1.0, progress);
            try {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        listener.onProgress(progress, numSeenPeers, targetPeers);
                    }
                });
            } catch (Throwable e) {
                log.error("Exception during progress callback", e);
            }
        }
    }

    private void notifyBroadcastSuccess() {
        if (isBroadcastCompleted && isDoubleSpendSecondsElapsed) {
            removeListeners();
            future.set(tx);
            broadcastListenerExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    broadcastListener.onBroadcastSuccess(tx, isMined);
                }
            });
        }
    }

    private void notifyDoubleSpend(final Transaction detectedTx) {
        removeListeners();
        future.setException(new RejectedTransactionException(tx, null));
        broadcastListenerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                broadcastListener.onDoubleSpendDetected(tx, detectedTx);
            }
        });
    }

    private void notifyTransactionRejectedError(final Transaction tx, final RejectMessage rejectMessage) {
        removeListeners();
        future.setException(new RejectedTransactionException(tx, rejectMessage));
        broadcastListenerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                broadcastListener.onBroadcastRejected(tx, rejectMessage);
            }
        });
    }

    private class EnoughAvailablePeers implements Runnable {
        @Override
        public void run() {
            int numConnected = peerGroup.getConnectedPeers().size();
            log.info("broadcastTransaction: We have {} peers, adding {} to the memory pool", numConnected, tx.getHashAsString());

            List<Peer> peers = peerGroupBroadcastStrategy.choosePeers(peerGroup);
            if (minConnections > 1) {
                tx.getConfidence().addEventListener(confidenceChangeListener);
            }
            for (Peer peer : peers) {
                try {
                    peer.sendMessage(tx);
                } catch (Exception e) {
                    log.error("Caught exception sending to {}", peer, e);
                }
            }
            if (minConnections == 1) {
                onBroadcastCompleted();
            }
        }
    }

    private class ConfidenceChangeListener implements TransactionConfidence.Listener {
        @Override
        public void onConfidenceChanged(TransactionConfidence conf, ChangeReason reason) {
            synchronized (lock){
                if (!isBroadcastCompleted){
                    int numSeenPeers = conf.numBroadcastPeers() + rejects.size();
                    boolean mined = tx.getAppearsInHashes() != null;
                    log.info("broadcastTransaction: {}:  TX {} seen by {} peers{}",
                            reason, tx.getHashAsString(), numSeenPeers, mined ? " and isMined" : "");

                    onBroadcastProgress(numSeenPeers, mined);

                    if (numSeenPeers >= peerGroupBroadcastStrategy.getBroadcastTargetSize() || mined) {
                        log.info("broadcastTransaction: {} complete", tx.getHash());
                        conf.removeEventListener(this);
                        onBroadcastCompleted();
                    }
                }
            }
        }
    }

    private class RejectionListener implements PreMessageReceivedEventListener {
        @Override
        public Message onPreMessageReceived(Peer peer, Message m) {
            if (m instanceof RejectMessage) {
                RejectMessage rejectMessage = (RejectMessage) m;
                onRejectionMessage(peer, rejectMessage);
            }
            return m;
        }
    }

    private class TransactionReceivedListener implements OnTransactionBroadcastListener {

        private DoubleSpentDetector doubleSpentDetector;

        TransactionReceivedListener() {
            doubleSpentDetector = DoubleSpentDetector.create();
        }

        @Override
        public void onTransaction(Peer peer, Transaction receivedTransaction) {
            if (doubleSpentDetector.isDoubleSpend(tx, receivedTransaction)) {
                notifyDoubleSpend(receivedTransaction);
            }
        }
    }

}
