package com.github.openrealgps.lite;

import cn.imaq.autumn.http.protocol.AutumnHttpRequest;
import cn.imaq.autumn.http.protocol.AutumnHttpResponse;
import cn.imaq.autumn.http.server.AutumnHttpServer;
import cn.imaq.autumn.http.server.HttpServerOptions;
import cn.imaq.autumn.http.server.protocol.AutumnHttpHandler;
import org.json.JSONArray;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class LocalServer implements AutumnHttpHandler {
    public static final int HTTP_PORT = 9767;
    public static final int BROADCAST_PORT = 9768;
    public static final String BROADCAST_ADDR = "127.255.255.255";
    public static final int MAX_BODY = 10240;

    private final AutumnHttpServer server;
    private DatagramSocket broadcastSocket;
    private InetAddress broadcastAddress;

    public LocalServer(int port) {
        this.server = new AutumnHttpServer(HttpServerOptions.builder()
                .host("127.0.0.1")
                .port(port)
                .handler(this)
                .workerCount(1)
                .executor(new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(10), new ThreadPoolExecutor.AbortPolicy()))
                .maxBodyBytes(MAX_BODY)
                .build());
    }

    public void start() throws IOException {
        this.server.start();
        this.broadcastSocket = new DatagramSocket();
        this.broadcastAddress = InetAddress.getByName(BROADCAST_ADDR);
    }

    @Override
    public AutumnHttpResponse handle(AutumnHttpRequest request) {
        try {
            if ("POST".equals(request.getMethod())) {
                JSONArray root = new JSONArray(new String(request.getBody()));
                if (root.length() >= 3) {
                    int status = 0;
                    Object result = null;
                    switch (root.optString(0)) {
                        case "ping":
                            result = 0;
                            break;
                        case "updateLocation":
                        case "updateSatellites":
                            if (this.broadcastSocket != null) {
                                this.broadcastSocket.send(new DatagramPacket(request.getBody(), request.getBody().length, this.broadcastAddress, BROADCAST_PORT));
                            } else {
                                status = 1;
                                result = "Socket connection failed";
                            }
                            break;
                        case "getRealLocation":
                        case "setDataSource":
                        case "setHardwareEnabled":
                            status = 1;
                            result = "Unsupported in OpenRealGPS Lite";
                            break;
                        default:
                            status = 1;
                            result = "Unknown method";
                            break;
                    }
                    return AutumnHttpResponse.builder()
                            .status(200)
                            .contentType("application/json")
                            .body(new JSONArray().put(status).put(result).toString().getBytes())
                            .build();
                }
            }

            return AutumnHttpResponse.builder()
                    .status(418)
                    .contentType("text/html")
                    .body(("<html><head><title>OpenRealGPS Lite</title></head>" +
                            "<body><h1>This is probably not the site you are looking for.</h1></body></html>").getBytes())
                    .build();
        } catch (Exception e) {
            try {
                return AutumnHttpResponse.builder()
                        .status(500)
                        .contentType("application/json")
                        .body(new JSONArray().put(1).put(String.valueOf(e)).toString().getBytes())
                        .build();
            } catch (Exception e1) {
                return AutumnHttpResponse.builder()
                        .status(500)
                        .contentType("text/plain")
                        .body(String.valueOf(e1).getBytes())
                        .build();
            }
        }
    }
}
