/*
 * Copyright (C) ST-Ericsson SA 2011
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony.cat;

import android.os.Parcel;
import android.os.Parcelable;


/**
 * Class for representing "BearerDescription" object for STK.
 *
 * {@hide}
 */
public class BearerDescription implements Parcelable {
    public BearerType type;
    public byte[] parameters = new byte[0];

    public enum BearerType {
        MOBILE_CSD(0x01),
        MOBILE_PS(0x02),
        DEFAULT_BEARER(0x03),
        LOCAL_LINK(0x04),
        BLUETOOTH(0x05),
        IRDA(0x06),
        RS232(0x07),
        MOBILE_PS_EXTENDED_QOS(0x09),
        I_WLAN(0x0A),
        USB(0x10);

        private int mValue;

        BearerType(int value) {
            mValue = value;
        }

        public int value() {
            return mValue;
        }
    }

    public BearerDescription(BearerType type, byte[] parameters) {
        this.type = type;
        this.parameters = parameters;
    }

    private BearerDescription(Parcel in) {
        type = BearerType.values()[in.readInt()];
        int len = in.readInt();
        if (len > 0) {
            parameters = new byte[len];
            in.readByteArray(parameters);
        }
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(type.ordinal());
        dest.writeInt(parameters.length);
        if (parameters.length > 0) {
            dest.writeByteArray(parameters);
        }
    }

    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<BearerDescription> CREATOR = new Parcelable.Creator<BearerDescription>() {
        public BearerDescription createFromParcel(Parcel in) {
            return new BearerDescription(in);
        }

        public BearerDescription[] newArray(int size) {
            return new BearerDescription[size];
        }
    };
}
