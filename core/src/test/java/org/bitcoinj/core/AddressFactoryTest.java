/*
 * Copyright 2011 Google Inc.
 * Copyright 2014 Andreas Schildbach
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

package org.bitcoinj.core;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.bitcoinj.params.MainNetParams;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class AddressFactoryTest {
    static final String ADDRESSES_FILE_PATH = "org/bitcoinj/core/bch_addresses.csv";
    static final Map<String, String> CASH_ADDRESS_BY_LEGACY_FORMAT = new HashMap<String, String>();
    static final NetworkParameters params = MainNetParams.get();

    private AddressFactory addressFactory;

    @BeforeClass
    public static void loadAddressBatch() throws IOException {
        ClassLoader classLoader = CashAddressTest.class.getClassLoader();
        URL url = classLoader.getResource(ADDRESSES_FILE_PATH);
        List<String> lines = Resources.readLines(url, Charsets.UTF_8);
        for (String line : lines) {
            String[] components = line.split(",");
            CASH_ADDRESS_BY_LEGACY_FORMAT.put(components[0], components[1]);
        }
    }

    @Before
    public void setUpCashAddressFactory() {
        addressFactory = AddressFactory.create();
    }

    @Test
    public void testCompareAddress() {
        for (String legacyAddressFormat : CASH_ADDRESS_BY_LEGACY_FORMAT.keySet()) {
            String cashAddressFormat = CASH_ADDRESS_BY_LEGACY_FORMAT.get(legacyAddressFormat);

            Address cashAddress = addressFactory.getAddress(params, cashAddressFormat);
            Address legacyAddress = addressFactory.getAddress(params, legacyAddressFormat);

            assertEquals(cashAddress.toBase58(), legacyAddress.toBase58());
            assertEquals(cashAddress.toCashAddressFormat(), legacyAddress.toCashAddressFormat());
        }
    }

}
