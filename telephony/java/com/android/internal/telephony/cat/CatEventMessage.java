/*
 * Copyright (C) 2010 Giesecke & Devrient GmbH
 * Copyright (C) 2012 CyanogenMod Project
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

public class CatEventMessage {
    private int mEvent = 0;
    private int mSourceId = 0x82;   // TE
    private int mDestId = 0x81;     // UICC
    private byte[] mAdditionalInfo = null;
    private boolean mOneShot = false;


    public CatEventMessage(int event, int sourceId, int destId, byte[] additionalInfo, boolean oneShot ) {
        this.mEvent = event;
        this.mSourceId = sourceId;
        this.mDestId = destId;
        this.mAdditionalInfo = additionalInfo;
        this.mOneShot = oneShot;
    }

    public CatEventMessage(int event, byte[] additionalInfo, boolean oneShot ) {
        this.mEvent = event;
        this.mAdditionalInfo = additionalInfo;
        this.mOneShot = oneShot;
    }

    public int getEvent(){
        return mEvent;
    }

    public int getSourceId(){
        return mSourceId;
    }

    public int getDestId(){
        return mDestId;
    }

    public byte[] getAdditionalInfo(){
        return mAdditionalInfo;
    }

    public boolean isOneShot(){
        return mOneShot;
    }
}

