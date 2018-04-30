package org.bitcoinj.core;

public class AddressFactory {

    public static AddressFactory create() {
        return new AddressFactory();
    }

    public Address getAddress(NetworkParameters params, String plainAddress) {
        if (plainAddress.length() <= 34) {
            return Address.fromBase58(params, plainAddress);
        } else {
            return CashAddressFactory.create().getFromFormattedAddress(params, plainAddress);
        }
    }
} 