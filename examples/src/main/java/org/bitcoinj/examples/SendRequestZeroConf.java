/*
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

package org.bitcoinj.examples;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.bitcoinj.broadcast.BroadcastTransactionListener;
import org.bitcoinj.core.*;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.wallet.Wallet;

import java.io.File;
import java.util.concurrent.Future;

/**
 * The following example shows you how to create a SendRequest to send coins from a wallet to a given address.
 */
public class SendRequestZeroConf {

    public static void main(String[] args) throws Exception {

        // We use the WalletAppKit that handles all the boilerplate for us. Have a look at the Kit.java example for more details.
        NetworkParameters params = TestNet3Params.get();
        WalletAppKit kit = new WalletAppKit(params, new File("."), "sendrequest-example") {
            @Override
            protected void onSetupCompleted() {
                super.onSetupCompleted();
                wallet().allowSpendingUnconfirmedTransactions();
            }
        };
        kit.startAsync();
        kit.awaitRunning();

        System.out.println("Send money to: " + kit.wallet().currentReceiveAddress().toString());

        // How much coins do we want to send?
        // The Coin class represents a monetary Bitcoin value.
        // We use the parseCoin function to simply get a Coin instance from a simple String.
        Coin value = Coin.parseCoin("1");

        // To which address you want to send the coins?
        // The Address class represents a Bitcoin address.
        Address to = Address.fromBase58(params, "mgRoeWs2CeCEuqQmNfhJjnpX8YvtPACmCX");

        // There are different ways to create and publish a SendRequest. This is probably the easiest one.
        // Have a look at the code of the SendRequest class to see what's happening and what other options you have: https://bitcoinj.github.io/javadoc/0.11/com/google/bitcoin/core/Wallet.SendRequest.html
        //
        // Please note that this might raise a InsufficientMoneyException if your wallet has not enough coins to spend.
        // When using the testnet you can use a faucet (like the http://faucet.xeno-genesis.com/) to get testnet coins.
        // In this example we catch the InsufficientMoneyException and register a BalanceFuture callback that runs once the wallet has enough balance.
        Future<Transaction> future = null;
        try {
            org.bitcoinj.wallet.SendRequest sendRequest = org.bitcoinj.wallet.SendRequest.to(to, value);
            sendRequest.setUseForkId(true);
            kit.wallet().completeTx(sendRequest);
            future = kit.peerGroup().getTransactionBroadcaster(sendRequest.tx)
                    .setBroadcastTransactionListener(new BroadcastTransactionListener() {
                        @Override
                        public void onBroadcastSuccess(Transaction tx, boolean isMined) {
                            System.out.println("Transaction broadcasted successfully");
                        }

                        @Override
                        public void onProgress(double percentage, int totalBroadcasts, int target) {
                            System.out.println("Transaction broadcast progress: " + percentage);
                        }

                        @Override
                        public void onDoubleSpendDetected(Transaction broadcastedTx, Transaction detectedTx) {
                            System.out.println("Transaction double spend detected");
                        }

                        @Override
                        public void onBroadcastRejected(Transaction tx, RejectMessage rejectMessage) {
                            System.out.println("Transaction broadcast rejected: " + rejectMessage);
                        }
                    })
                    .setMinConnections(4)
                    .broadcast();
            // you can use a block explorer like https://www.biteasy.com/ to inspect the transaction with the printed transaction hash.
        } catch (InsufficientMoneyException e) {
            System.out.println("Not enough coins in your wallet. Missing " + e.missing.getValue() + " satoshis are missing (including fees)");
            System.out.println("Send money to: " + kit.wallet().currentReceiveAddress().toString());

            // Bitcoinj allows you to define a BalanceFuture to execute a callback once your wallet has a certain balance.
            // Here we wait until the we have enough balance and display a notice.
            // Bitcoinj is using the ListenableFutures of the Guava library. Have a look here for more information: https://github.com/google/guava/wiki/ListenableFutureExplained
            ListenableFuture<Coin> balanceFuture = kit.wallet().getBalanceFuture(value, Wallet.BalanceType.AVAILABLE);
            FutureCallback<Coin> callback = new FutureCallback<Coin>() {
                @Override
                public void onSuccess(Coin balance) {
                    System.out.println("coins arrived and the wallet now has enough balance");
                }

                @Override
                public void onFailure(Throwable t) {
                    System.out.println("something went wrong");
                }
            };
            Futures.addCallback(balanceFuture, callback);
        }
        while (future != null && !future.isDone()) {
            Thread.sleep(500);
        }
        // shutting down
        //kit.stopAsync();
        //kit.awaitTerminated();
    }
}
