package org.openhab.binding.openocean.transceiver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import org.openhab.binding.openocean.internal.OpenOceanException;
import org.openhab.binding.openocean.messages.Response;
import org.openhab.binding.openocean.transceiver.ESP3Packet.ESPPacketType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class OpenOceanTransceiver {

    // Thread management
    private Future<?> readingTask;
    protected Object syncObj;

    protected String path;

    private Logger logger = LoggerFactory.getLogger(OpenOceanTransceiver.class);

    class Request {
        ESP3Packet RequestPacket;

        Response ResponsePacket;
        ResponseListener ResponseListener;
    }

    private Queue<Request> requests;
    Request currentRequest = null;
    private Future<?> sendingTask;

    protected List<ESP3PacketListener> listeners;

    // Input and output streams, must be created by transceiver implementations
    protected InputStream inputStream;
    protected OutputStream outputStream;

    enum ReadingState {
        WaitingForSyncByte,
        ReadingHeader,
        ReadingData
    }

    public OpenOceanTransceiver(String path) {
        this.path = path;
        this.syncObj = new Object();

        listeners = new LinkedList<ESP3PacketListener>();
        requests = new LinkedList<Request>();
    }

    public abstract void Initialize() throws OpenOceanException;

    public void StartSendingAndReading(ScheduledExecutorService scheduler) {
        if (readingTask == null || readingTask.isCancelled()) {
            readingTask = scheduler.submit(new Runnable() {

                @Override
                public void run() {
                    receivePackets();
                }

            });
        }

        if (sendingTask == null || sendingTask.isCancelled()) {
            sendingTask = scheduler.submit(new Runnable() {

                @Override
                public void run() {
                    sendPackets();
                }
            });
        }
    }

    public void ShutDown() {
        if (readingTask != null && !readingTask.isCancelled()) {
            readingTask.cancel(true);
        }

        if (sendingTask != null && !sendingTask.isCancelled()) {
            sendingTask.cancel(true);
        }
    }

    private void receivePackets() {

        byte[] readingBuffer = new byte[Helper.ENOCEAN_MAX_DATA];
        int bytesRead = -1;
        int _byte;

        int[] dataBuffer = new int[Helper.ENOCEAN_MAX_DATA];
        ReadingState state = ReadingState.WaitingForSyncByte;
        int currentPosition = 0;
        int dataLength = -1;
        int optionalLength = -1;
        int packetType = -1;

        logger.debug("Listening on port: " + path);

        while (!readingTask.isCancelled()) {
            try {
                synchronized (this.syncObj) {
                    if (this.inputStream.available() == 0) {
                        this.syncObj.wait();
                    }
                }
                if (readingTask.isCancelled()) {
                    return;
                }

                bytesRead = this.inputStream.read(readingBuffer, 0, inputStream.available());
                if (bytesRead == -1) {
                    throw new IOException("could not read from inputstream");
                }

                for (int p = 0; p < bytesRead; p++) {
                    _byte = (readingBuffer[p] & 0xff);

                    switch (state) {
                        case WaitingForSyncByte:
                            if (_byte == Helper.ENOCEAN_SYNC_BYTE) {
                                state = ReadingState.ReadingHeader;
                                logger.debug("Received Sync Byte");
                            }
                            break;
                        case ReadingHeader:
                            if (currentPosition == Helper.ENOCEAN_HEADER_LENGTH) {
                                if (Helper.checkCRC8(dataBuffer, Helper.ENOCEAN_HEADER_LENGTH, _byte)
                                        && ((dataBuffer[0] << 8) | dataBuffer[1]) + dataBuffer[2] > 0) {

                                    state = ReadingState.ReadingData;

                                    dataLength = ((dataBuffer[0] << 8) | dataBuffer[1]);
                                    optionalLength = dataBuffer[2];
                                    packetType = dataBuffer[3];
                                    currentPosition = 0;

                                    logger.debug("Received header, data length " + dataLength + " optional length "
                                            + optionalLength + " packet type " + packetType);
                                } else {
                                    // check if we find a sync byte in current buffer
                                    int copyFrom = -1;
                                    for (int i = 0; i < Helper.ENOCEAN_HEADER_LENGTH; i++) {
                                        if (dataBuffer[i] == Helper.ENOCEAN_SYNC_BYTE) {
                                            copyFrom = i + 1;
                                            break;
                                        }
                                    }

                                    if (copyFrom != -1) {
                                        System.arraycopy(dataBuffer, copyFrom, dataBuffer, 0,
                                                Helper.ENOCEAN_HEADER_LENGTH - copyFrom);
                                        state = ReadingState.ReadingHeader;
                                        currentPosition = Helper.ENOCEAN_HEADER_LENGTH - copyFrom;
                                        dataBuffer[currentPosition++] = _byte;
                                    } else {
                                        currentPosition = 0;
                                        state = _byte == Helper.ENOCEAN_SYNC_BYTE ? ReadingState.ReadingHeader
                                                : ReadingState.WaitingForSyncByte;
                                    }
                                    logger.debug("CrC8 header check not successful");
                                }
                            } else {
                                dataBuffer[currentPosition++] = _byte;
                            }
                            break;
                        case ReadingData:
                            if (currentPosition == dataLength + optionalLength) {
                                if (Helper.checkCRC8(dataBuffer, dataLength + optionalLength, _byte)) {
                                    state = ReadingState.WaitingForSyncByte;
                                    ESP3Packet packet = new ESP3Packet(dataLength, optionalLength, packetType,
                                            dataBuffer);

                                    if (packet.getPacketType() == ESPPacketType.RESPONSE && currentRequest != null) {
                                        logger.debug("publish response");
                                        synchronized (currentRequest) {
                                            currentRequest.ResponsePacket = new Response(packet);
                                            currentRequest.notify();
                                        }
                                    } else {
                                        logger.debug("publish event");

                                        int[] d = new int[dataLength + optionalLength];
                                        System.arraycopy(dataBuffer, 0, d, 0, d.length);
                                        logger.debug(Helper.bytesToHexString(d));

                                        informListeners(
                                                new ESP3Packet(dataLength, optionalLength, packetType, dataBuffer));
                                    }

                                } else {
                                    state = _byte == Helper.ENOCEAN_SYNC_BYTE ? ReadingState.ReadingHeader
                                            : ReadingState.WaitingForSyncByte;
                                    logger.debug("esp packet malformed");
                                }

                                currentPosition = 0;
                                dataLength = optionalLength = packetType = -1;
                            } else {
                                dataBuffer[currentPosition++] = _byte;
                            }
                            break;
                        default:
                            break;
                    }
                }
            } catch (IOException ioexception) {
                ioexception.printStackTrace();
            } catch (InterruptedException interruptedexception) {
                logger.debug("receiving packets interrupted");
            }
        }

        logger.debug("finished listening");
    }

    public void sendESP3Packet(ESP3Packet packet, ResponseListener responseCallback) {

        synchronized (requests) {
            logger.debug("new request arrived");
            Request r = new Request();
            r.RequestPacket = packet;
            r.ResponseListener = responseCallback;

            requests.offer(r);
            requests.notify();
        }
    }

    protected void sendPackets() {

        logger.debug("start sending packets");

        while (!sendingTask.isCancelled()) {
            try {

                synchronized (requests) {
                    if (requests.isEmpty()) {
                        logger.debug("awaiting packets to send");
                        requests.wait();
                    }
                    currentRequest = requests.poll();
                }

                if (sendingTask.isCancelled()) {
                    return;
                }

                if (currentRequest != null) {
                    synchronized (currentRequest) {

                        logger.debug("sending request");

                        try {
                            outputStream.write(currentRequest.RequestPacket.serialize());
                            outputStream.flush();
                        } catch (IOException e) {
                            logger.debug("exception while sending data " + e.getMessage());
                        }

                        logger.debug("awaiting response");
                        currentRequest.wait(500);

                        if (currentRequest.ResponseListener != null) {
                            if (currentRequest.ResponsePacket == null) {
                                logger.debug("response timeout");
                                currentRequest.ResponseListener.responseTimeOut();
                            } else {
                                logger.debug("response received");
                                currentRequest.ResponseListener.responseReceived(currentRequest.ResponsePacket);
                            }
                        } else {
                            logger.debug("request without listener");
                        }

                        logger.debug("handeled request");
                        currentRequest = null;
                    }
                }
            } catch (InterruptedException e) {
                logger.debug("sending packets interrupted");
            }
        }
    }

    protected void informListeners(ESP3Packet packet) {
        for (ESP3PacketListener listener : listeners) {
            listener.espPacketReceived(packet);
        }
    }

    public void addPacketListener(ESP3PacketListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
}