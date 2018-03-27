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

public class RatioOfConnectedRandomlyPeerGroupStrategy implements BroadcastPeerGroupStrategy {

    /**
     * Used for shuffling the peers before broadcast: unit tests can replace this to make themselves deterministic.
     */
    @VisibleForTesting
    public static Random random = new Random();

    private static final Logger log = LoggerFactory.getLogger(RatioOfConnectedRandomlyPeerGroupStrategy.class);

    private float broadcastRatio;
    private float targetRatio;
    private int targetSize;

    public RatioOfConnectedRandomlyPeerGroupStrategy(float broadcastRatio, float targetRatio) {
        this.broadcastRatio = broadcastRatio;
        this.targetRatio = targetRatio;
    }

    @Override
    public List<Peer> choosePeers(PeerGroup peerGroup) {
        List<Peer> peers = peerGroup.getConnectedPeers();
        int numConnected = peers.size();
        int numToBroadcastTo = (int) Math.max(1, Math.ceil(numConnected * broadcastRatio));
        targetSize = (int) Math.max(1, Math.ceil(numConnected * targetRatio));
        Collections.shuffle(peers, random);
        log.info("Sending to {} peers, will wait for {}, sending to: {}", numToBroadcastTo, targetSize, Joiner.on(",").join(peers));
        return peers.subList(0, numToBroadcastTo);
    }

    @Override
    public int getBroadcastTargetSize() {
        return targetSize;
    }

}
