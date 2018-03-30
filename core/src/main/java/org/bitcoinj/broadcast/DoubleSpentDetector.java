package org.bitcoinj.broadcast;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;

import java.util.HashSet;
import java.util.Set;

public class DoubleSpentDetector {

    public static DoubleSpentDetector create() {
        return new DoubleSpentDetector();
    }

    public boolean isDoubleSpend(Transaction tx1, Transaction tx2) {
        if (tx1.equals(tx2)) {
            return false;
        }
        Set<TransactionInput> spentInputs = new HashSet<TransactionInput>(tx1.getInputs());
        for (TransactionInput input : tx2.getInputs()) {
            if (spentInputs.contains(input)) {
                return true;
            }
        }
        return false;
    }

}
