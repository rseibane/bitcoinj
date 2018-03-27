package org.bitcoinj.broadcast.group;

import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;

import java.util.List;

public interface BroadcastPeerGroupStrategy {

    List<Peer> choosePeers(PeerGroup peerGroup);

    int getBroadcastTargetSize();

}
