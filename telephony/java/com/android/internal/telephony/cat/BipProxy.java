/*
 * Copyright (C) 2011 Giesecke & Devrient GmbH
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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.NetworkInfo;
import android.net.NetworkUtils;
import android.net.RouteInfo;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.DataCallState;
import com.android.internal.telephony.Call.State;
import com.android.internal.telephony.cat.AppInterface.CommandType;
import com.android.internal.telephony.cat.BearerDescription.BearerType;
import com.android.internal.telephony.cat.InterfaceTransportLevel.TransportProtocol;
import com.android.internal.telephony.cat.CatCmdMessage.ChannelSettings;
import com.android.internal.telephony.cat.CatCmdMessage.DataSettings;

public class BipProxy extends Handler {

    final int TCP_CHANNEL_BUFFER_SIZE = 16384; // reserve 16k as Tx/Rx per Buffer for TCP
    final int UDP_CHANNEL_BUFFER_SIZE = 1500; // Restrict UDP packet size to 1500 bytes due to MTU restriction
    final int MAX_CHANNEL_NUM = 7; // Must match Terminal Profile

    static final int MSG_ID_SETUP_DATA_CALL      = 10;
    static final int MSG_ID_TEARDOWN_DATA_CALL   = 11;

    private CatService mCatService = null;
    private CommandsInterface mCmdIf;
    private Context mContext;
    private DefaultBearerStateReceiver mDefaultBearerStateReceiver;

    private BipChannel mBipChannels[] = new BipChannel[MAX_CHANNEL_NUM];

    public BipProxy(CatService catService, CommandsInterface cmdIf, Context context) {
        mCatService = catService;
        mCmdIf = cmdIf;
        mContext = context;
        mDefaultBearerStateReceiver = new DefaultBearerStateReceiver(context);
    }

    /**
     * If user confirmation should be handled in CatService then the CatService needs to determine if we can handle more channels.
     * @return
     */
    public boolean canHandleNewChannel() {
        for (int i = 0; i< mBipChannels.length; i++) {
            if (mBipChannels[i] == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Common handler for BIP related proactive commands.
     *
     * User confirmation shall be handled before call to this function, but we
     * still have access to the result using cmdMsg.getTextMessage() if required
     * later for example when we try to establish a data connection.
     *
     * @param cmdMsg null indicates session end
     */
    public void handleBipCommand(CatCmdMessage cmdMsg) {

        // Handle session end
        if (cmdMsg == null) {
            for (int i = 0; i< mBipChannels.length; i++) {
                if (mBipChannels[i] != null) {
                    mBipChannels[i].onSessionEnd();
                }
            }
            return;
        }

        CommandType curCmdType = cmdMsg.getCmdType();

        switch (curCmdType) {
        case OPEN_CHANNEL:
            ChannelSettings channelSettings = cmdMsg.getChannelSettings();
            if (channelSettings != null) {

                if (allChannelsClosed()) {
                    /* This is our first open channel request. Fire up the broadcast receiver */
                    mDefaultBearerStateReceiver.startListening();
                }

                // Find next available channel identifier
                for (int i = 0; i< mBipChannels.length; i++) {
                    if (mBipChannels[i] == null) {
                        channelSettings.channel = i + 1;
                        break;
                    }
                }
                if (channelSettings.channel == 0) {
                    //Send TR No channel available
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x01, null);
                    return;
                }

                switch (channelSettings.protocol) {
                case TCP_SERVER:
                    mBipChannels[channelSettings.channel -1] = new TcpServerChannel();
                    break;
                case TCP_CLIENT_REMOTE:
                case TCP_CLIENT_LOCAL:
                    mBipChannels[channelSettings.channel -1] = new TcpClientChannel();
                    break;
                case UDP_CLIENT_REMOTE:
                case UDP_CLIENT_LOCAL:
                    mBipChannels[channelSettings.channel -1] = new UdpClientChannel();
                    break;
                default:
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.CMD_DATA_NOT_UNDERSTOOD, false, 0, null);
                    return;
                }

                if (setupDataConnection(cmdMsg)) {
                    // Data connection available, or not needed continue open the channel
                    CatLog.d(this, "Continue processing open channel");
                    if (!mBipChannels[channelSettings.channel -1].open(cmdMsg)) {
                        cleanupBipChannel(channelSettings.channel);
                    }
                }
                return;

            }
            break;

        case SEND_DATA:
        case RECEIVE_DATA:
        case CLOSE_CHANNEL:
            if (cmdMsg.getDataSettings() != null) {
                try {
                    BipChannel curChannel = mBipChannels[cmdMsg.getDataSettings().channel - 1];
                    if (curChannel != null) {
                        if (curCmdType == CommandType.SEND_DATA) {
                            curChannel.send(cmdMsg);
                            return;
                        } else if (curCmdType == CommandType.RECEIVE_DATA) {
                            curChannel.receive(cmdMsg);
                            return;
                        } else if (curCmdType == CommandType.CLOSE_CHANNEL) {
                            curChannel.close(cmdMsg);
                            cleanupBipChannel(cmdMsg.getDataSettings().channel);
                            return;
                        }
                    } else {
                        // Send TR Channel identifier not valid
                        mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x03, null);
                        return;
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    // Send TR Channel identifier not valid
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x03, null);
                    return;
                }
            }
            break;

        case GET_CHANNEL_STATUS:
            int[] status = new int[MAX_CHANNEL_NUM];
            for( int i = 0; i < MAX_CHANNEL_NUM; i++) {
                if (mBipChannels[i] != null) {
                    status[i] = mBipChannels[i].getStatus();
                } else {
                    status[i] = 0; // Not a valid channel (Should not be present in the terminal response)
                }
            }
            ResponseData resp = new ChannelStatusResponseData(status);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, resp);
            return;

        }

        mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.CMD_DATA_NOT_UNDERSTOOD, false, 0, null);
    }

    /**
     * Check to see if all BIP channels are closed
     * @return true if all channels are closed.
     */
    private boolean allChannelsClosed() {
        for (BipChannel channel : mBipChannels) {
            if (channel != null)
                return false;
        }
        return true;
    }

    private void cleanupBipChannel(int channel) {
        mBipChannels[channel - 1] = null;
        if (allChannelsClosed())
            mDefaultBearerStateReceiver.stopListening();  /*All channels are closed.  Stop the broadcast receiver.*/
    }

    private void sendChannelStatusEvent(int channelStatus) {
        byte[] additionalInfo = { (byte)0xb8, 0x02, 0x00, 0x00 };
        additionalInfo[2] = (byte) ((channelStatus >> 8) & 0xff);
        additionalInfo[3] = (byte) (channelStatus & 0xff);
        mCatService.onEventDownload( new CatEventMessage( EventCode.CHANNEL_STATUS.value(), additionalInfo, true ));
        //TODO use mCatService.eventDownload instead!
//        mCatService.eventDownload(EventCode.CHANNEL_STATUS.value(), sourceId, destinationId, additionalInfo, true);
    }

    private void sendDataAvailableEvent(int channelStatus, int dataAvailable) {
        byte[] additionalInfo = { (byte)0xb8, 0x02, 0x00, 0x00, (byte)0xb7, 0x01, 0x00 };
        additionalInfo[2] = (byte) ((channelStatus >> 8) & 0xff);
        additionalInfo[3] = (byte) (channelStatus & 0xff);
        additionalInfo[6] = (byte) (dataAvailable & 0xff);
        mCatService.onEventDownload( new CatEventMessage( EventCode.DATA_AVAILABLE.value(), additionalInfo, true ));
        //TODO use mCatService.eventDownload instead!
//        mCatService.eventDownload(EventCode.DATA_AVAILABLE.value(), sourceId, destinationId, additionalInfo, true);
    }

    private class ConnectionSetupFailedException extends IOException {
        public ConnectionSetupFailedException(String message) {
            super(message);
        }
    };

    private NetworkInfo findAvailableDefaultBearer(NetworkInfo[] networkInfos) {
        ArrayList<NetworkInfo> availableBearers = new ArrayList<NetworkInfo>();
        for (NetworkInfo info : networkInfos) {
           if (info != null && info.isAvailable()) {
               switch (info.getType()) {
                   case ConnectivityManager.TYPE_MOBILE:
                   case ConnectivityManager.TYPE_WIFI:
                   case ConnectivityManager.TYPE_WIMAX:
                       availableBearers.add(info);
                       break;
                   default:
                       break;  // Unusable type
               }
           }
        }

        if (availableBearers.size() == 0) {
            return null; /* No default bearers available. */
        }

        NetworkInfo candidateBearer = null;
        for (NetworkInfo info : availableBearers) {
            NetworkInfo.State state = info.getState();
            if (state == NetworkInfo.State.CONNECTED) {
                candidateBearer = info; /* Found a connected bearer. We are happy */
                break;
            } else if (state == NetworkInfo.State.CONNECTING || state == NetworkInfo.State.SUSPENDED) {
                candidateBearer = info; /* Found a possible bearer. Look further in case there are better. */
            }
        }

        return candidateBearer;
    }

    private boolean setupDefaultDataConnection(CatCmdMessage cmdMsg) throws ConnectionSetupFailedException {
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo[] netInfos = cm.getAllNetworkInfo();
        ChannelSettings newChannel = cmdMsg.getChannelSettings();
        boolean result = false;

        if (netInfos == null || netInfos.length == 0 || null == findAvailableDefaultBearer(netInfos)) {
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null);
            throw new ConnectionSetupFailedException("No default bearer available");
        }

        NetworkInfo netInfo = findAvailableDefaultBearer(netInfos);
        NetworkInfo.State state = netInfo.getState();
        ConnectionSetupFailedException setupFailedException = null;

        switch (state) {
            case CONNECTED:
                CatLog.d(this, "Default bearer is connected");
                result = true;
                break;
            case CONNECTING:
                CatLog.d(this, "Default bearer is connecting.  Waiting for connect");
                Message resultMsg = obtainMessage(MSG_ID_SETUP_DATA_CALL, cmdMsg);
                mDefaultBearerStateReceiver.setOngoingSetupMessage(resultMsg);
                result = false;
                break;
            case SUSPENDED:
                /* Suspended state is only possible for mobile data accounts during voice calls */
                CatLog.d(this, "Default bearer not connected, busy on voice call");
                ResponseData resp = new OpenChannelResponseData(newChannel.bufSize, null, newChannel.bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, true, 0x02, resp);
                setupFailedException = new ConnectionSetupFailedException("Default bearer suspended!");
                break;
            default:
                /* The default bearer is disconnected either due to error or user preference.
                 * Either way, there's nothing we can do. */
                CatLog.d(this, "Default bearer is Disconnected");
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null);
                setupFailedException = new ConnectionSetupFailedException("Default bearer is disconnected!");
                break;
        }

        if (setupFailedException != null) {
            throw setupFailedException;
        }

        return result;
    }

    private boolean setupSpecificPdpConnection(CatCmdMessage cmdMsg) throws ConnectionSetupFailedException {
        ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        TelephonyManager tm = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        ChannelSettings newChannel = cmdMsg.getChannelSettings();

        if (!cm.getMobileDataEnabled()) {
            CatLog.d(this, "User does not allow mobile data connections");
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null); //TODO fix
            throw new ConnectionSetupFailedException("No mobile data connections allowed!");
        }

        if (newChannel.networkAccessName == null) {
            CatLog.d(this, "no accessname for PS bearer req");
            return setupDefaultDataConnection(cmdMsg);
        }

        if (tm.getCallState() != TelephonyManager.CALL_STATE_IDLE) {  // TODO: Check for class A/B support
            CatLog.d(this, "Bearer not setup, busy on voice call");
            ResponseData resp = new OpenChannelResponseData(newChannel.bufSize, null, newChannel.bearerDescription);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.TERMINAL_CRNTLY_UNABLE_TO_PROCESS, true, 0x02, resp);
            throw new ConnectionSetupFailedException("Busy on voice call");
        }

        CatLog.d(this, "Detected new data connection parameters");
        /* try to setup new PDP context */
        Message resultMsg = obtainMessage(MSG_ID_SETUP_DATA_CALL, cmdMsg);
        mCmdIf.setupDataCall("1", "1", newChannel.networkAccessName, newChannel.userLogin, newChannel.userPassword, "3", "IP", resultMsg); //TODO check parameters
        /* Response is handled by onSetupConnectionCompleted() */

        return false;
    }

    /**
     *
     * @param cmdMsg The Command Message that initiated the connection.
     * @return true if data connection is established, false if error occurred or data connection is being established.
     */
    private boolean setupDataConnection(CatCmdMessage cmdMsg) {
        boolean result = false;
        ChannelSettings newChannel = cmdMsg.getChannelSettings();

        if (newChannel.protocol != TransportProtocol.TCP_CLIENT_REMOTE
                && newChannel.protocol != TransportProtocol.UDP_CLIENT_REMOTE) {
            CatLog.d(this, "No data connection needed for this channel");
            return true;
        }

        BearerDescription bd = newChannel.bearerDescription;

        try {
            if (bd.type == BearerType.DEFAULT_BEARER) {
                result = setupDefaultDataConnection(cmdMsg);
            } else if (bd.type == BearerType.MOBILE_PS || bd.type == BearerType.MOBILE_PS_EXTENDED_QOS) {
                result = setupSpecificPdpConnection(cmdMsg);
            } else {
                // send TR error
                CatLog.d(this, "Unsupported bearer type");
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BEYOND_TERMINAL_CAPABILITY, false, 0, null);
            }

        } catch (ConnectionSetupFailedException csfe) {
            CatLog.d(this, "setupDataConnection Failed: " + csfe.getMessage());
             // Free resources since channel could not be opened
            mBipChannels[newChannel.channel -1] = null;
            cleanupBipChannel(newChannel.channel);
        }

        return result;
    }

    /**
     *
     * @param cmdMsg
     * @param cid
     * @return true if teardown of data connection is pending
     */
    private boolean teardownDataConnection(CatCmdMessage cmdMsg, int cid) {
        Message resultMsg = obtainMessage(MSG_ID_TEARDOWN_DATA_CALL, cmdMsg);
        mCmdIf.deactivateDataCall(cid, 0, resultMsg);
        return true;
    }

    private void onSetupConnectionCompleted(AsyncResult ar) {
        String[] response;
        CatCmdMessage cmdMsg;

        if (ar == null) {
            return;
        }

        cmdMsg = (CatCmdMessage) ar.userObj;
        response = (String[]) ar.result;

        if (ar.exception != null) {
            CatLog.d(this, "Failed to setup data connection for channel: " +cmdMsg.getChannelSettings().channel);
            cmdMsg.getChannelSettings().cid = null;
            ResponseData resp = new OpenChannelResponseData(cmdMsg.getChannelSettings().bufSize, null, cmdMsg.getChannelSettings().bearerDescription);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.NETWORK_CRNTLY_UNABLE_TO_PROCESS, false, 0, resp);
            cleanupBipChannel(cmdMsg.getChannelSettings().channel);
        } else {
            if (response != null && response.length >= 2) {
                if (response[0] == null) { /*Default bearer*/
                    CatLog.d(this, "Succeeded to setup data connection for channel " + "- Default bearer");
                } else { /* Specific pdp */
                    int cid = Integer.parseInt(response[0]);
                    String interfaceName = response[1];
                    CatLog.d(this, "Succeeded to setup data connection for channel " +cmdMsg.getChannelSettings().channel +" cid=" +cid +" ifname=" +interfaceName);

                    // Store connection id to be able to disconnect when processing CLOSE_CHANNEL for this channel
                    cmdMsg.getChannelSettings().cid = cid;
                    // TODO Possible to extract and store IP as localAddress if requested in the PC

                    //NetworkUtils.removeDefaultRoute(interfaceName);
                    int addr = ((cmdMsg.getChannelSettings().destinationAddress[3] & 0xff) << 24)
                            | ((cmdMsg.getChannelSettings().destinationAddress[2] & 0xff) << 16)
                            | ((cmdMsg.getChannelSettings().destinationAddress[1] & 0xff) << 8)
                            |  (cmdMsg.getChannelSettings().destinationAddress[0] & 0xff);

                    try {
                        InetAddress ip = InetAddress.getByAddress(cmdMsg.getChannelSettings().destinationAddress);

                        LinkProperties source = new LinkProperties();
                        source.setInterfaceName(interfaceName);

                        source.addRoute(RouteInfo.makeHostRoute(ip));
                    }
                    catch (java.net.UnknownHostException e) {}

                    //NetworkUtils.addHostRoute(interfaceName, addr);
                }

                //CatLog.d(this, "Continue processing open channel");
                if (!mBipChannels[cmdMsg.getChannelSettings().channel-1].open(cmdMsg)) {
                    // Failed to open channel, free resources
                    cleanupBipChannel(cmdMsg.getChannelSettings().channel);
                }

            } else {
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, null); //TODO fix?
                cleanupBipChannel(cmdMsg.getChannelSettings().channel);
            }
        }
    }

    private void onTeardownConnectionCompleted(AsyncResult ar) {
        CatCmdMessage cmdMsg;
        int channel;

        if (ar == null) {
            return;
        }

        cmdMsg = (CatCmdMessage) ar.userObj;

        if (cmdMsg.getCmdType() == CommandType.OPEN_CHANNEL) {
            channel = cmdMsg.getChannelSettings().channel;
        } else if (cmdMsg.getCmdType() == CommandType.CLOSE_CHANNEL) {
            channel = cmdMsg.getDataSettings().channel;
        } else {
            return;
        }

        if (ar.exception != null) {
            CatLog.d(this, "Failed to teardown data connection for channel: " +channel + " " + ar.exception.getMessage() );
        } else {
            CatLog.d(this, "Succedded to teardown data connection for channel: " +channel);
        }

        cleanupBipChannel(channel);
    }

    @Override
    public void handleMessage(Message msg) {

        switch (msg.what) {
        case MSG_ID_SETUP_DATA_CALL:
            if (msg.obj != null) {
                onSetupConnectionCompleted((AsyncResult) msg.obj);
            }
            break;
        case MSG_ID_TEARDOWN_DATA_CALL:
            if (msg.obj != null) {
                onTeardownConnectionCompleted((AsyncResult) msg.obj);
            }
            break;
        default:
            throw new AssertionError("Unrecognized message: " + msg.what);
        }
    }

    interface BipChannel {

        /**
         * Process OPEN_CHANNEL command.
         *
         * Caller must free resources reserved if false is returned.
         *
         * @param cmdMsg
         * @return false if channel could not be established
         */
        public boolean open(CatCmdMessage cmdMsg);

        public void close(CatCmdMessage cmdMsg);

        public void send(CatCmdMessage cmdMsg);

        public void receive(CatCmdMessage cmdMsg);

        public int getStatus();

        public void onSessionEnd();
    }

    /**
     * UICC Server Mode
     *
     * Note: Terminal responses to the proactive commands are sent from the functions (open/close etc.) and events are sent from the thread.
     */
    class TcpServerChannel implements BipChannel {

        ChannelSettings mChannelSettings = null;
        int mChannelStatus = 0;

        ServerThread mThread = null;

        ServerSocket mServerSocket;
        Socket mSocket;

        byte[] mRxBuf = new byte[TCP_CHANNEL_BUFFER_SIZE];
        int mRxPos = 0;
        int mRxLen = 0;

        byte[] mTxBuf = new byte[TCP_CHANNEL_BUFFER_SIZE];
        int mTxPos = 0;
        int mTxLen = 0;

        @Override
        public boolean open(CatCmdMessage cmdMsg) {
            ResultCode result = ResultCode.OK;

            mChannelSettings = cmdMsg.getChannelSettings();
            mChannelStatus = mChannelSettings.channel << 8; // Closed state

            if (mChannelSettings.bufSize > TCP_CHANNEL_BUFFER_SIZE) {
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                mChannelSettings.bufSize = TCP_CHANNEL_BUFFER_SIZE;
            } else if (mChannelSettings.bufSize > 0) {
                mRxBuf = new byte[mChannelSettings.bufSize];
                mTxBuf = new byte[mChannelSettings.bufSize];
            } else {
                mChannelSettings.bufSize = TCP_CHANNEL_BUFFER_SIZE;
            }

            try {
                mServerSocket = new ServerSocket(mChannelSettings.port);

                CatLog.d(this, "Open server socket on port " + mChannelSettings.port + " for channel " + mChannelSettings.channel );

                // Update channel status to listening before sending TR
                mChannelStatus = 0x4000 + (mChannelSettings.channel << 8);
                ResponseData resp = new OpenChannelResponseData(mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, result, false, 0, resp);

            } catch(IOException e) {
                ResponseData resp = new OpenChannelResponseData(mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp);
                CatLog.d(this, "IOException " + e.getMessage() );
                return false;
            }
            new ServerThread().start();
            return true;
        }

        @Override
        public void close(CatCmdMessage cmdMsg) {

            if ((cmdMsg.getCommandQualifier() & 0x01) == 0x01) {
                //Close only client connection
                if (mSocket != null && !mSocket.isClosed()) {
                    try {
                        mSocket.close();
                    } catch (IOException e) {
                    }
                }
                mSocket = null;

                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, null);

            } else {

                if (mSocket != null && !mSocket.isClosed()) {
                    try {
                        mSocket.close();
                    } catch (IOException e) {
                    }
                }
                mSocket = null;

                if (mServerSocket != null && !mServerSocket.isClosed()) {
                    try {
                        mServerSocket.close();
                    } catch (IOException e) {
                    }
                }
                mServerSocket = null;

                mRxPos = 0;
                mRxLen = 0;
                mTxPos = 0;
                mTxLen = 0;

                // Update channel status to closed before sending TR
                mChannelStatus = mChannelSettings.channel << 8;
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, null);

                sendChannelStatusEvent(mChannelStatus);
            }
        }

        @Override
        public void send(CatCmdMessage cmdMsg) {
            DataSettings dataSettings = cmdMsg.getDataSettings();
            CatLog.d(this, "SEND_DATA on channel no: " + dataSettings.channel);

            // transfer data into tx buffer.
            //CatLog.d( this, "Transfer data into tx buffer" );
            for( int i = 0;
                  i < dataSettings.data.length &&
                   mTxPos < mTxBuf.length; // sanity check
                   i++ ){
                mTxBuf[mTxPos++] = dataSettings.data[i]; //TODO why not use System.arraycopy
            }
            mTxLen += dataSettings.data.length;
            CatLog.d( this, "Tx buffer now contains " +  mTxLen + " bytes.");

            // check if data shall be sent immediately
            if( cmdMsg.getCommandQualifier() == 0x01 ) {
                // TODO reset mTxlen/pos first when data successfully has been sent?
                mTxPos = 0;
                int len = mTxLen;
                mTxLen = 0;
                CatLog.d( this, "Sent data to socket " +  len + " bytes.");

                // check if client socket still exists.
                if (mSocket == null) {
                    CatLog.d( this, "Socket not available.");
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp); //TODO correct?
                    return;
                }

                try {
                    mSocket.getOutputStream().write(mTxBuf, 0, len);
                    //CatLog.d(this, "Data on channel no: " + dataSettings.channel + " sent to socket.");

                } catch(IOException e) {
                    CatLog.d( this, "IOException " + e.getMessage() );
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp); //TODO correct?
                    return;
                }
            }

            int avail = 0xee;
            if( mChannelSettings != null ) {
                // estimate number of bytes left in tx buffer.
                // bufSize contains either the requested bufSize or
                // the max supported buffer size.
                avail = mChannelSettings.bufSize - mTxLen;
                if( avail > 0xff ) {
                    avail = 0xff;
                }
            }
            //CatLog.d(this, "TR with " + avail + " bytes available in Tx Buffer on channel no: " + dataSettings.channel);

            ResponseData resp = new SendDataResponseData(avail);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, resp);
        }

        @Override
        public void receive(CatCmdMessage cmdMsg) {
            ResultCode result = ResultCode.OK;
            ResponseData resp = null;

            CatLog.d(this, "RECEIVE_DATA on channel no: " + cmdMsg.getDataSettings().channel);

            int requested = cmdMsg.getDataSettings().length;
            if (requested > 0xec) {
                /* The maximum length of Terminal Response APDU is 0xff bytes,
                 * so the maximum length of channel data is 0xec when length of
                 * other mandatory TLVS are subtracted.
                 * sch 2011-07-05
                 * But some (U)SIMs allow a maximum length of 256 bytes, then
                 * the max. allowed requested length is 0xed
                 * ste 2011-08-31
                 * Yes but then it would not work for 0xec cards!
                 */
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                requested = 0xec;
            }
            if (requested > mRxLen) {
                requested = mRxLen;
                result = ResultCode.PRFRMD_WITH_MISSING_INFO;
            }

            mRxLen -= requested;
            int available = 0xff;
            if (mRxLen < available)
                available = mRxLen;

            byte[] data = null;
            if (requested > 0) {
                data = new byte[requested];
                System.arraycopy(mRxBuf, mRxPos, data, 0, requested);
                mRxPos += requested;
            }

            resp = new ReceiveDataResponseData(data, available);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, result, false, 0, resp);
        }

        @Override
        public int getStatus() {
            if (mChannelSettings.channel == 0) {
                mChannelStatus = mChannelSettings.channel << 8; // Closed
            }
            return mChannelStatus;
        }

        @Override
        public void onSessionEnd() {
            // close any existing client connection
            // so that we can handle the next waiting client request.
            if(mSocket != null ){
                if(!mSocket.isClosed()){
                    try {
                        mSocket.close();
                    } catch (IOException ioex ) {
                        // nothing to do, since we don't need this socket
                        // any longer.
                    }
                }
                mSocket=null;
            }

            // restart server thread.
            if (mThread == null || !mThread.isAlive()) {
                mThread = new ServerThread();
                mThread.start();
            }
        }

        class ServerThread extends Thread {

            @Override
            public void run() {

                CatLog.d(this, "Server thread start on channel no: " + mChannelSettings.channel);

                if (mSocket == null || mSocket.isClosed()) {

                    // event download - channel listen
                    mChannelStatus = 0x4000 + (mChannelSettings.channel << 8);
                    sendChannelStatusEvent(mChannelStatus);

                    //listen
                    try {
                        CatLog.d(this, "Wait for connection");
                        mSocket = mServerSocket.accept();
                        //CatLog.d(this, "New connection");
                    } catch(IOException e) {
                        CatLog.d(this, "IOException " + e.getMessage());
                        // TODO find out if serverSocket is OK else we will end up in a loop
                    }

                    // event download, channel established
                    if (mSocket != null && mSocket.isConnected()) {
                        mChannelStatus = 0x8000 + (mChannelSettings.channel << 8);
                        sendChannelStatusEvent(mChannelStatus);
                    }
                }

                if (mSocket != null) {
                    // client connected, wait until some data is ready
                    try {
                        //CatLog.d(this, "Reading from input stream");
                        mRxLen = mSocket.getInputStream().read(mRxBuf);
                    } catch(IOException e) {
                        CatLog.d(this, "Read on No: " + mChannelSettings.channel + ", IOException " + e.getMessage());
                        mSocket = null; // throw client socket away.
                        //Invalidate data
                        mRxBuf = new byte[mChannelSettings.bufSize];
                        mTxBuf = new byte[mChannelSettings.bufSize];
                        mRxPos = 0;
                        mRxLen = 0;
                        mTxPos = 0;
                        mTxLen = 0;
                    }

                    // sanity check
                    if (mRxLen <= 0) {
                        CatLog.d(this, "No data read.");
                    } else {

                        mRxPos = 0;
                        int available = 0xff;
                        if (mRxLen < available) {
                            available = mRxLen;
                        }

                        // event download, data available
                        sendDataAvailableEvent(mChannelStatus, (byte) (available & 0xff));
                    }
                } else {
                    CatLog.d(this, "No Socket connection for server thread on channel no: " + mChannelSettings.channel);
                }

                CatLog.d(this, "Server thread end on channel no: " + mChannelSettings.channel);
            }
        }

    }

    /**
     * TCP Client channel for remote and local(Terminal Server Mode) connections
     *
     * Note: Terminal responses and channel status events are from the functions (open/close etc.) and data available events are sent from the thread.
     */
    class TcpClientChannel implements BipChannel {

        ChannelSettings mChannelSettings = null;
        int mChannelStatus = 0;

        TcpClientThread mThread = null;

        Socket mSocket;

        byte[] mRxBuf = new byte[TCP_CHANNEL_BUFFER_SIZE];
        int mRxPos = 0;
        int mRxLen = 0;

        byte[] mTxBuf = new byte[TCP_CHANNEL_BUFFER_SIZE];
        int mTxPos = 0;
        int mTxLen = 0;

        @Override
        public boolean open(CatCmdMessage cmdMsg) {
            ResultCode result = ResultCode.OK;

            mChannelSettings = cmdMsg.getChannelSettings();
            mChannelStatus = mChannelSettings.channel << 8; // Closed state

            if (mChannelSettings.bufSize > TCP_CHANNEL_BUFFER_SIZE) {
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                mChannelSettings.bufSize = TCP_CHANNEL_BUFFER_SIZE;
            } else {
                mRxBuf = new byte[mChannelSettings.bufSize];
                mTxBuf = new byte[mChannelSettings.bufSize];
            }

            // get server address and try to connect.
            try {
                InetAddress addr = null;
                if (mChannelSettings.protocol == TransportProtocol.TCP_CLIENT_REMOTE) {
                    addr = InetAddress.getByAddress(mChannelSettings.destinationAddress);
                } else {
                    addr = InetAddress.getLocalHost();
                }

                //CatLog.d(this, "Connecting client socket to " + addr.getHostAddress() + ":" +mChannelSettings.port +" for channel " + mChannelSettings.channel );
                mSocket = new Socket(addr, mChannelSettings.port);

                CatLog.d(this, "Connected client socket to " + addr.getHostAddress() + ":" +mChannelSettings.port +" for channel " + mChannelSettings.channel );

                // Update channel status to open before sending TR
                mChannelStatus = 0x8000 + (mChannelSettings.channel << 8);
                ResponseData resp = new OpenChannelResponseData(mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, result, false, 0, resp);

                sendChannelStatusEvent(mChannelStatus);

            } catch(IOException e) {
                CatLog.d(this, "OPEN_CHANNEL - Client connection failed: " + e.getMessage() );
                ResponseData resp = new OpenChannelResponseData(mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp); //TODO correct?
                CatLog.d(this, "IOException " + e.getMessage() );
                if (mChannelSettings.cid != null) {
                    teardownDataConnection(cmdMsg, mChannelSettings.cid);
                }
                return false;
            }
            mThread = new TcpClientThread();
            mThread.start();
            return true;
        }

        @Override
        public void close(CatCmdMessage cmdMsg) {

            if (mSocket != null && !mSocket.isClosed()) {
                try {
                    mSocket.close();
                } catch (IOException e) {
                }
            }
            mSocket = null;

            mRxPos = 0;
            mRxLen = 0;
            mTxPos = 0;
            mTxLen = 0;

            // Update channel status to closed before sending TR
            mChannelStatus = mChannelSettings.channel << 8;
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, null);

            sendChannelStatusEvent(mChannelStatus);

            if (mChannelSettings.cid != null) {
                teardownDataConnection(cmdMsg, mChannelSettings.cid);
            }
        }

        @Override
        public void send(CatCmdMessage cmdMsg) {
            DataSettings dataSettings = cmdMsg.getDataSettings();
            CatLog.d(this, "SEND_DATA on channel no: " + dataSettings.channel);

            // transfer data into tx buffer.
            CatLog.d( this, "Transfer data into tx buffer" );
            for( int i = 0;
                  i < dataSettings.data.length &&
                   mTxPos < mTxBuf.length; // sanity check
                   i++ ){
                mTxBuf[mTxPos++] = dataSettings.data[i]; //TODO why not use System.arraycopy
            }
            mTxLen += dataSettings.data.length;
            CatLog.d( this, "Tx buffer now contains " +  mTxLen + " bytes.");

            // check if data shall be sent immediately
            if( cmdMsg.getCommandQualifier() == 0x01 ) {
                // TODO reset mTxlen/pos first when data successfully has been sent?
                mTxPos = 0;
                int len = mTxLen;
                mTxLen = 0;
                CatLog.d( this, "Sent data to socket " +  len + " bytes.");

                // check if client socket still exists.
                if (mSocket == null) {
                    CatLog.d( this, "Socket not available.");
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp); //TODO correct?
                    return;
                }

                try {
                    mSocket.getOutputStream().write(mTxBuf, 0, len);
                    //CatLog.d(this, "Data on channel no: " + dataSettings.channel + " sent to socket.");

                } catch(IOException e) {
                    CatLog.d( this, "IOException " + e.getMessage() );
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp); //TODO correct?
                    return;
                }
            }

            int avail = 0xee;
            if( mChannelSettings != null ) {
                // estimate number of bytes left in tx buffer.
                // bufSize contains either the requested bufSize or
                // the max supported buffer size.
                avail = mChannelSettings.bufSize - mTxLen;
                if( avail > 0xff ) {
                    avail = 0xff;
                }
            }
            CatLog.d(this, "TR with " + avail + " bytes available in Tx Buffer on channel no: " + dataSettings.channel);

            ResponseData resp = new SendDataResponseData(avail);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, resp);
        }

        @Override
        public void receive(CatCmdMessage cmdMsg) {
            ResultCode result = ResultCode.OK;
            ResponseData resp = null;

            CatLog.d(this, "RECEIVE_DATA on channel no: " + cmdMsg.getDataSettings().channel);

            int requested = cmdMsg.getDataSettings().length;
            if (requested > 0xec) {
                /* The maximum length of Terminal Response APDU is 0xff bytes,
                 * so the maximum length of channel data is 0xec when length of
                 * other mandatory TLVS are subtracted.
                 * sch 2011-07-05
                 * But some (U)SIMs allow a maximum length of 256 bytes, then
                 * the max. allowed requested length is 0xed
                 * ste 2011-08-31
                 * Yes but then it would not work for 0xec cards!
                 */
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                requested = 0xec;
            }
            if (requested > mRxLen) {
                requested = mRxLen;
                result = ResultCode.PRFRMD_WITH_MISSING_INFO;
            }

            mRxLen -= requested;
            int available = 0xff;
            if (mRxLen < available)
                available = mRxLen;

            byte[] data = null;
            if (requested > 0) {
                data = new byte[requested];
                System.arraycopy(mRxBuf, mRxPos, data, 0, requested);
                mRxPos += requested;
            }

            resp = new ReceiveDataResponseData(data, available);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, result, false, 0, resp);
        }

        @Override
        public int getStatus() {
            if (mChannelSettings.channel == 0) {
                mChannelStatus = mChannelSettings.channel << 8; // Closed
            }
            return mChannelStatus;
        }

        @Override
        public void onSessionEnd() {
            if (mThread == null || !mThread.isAlive()) {
                mThread = new TcpClientThread();
                mThread.start();
            }
        }

        class TcpClientThread extends Thread {

            @Override
            public void run() {
                CatLog.d(this, "Client thread start on channel no: " + mChannelSettings.channel);

                if (mSocket != null) {
                    // client connected, wait until some data is ready
                    try {
                        mRxLen = mSocket.getInputStream().read(mRxBuf);
                    } catch(IOException e) {
                        CatLog.d(this, "Read on No: " + mChannelSettings.channel + ", IOException " + e.getMessage());
                        mSocket = null; // throw client socket away.
                        //Invalidate data
                        mRxBuf = new byte[mChannelSettings.bufSize];
                        mTxBuf = new byte[mChannelSettings.bufSize];
                        mRxPos = 0;
                        mRxLen = 0;
                        mTxPos = 0;
                        mTxLen = 0;
                    }

                    // sanity check
                    if (mRxLen <= 0) {
                        CatLog.d(this, "No data read.");
                    } else {
                        //CatLog.d(this, mRxLen +" data read.");
                        mRxPos = 0;
                        int available = 0xff;
                        if (mRxLen < available) {
                            available = mRxLen;
                        }

                        // event download, data available
                        sendDataAvailableEvent(mChannelStatus, (byte) (available & 0xff));
                    }
                }
                CatLog.d(this, "Client thread end on channel no: " + mChannelSettings.channel);
            }
        }

    }

    /**
     * UDP Client channel for remote and local(Terminal Server Mode) connections
     */
    class UdpClientChannel implements BipChannel {

        ChannelSettings mChannelSettings = null;
        int mChannelStatus = 0;

        UdpClientThread mThread = null;

        DatagramSocket mDatagramSocket;

        byte[] mRxBuf = new byte[UDP_CHANNEL_BUFFER_SIZE];
        int mRxPos = 0;
        int mRxLen = 0;

        byte[] mTxBuf = new byte[UDP_CHANNEL_BUFFER_SIZE];
        int mTxPos = 0;
        int mTxLen = 0;

        @Override
        public boolean open(CatCmdMessage cmdMsg) {
            ResultCode result = ResultCode.OK;
            
            mChannelSettings = cmdMsg.getChannelSettings();
            mChannelStatus = mChannelSettings.channel << 8; // Closed state
    
            if (mChannelSettings.bufSize > UDP_CHANNEL_BUFFER_SIZE) {
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                mChannelSettings.bufSize = UDP_CHANNEL_BUFFER_SIZE;
            } else if (mChannelSettings.bufSize > 0) {
                mRxBuf = new byte[mChannelSettings.bufSize];
                mTxBuf = new byte[mChannelSettings.bufSize];
            } else {
                mChannelSettings.bufSize = UDP_CHANNEL_BUFFER_SIZE;
            }

            // get server address and try to connect.
            try {
                InetAddress addr = null;
                if (mChannelSettings.protocol == TransportProtocol.UDP_CLIENT_REMOTE) {
                    addr = InetAddress.getByAddress(mChannelSettings.destinationAddress);
                } else {
                    addr = InetAddress.getLocalHost();
                }

                CatLog.d(this, "Creating " + ((mChannelSettings.protocol == TransportProtocol.UDP_CLIENT_REMOTE) ? "remote" : "local")
                    + " client socket to " + addr.getHostAddress() + ":" + mChannelSettings.port + " for channel " + mChannelSettings.channel );

                mDatagramSocket = new DatagramSocket();
                mDatagramSocket.connect(addr, mChannelSettings.port);

                CatLog.d(this, "Connected UDP client socket to " + addr.getHostAddress() + ":" + mChannelSettings.port
                    + " for channel " + mChannelSettings.channel );

                // Update channel status to open before sending TR
                mChannelStatus = 0x8000 + (mChannelSettings.channel << 8);
                ResponseData resp = new OpenChannelResponseData(mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, result, false, 0, resp);

                sendChannelStatusEvent(mChannelStatus);

            } catch(IOException e) {
                CatLog.d(this, "OPEN_CHANNEL - UDP Client connection failed: " + e.getMessage() );
                ResponseData resp = new OpenChannelResponseData(mChannelSettings.bufSize, mChannelStatus, mChannelSettings.bearerDescription);
                mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp); //TODO correct?
                if (mChannelSettings.cid != null) {
                    teardownDataConnection(cmdMsg, mChannelSettings.cid);
                }
                return false;
            }

            mThread = new UdpClientThread();
            mThread.start();
            return true;
        }

        @Override
        public void close(CatCmdMessage cmdMsg) {

            if (mDatagramSocket != null && !mDatagramSocket.isClosed()) {
                mDatagramSocket.close();
            }

            mDatagramSocket = null;

            mRxPos = 0;
            mRxLen = 0;
            mTxPos = 0;
            mTxLen = 0;

            // Update channel status to closed before sending TR
            mChannelStatus = mChannelSettings.channel << 8;
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, null);

            sendChannelStatusEvent(mChannelStatus);

            if (mChannelSettings.cid != null) {
                teardownDataConnection(cmdMsg, mChannelSettings.cid);
            }
        }

        @Override
        public void send(CatCmdMessage cmdMsg) {
            DataSettings dataSettings = cmdMsg.getDataSettings();
            CatLog.d(this, "SEND_DATA on channel no: " + dataSettings.channel);

            // transfer data into tx buffer.
            CatLog.d( this, "Transfer data into tx buffer" );
            for( int i = 0;
                 i < dataSettings.data.length &&
                 mTxPos < mTxBuf.length; // sanity check
                   i++ ){
                mTxBuf[mTxPos++] = dataSettings.data[i]; //TODO why not use System.arraycopy
            }
            mTxLen += dataSettings.data.length;
            CatLog.d( this, "Tx buffer now contains " +  mTxLen + " bytes.");

            // check if data shall be sent immediately
            if( cmdMsg.getCommandQualifier() == 0x01 ) {
                // TODO reset mTxlen/pos first when data successfully has been sent?
                mTxPos = 0;
                int len = mTxLen;
                mTxLen = 0;
                CatLog.d( this, "Sent data to socket " +  len + " bytes.");

                // check if client socket still exists.
                if (mDatagramSocket == null) {
                    CatLog.d( this, "Socket not available.");
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp); //TODO correct?
                    return;
                }

                try {
                    mDatagramSocket.send(new DatagramPacket(mTxBuf, len));
                    CatLog.d(this, "Data on channel no: " + dataSettings.channel + " sent to socket.");
                } catch(IOException e) {
                    CatLog.d( this, "IOException " + e.getMessage() );
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp);
                    return;
                } catch (IllegalArgumentException e) {
                    CatLog.d(this, "IllegalArgumentException " + e.getMessage());
                    ResponseData resp = new SendDataResponseData(0);
                    mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.BIP_ERROR, true, 0x00, resp);
                    return;
                }
            }

            int avail = 0xee;
            if( mChannelSettings != null ) {
                // estimate number of bytes left in tx buffer.
                // bufSize contains either the requested bufSize or
                // the max supported buffer size.
                avail = mChannelSettings.bufSize - mTxLen;
                if( avail > 0xff ) {
                    avail = 0xff;
                }
            }
            CatLog.d(this, "TR with " + avail + " bytes available in Tx Buffer on channel no: " + dataSettings.channel);

            ResponseData resp = new SendDataResponseData(avail);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, ResultCode.OK, false, 0, resp);
        }

        @Override
        public void receive(CatCmdMessage cmdMsg) {
            ResultCode result = ResultCode.OK;
            ResponseData resp = null;

            CatLog.d(this, "RECEIVE_DATA on channel no: " + cmdMsg.getDataSettings().channel);

            int requested = cmdMsg.getDataSettings().length;
            if (requested > 0xec) {
                /* The maximum length of Terminal Response APDU is 0xff bytes,
                 * so the maximum length of channel data is 0xec when length of
                 * other mandatory TLVS are subtracted.
                 * sch 2011-07-05
                 * But some (U)SIMs allow a maximum length of 256 bytes, then
                 * the max. allowed requested length is 0xed
                 * ste 2011-08-31
                 * Yes but then it would not work for 0xec cards!
                 */
                result = ResultCode.PRFRMD_WITH_MODIFICATION;
                requested = 0xec;
            }
            if (requested > mRxLen) {
                requested = mRxLen;
                result = ResultCode.PRFRMD_WITH_MISSING_INFO;
            }

            mRxLen -= requested;
            int available = 0xff;
            if (mRxLen < available)
                available = mRxLen;

            byte[] data = null;
            if (requested > 0) {
                data = new byte[requested];
                System.arraycopy(mRxBuf, mRxPos, data, 0, requested);
                mRxPos += requested;
            }

            resp = new ReceiveDataResponseData(data, available);
            mCatService.sendTerminalResponse(cmdMsg.mCmdDet, result, false, 0, resp);
        }

        @Override
        public int getStatus() {
            if (mChannelSettings.channel == 0) {
                mChannelStatus = mChannelSettings.channel << 8; // Closed
            }
            return mChannelStatus;
        }

        @Override
        public void onSessionEnd() {
            if (mThread == null || !mThread.isAlive()) {
                mThread = new UdpClientThread();
                mThread.start();
            }
        }

        class UdpClientThread extends Thread {

            @Override
            public void run() {
                CatLog.d(this, "UDP Client thread start on channel no: " + mChannelSettings.channel);

                if (mDatagramSocket != null) {
                    // client connected, wait until some data is ready
                    DatagramPacket packet = null;
                    boolean success = false;

                    try {
                        CatLog.d(this, "UDP Client listening on port : " + mDatagramSocket.getLocalPort());
                        packet = new DatagramPacket(mRxBuf, mRxBuf.length);
                        mDatagramSocket.receive(packet);
                        success = true;
                    } catch(IOException e) {
                        CatLog.d(this, "Read on No: " + mChannelSettings.channel + ", IOException " + e.getMessage());
                    } catch (IllegalArgumentException e) {
                        CatLog.d(this, "IllegalArgumentException: " + e.getMessage());
                    }

                    if (success) {
                        mRxLen = packet.getLength();
                    } else {
                        mDatagramSocket = null; // throw client socket away.
                        //Invalidate data
                        mRxBuf = new byte[mChannelSettings.bufSize];
                        mTxBuf = new byte[mChannelSettings.bufSize];
                        mRxPos = 0;
                        mRxLen = 0;
                        mTxPos = 0;
                        mTxLen = 0;
                    }

                    // sanity check
                    if (mRxLen <= 0) {
                        CatLog.d(this, "No data read.");
                    } else {
                        CatLog.d(this, mRxLen +" data read.");
                        mRxPos = 0;
                        int available = 0xff;
                        if (mRxLen < available) {
                            available = mRxLen;
                        }

                        // event download, data available
                        sendDataAvailableEvent(mChannelStatus, (byte) (available & 0xff));
                    }
                }

                CatLog.d(this, "UDP Client thread end on channel no: " + mChannelSettings.channel);
            }
        }

    }

    class DefaultBearerStateReceiver extends BroadcastReceiver {

        Message mOngoingSetupMessage = null;
        final Object mSetupMessageLock = new Object();
        Context mContext;
        ConnectivityManager mCm;
        IntentFilter mFilter;
        boolean mIsRegistered;

        public DefaultBearerStateReceiver(Context context) {
            mContext = context;
            mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            mFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            mIsRegistered = false;

        }

        public void startListening() {
            if (mIsRegistered)
                return; /* already registered. */
            mContext.registerReceiver(this, mFilter);
            mIsRegistered = true;
        }

        public void stopListening() {
            if (!mIsRegistered)
                return; /* already de-registered*/
            mContext.unregisterReceiver(this);
            mOngoingSetupMessage = null;
            mIsRegistered = false;
        }

        public void setOngoingSetupMessage(Message msg) {
            synchronized (mSetupMessageLock) {
                mOngoingSetupMessage = msg;
            }
        }

        private void onDisconnected() {
            CatLog.d(this, "onDisconnected");
            Message msg = null;
            synchronized (mSetupMessageLock) {
                if (mOngoingSetupMessage == null)
                    return;
                msg = mOngoingSetupMessage;
                mOngoingSetupMessage = null;
            }
            ConnectionSetupFailedException csfe = new ConnectionSetupFailedException("Default bearer failed to connect");
            AsyncResult.forMessage(msg, null, csfe);
            msg.sendToTarget();
        }

        private void onConnected() {
            CatLog.d(this, "onConnected");
            Message msg = null;
            synchronized (mSetupMessageLock) {
                if (mOngoingSetupMessage == null)
                    return;
                msg = mOngoingSetupMessage;
                mOngoingSetupMessage = null;
            }

            /*Result info set to null to indicate default bearer*/
            String[] info = new String[2];
            info[0] = null;
            info[1] = null;

            AsyncResult.forMessage(msg, info, null);
            msg.sendToTarget();
        }

        private void onStillConnecting() {
            CatLog.d(this, "onStillConnecting");
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (!intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                CatLog.d(this, "Received unexpected broadcast: " + intent.getAction());
                return;
            }

            boolean noConnection = intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
            NetworkInfo netInfo = (NetworkInfo)intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            NetworkInfo otherNetInfo = (NetworkInfo)intent.getParcelableExtra(ConnectivityManager.EXTRA_OTHER_NETWORK_INFO);

            if (!noConnection) {
                onConnected();
            } else if (otherNetInfo != null) { /* Failed to connect but retrying with a different network*/
                onStillConnecting();
            } else {
                onDisconnected();
            }
        }
    }
}
