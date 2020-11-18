package com.github.openrealgps.lite;

import android.os.Handler;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

@Slf4j
public class LocalBroadcastReceiver implements Runnable {
    private final DatagramSocket broadcastReceiver;
    private final Handler handler;
    private final byte[] buf;

    public LocalBroadcastReceiver(Handler handler) throws Exception {
        this.broadcastReceiver = new DatagramSocket(null);
        this.broadcastReceiver.setReuseAddress(true);
        this.broadcastReceiver.setSoTimeout(0);
        this.broadcastReceiver.bind(new InetSocketAddress(LocalServer.BROADCAST_PORT));
        this.handler = handler;
        this.buf = new byte[LocalServer.MAX_BODY];
    }

    @Override
    public void run() {
        while (!broadcastReceiver.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            try {
                broadcastReceiver.receive(packet);
            } catch (IOException e) {
                log.warn("Failed to receive broadcast packet: {}", String.valueOf(e));
                break;
            }

            try {
                JSONArray root = new JSONArray(new String(packet.getData(), packet.getOffset(), packet.getLength()));
                String updateType = root.optString(0);
                switch (updateType) {
                    case "updateLocation":
                        LocationUpdater.getInstance().update(root.getJSONArray(2).getJSONObject(0));
                        break;
                    case "updateSatellites":
                        SatelliteUpdater.getInstance().update(root.getJSONArray(2).getJSONArray(0), this.handler);
                        break;
                    default:
                        log.warn("Unknown update type: {}", updateType);
                        break;
                }
            } catch (Exception e) {
                log.warn("Failed to process broadcast data: {}", String.valueOf(e));
            }
        }
    }
}
