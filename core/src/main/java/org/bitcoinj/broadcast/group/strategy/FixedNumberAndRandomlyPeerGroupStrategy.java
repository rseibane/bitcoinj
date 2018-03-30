package org.bitcoinj.broadcast.group.strategy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import org.bitcoinj.broadcast.group.BroadcastPeerGroupStrategy;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class FixedNumberAndRandomlyPeerGroupStrategy implements BroadcastPeerGroupStrategy {

    /**
     * Used for shuffling the peers before broadcast: unit tests can replace this to make themselves deterministic.
     */
    @VisibleForTesting
    public static Random random = new Random();

    private static final Logger log = LoggerFactory.getLogger(FixedNumberAndRandomlyPeerGroupStrategy.class);

    private int targetSize;

    public FixedNumberAndRandomlyPeerGroupStrategy(int targetNumber) {
        this.targetSize = targetNumber;
    }

    @Override
    public List<Peer> choosePeers(PeerGroup peerGroup) {
        List<Peer> peers = peerGroup.getConnectedPeers();
        int connectedSize = peers.size();
        Collections.shuffle(peers, random);
        log.info("Sending to {} peers, will wait for {}, sending to: {}", connectedSize, targetSize, Joiner.on(",").join(peers));
        return peers;
    }

    @Override
    public int getBroadcastTargetSize() {
        return targetSize;
    }

}
