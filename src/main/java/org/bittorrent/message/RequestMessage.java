package org.bittorrent.message;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

public class RequestMessage implements Serializable {

    private final String senderAddress;
    private final RequestType requestType;
    private final Map<DataType, Object> data;

    public RequestMessage(String senderAddress, RequestType requestType) {
        this.senderAddress = senderAddress;
        this.requestType = requestType;
        this.data = new HashMap<>();
    }

    public String getSenderAddress() {
        return senderAddress;
    }

    public RequestType getRequestType() {
        return requestType;
    }

    public Map<DataType, Object> getData() {
        return data;
    }
}