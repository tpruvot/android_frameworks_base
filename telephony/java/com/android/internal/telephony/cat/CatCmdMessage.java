/*
 * Copyright (C) 2007 The Android Open Source Project
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

import com.android.internal.telephony.cat.InterfaceTransportLevel.TransportProtocol;

/**
 * Class used to pass CAT messages from telephony to application. Application
 * should call getXXX() to get commands's specific values.
 *
 */
public class CatCmdMessage implements Parcelable {
    // members
    CommandDetails mCmdDet;
    private TextMessage mTextMsg;
    private Menu mMenu;
    private Input mInput;
    private BrowserSettings mBrowserSettings = null;
    private ToneSettings mToneSettings = null;
    private CallSettings mCallSettings = null;
    private ChannelSettings mChannelSettings = null;
    private DataSettings mDataSettings = null;
    private byte[] mEventList = null;

    /*
     * Container for Launch Browser command settings.
     */
    public class BrowserSettings {
        public String url;
        public LaunchBrowserMode mode;
    }

    /*
     * Container for Call Setup command settings.
     */
    public class CallSettings {
        public TextMessage confirmMsg;
        public TextMessage callMsg;
    }

    /*
     * Container for BIP extensions.
     */
    public class ChannelSettings {
        public int channel;
        public TransportProtocol protocol;
        public int port;
        public int bufSize;
        public byte[] destinationAddress;
        public BearerDescription bearerDescription;
        public String networkAccessName;
        public String userLogin;
        public String userPassword;
        public Integer cid;
    }

    public class DataSettings {
        public int channel;
        public int length;
        public byte[] data;
    }


    CatCmdMessage(CommandParams cmdParams) {
        mCmdDet = cmdParams.cmdDet;
        switch(getCmdType()) {
        case SET_UP_MENU:
        case SELECT_ITEM:
            mMenu = ((SelectItemParams) cmdParams).menu;
            break;
        case DISPLAY_TEXT:
        case SET_UP_IDLE_MODE_TEXT:
        case SEND_DTMF:
        case SEND_SMS:
        case SEND_SS:
        case SEND_USSD:
            mTextMsg = ((DisplayTextParams) cmdParams).textMsg;
            break;
        case GET_INPUT:
        case GET_INKEY:
            mInput = ((GetInputParams) cmdParams).input;
            break;
        case LAUNCH_BROWSER:
            mTextMsg = ((LaunchBrowserParams) cmdParams).confirmMsg;
            mBrowserSettings = new BrowserSettings();
            mBrowserSettings.url = ((LaunchBrowserParams) cmdParams).url;
            mBrowserSettings.mode = ((LaunchBrowserParams) cmdParams).mode;
            break;
        case PLAY_TONE:
            PlayToneParams params = (PlayToneParams) cmdParams;
            mToneSettings = params.settings;
            mTextMsg = params.textMsg;
            break;
        case SET_UP_CALL:
            mCallSettings = new CallSettings();
            mCallSettings.confirmMsg = ((CallSetupParams) cmdParams).confirmMsg;
            mCallSettings.callMsg = ((CallSetupParams) cmdParams).callMsg;
            break;
/*
        case OPEN_CHANNEL:
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
            BIPClientParams param = (BIPClientParams) cmdParams;
            mTextMsg = param.textMsg;
            break;
*/
        case SET_UP_EVENT_LIST:
            mEventList = ((EventListParams) cmdParams).eventList;
            break;
        case OPEN_CHANNEL:
            mTextMsg = ((OpenChannelParams) cmdParams).confirmMsg;
            mChannelSettings = new ChannelSettings();
            mChannelSettings.channel = 0;
            mChannelSettings.protocol =
                    ((OpenChannelParams) cmdParams).itl.protocol;
            mChannelSettings.port = ((OpenChannelParams) cmdParams).itl.port;
            mChannelSettings.bufSize = ((OpenChannelParams) cmdParams).bufSize;
            mChannelSettings.destinationAddress = ((OpenChannelParams) cmdParams).destinationAddress;
            mChannelSettings.bearerDescription = ((OpenChannelParams) cmdParams).bearerDescription;
            mChannelSettings.networkAccessName = ((OpenChannelParams) cmdParams).networkAccessName;
            mChannelSettings.userLogin = ((OpenChannelParams) cmdParams).userLogin;
            mChannelSettings.userPassword = ((OpenChannelParams) cmdParams).userPassword;
            break;
        case CLOSE_CHANNEL:
            mDataSettings = new DataSettings();
            mDataSettings.channel = ((CloseChannelParams) cmdParams).channel;
            mDataSettings.length = 0;
            mDataSettings.data = null;
            break;
        case RECEIVE_DATA:
            mDataSettings = new DataSettings();
            mDataSettings.channel = ((ReceiveDataParams) cmdParams).channel;
            mDataSettings.length = ((ReceiveDataParams) cmdParams).datLen;
            mDataSettings.data = null;
            break;
        case SEND_DATA:
            mDataSettings = new DataSettings();
            mDataSettings.channel = ((SendDataParams) cmdParams).channel;
            mDataSettings.length = 0;
            mDataSettings.data = ((SendDataParams) cmdParams).data;
            break;
        case GET_CHANNEL_STATUS:
            mDataSettings = new DataSettings();
            mDataSettings.channel = 0;
            mDataSettings.length = 0;
            mDataSettings.data = null;
            break;
        }
    }

    public CatCmdMessage(Parcel in) {
        mCmdDet = in.readParcelable(null);
        mTextMsg = in.readParcelable(null);
        mMenu = in.readParcelable(null);
        mInput = in.readParcelable(null);
        switch (getCmdType()) {
        case LAUNCH_BROWSER:
            mBrowserSettings = new BrowserSettings();
            mBrowserSettings.url = in.readString();
            mBrowserSettings.mode = LaunchBrowserMode.values()[in.readInt()];
            break;
        case PLAY_TONE:
            mToneSettings = in.readParcelable(null);
            break;
        case SET_UP_CALL:
            mCallSettings = new CallSettings();
            mCallSettings.confirmMsg = in.readParcelable(null);
            mCallSettings.callMsg = in.readParcelable(null);
            break;
        case SET_UP_EVENT_LIST:
            {
                mEventList = null;
                int len = in.readInt();
                if (len > 0) {
                    mEventList = new byte[len];
                    in.readByteArray(mEventList);
                }
            }
            break;
        case OPEN_CHANNEL:
            mChannelSettings = new ChannelSettings();
            mChannelSettings.channel = in.readInt();
            mChannelSettings.protocol =
                    TransportProtocol.values()[in.readInt()];
            mChannelSettings.port = in.readInt();
            mChannelSettings.bufSize = in.readInt();
            mChannelSettings.destinationAddress = new byte[in.readInt()];
            if (mChannelSettings.destinationAddress.length > 0) {
                in.readByteArray(mChannelSettings.destinationAddress);
            }
            mChannelSettings.bearerDescription = (BearerDescription) in.readValue(BearerDescription.class.getClassLoader());
            mChannelSettings.networkAccessName = in.readString();
            mChannelSettings.userLogin = in.readString();
            mChannelSettings.userPassword = in.readString();
            break;
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
            mDataSettings = new DataSettings();
            mDataSettings.channel = in.readInt();
            mDataSettings.length = in.readInt();
            mDataSettings.data = null;
            int len = in.readInt();
            if (len > 0) {
                mDataSettings.data = new byte[len];
                in.readByteArray(mDataSettings.data);
            }
            break;
        }
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mCmdDet, 0);
        dest.writeParcelable(mTextMsg, 0);
        dest.writeParcelable(mMenu, 0);
        dest.writeParcelable(mInput, 0);
        switch(getCmdType()) {
        case LAUNCH_BROWSER:
            dest.writeString(mBrowserSettings.url);
            dest.writeInt(mBrowserSettings.mode.ordinal());
            break;
        case PLAY_TONE:
            dest.writeParcelable(mToneSettings, 0);
            break;
        case SET_UP_CALL:
            dest.writeParcelable(mCallSettings.confirmMsg, 0);
            dest.writeParcelable(mCallSettings.callMsg, 0);
            break;
        case SET_UP_EVENT_LIST:
            {
                int len = 0;
                if (mEventList != null)
                    len = mEventList.length;
                dest.writeInt(len);
                if (len > 0)
                    dest.writeByteArray(mEventList);
            }
            break;
        case OPEN_CHANNEL:
            dest.writeInt(mChannelSettings.channel);
            dest.writeInt(mChannelSettings.protocol.value());
            dest.writeInt(mChannelSettings.port);
            dest.writeInt(mChannelSettings.bufSize);
            if (mChannelSettings.destinationAddress != null) {
                dest.writeInt(mChannelSettings.destinationAddress.length);
                dest.writeByteArray(mChannelSettings.destinationAddress);
            } else {
                dest.writeInt(0);
            }
            dest.writeValue(mChannelSettings.bearerDescription);
            dest.writeString(mChannelSettings.networkAccessName);
            dest.writeString(mChannelSettings.userLogin);
            dest.writeString(mChannelSettings.userPassword);
            break;
        case CLOSE_CHANNEL:
        case RECEIVE_DATA:
        case SEND_DATA:
            dest.writeInt(mDataSettings.channel);
            dest.writeInt(mDataSettings.length);
            int len = 0;
            if (mDataSettings.data != null)
                len = mDataSettings.data.length;
            dest.writeInt(len);
            if (len > 0)
                dest.writeByteArray(mDataSettings.data);
            break;
        }
    }

    public static final Parcelable.Creator<CatCmdMessage> CREATOR = new Parcelable.Creator<CatCmdMessage>() {
        public CatCmdMessage createFromParcel(Parcel in) {
            return new CatCmdMessage(in);
        }

        public CatCmdMessage[] newArray(int size) {
            return new CatCmdMessage[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    /* external API to be used by application */
    public AppInterface.CommandType getCmdType() {
        return AppInterface.CommandType.fromInt(mCmdDet.typeOfCommand);
    }

    public Menu getMenu() {
        return mMenu;
    }

    public Input geInput() {
        return mInput;
    }

    public TextMessage geTextMessage() {
        return mTextMsg;
    }

    public BrowserSettings getBrowserSettings() {
        return mBrowserSettings;
    }

    public ToneSettings getToneSettings() {
        return mToneSettings;
    }

    public CallSettings getCallSettings() {
        return mCallSettings;
    }

    /*
     * BIP Extensions
     */
    public int getCommandQualifier() {
        return mCmdDet.commandQualifier;
    }

    public byte[] getEventList() {
        return mEventList;
    }

    public DataSettings getDataSettings() {
        return mDataSettings;
    }

    public ChannelSettings getChannelSettings() {
        return mChannelSettings;
    }
}
