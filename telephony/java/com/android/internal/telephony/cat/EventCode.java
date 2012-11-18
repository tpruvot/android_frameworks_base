/*
 * Copyright (C) 2006 The Android Open Source Project
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


/**
 * Enumeration for the event code in SET UP EVENT LIST and event download.
 * To get the actual return code for each enum value, call {@link #code() code}
 * method.
 *
 * {@hide}
 */
public enum EventCode {

    MT_CALL(0x00),
    CALL_CONNECTED(0x01),
    CALL_DISCONNECTED(0x02),
    LOACATION_STATUS(0x03),
    USER_ACTIVITY(0x04),
    IDLE_SCREEN_AVAILABLE(0x05),
    CARD_READER_STATUS(0x06),
    LANGUAGE_SELECTION(0x07),
    BROWSER_TERMINATION(0x08),
    DATA_AVAILABLE(0x09),
    CHANNEL_STATUS(0x0A);

    private int mCode;

    EventCode(int code) {
        mCode = code;
    }

    /**
     * Retrieves the actual result code that this object represents.
     * @return Actual result code
     */
    public int value() {
        return mCode;
    }

    public static EventCode fromInt(int value) {
        for (EventCode r : EventCode.values()) {
            if (r.mCode == value) {
                return r;
            }
        }
        return null;
    }
}
