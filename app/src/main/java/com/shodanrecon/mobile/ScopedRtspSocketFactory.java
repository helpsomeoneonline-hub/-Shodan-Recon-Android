package com.shodanrecon.mobile;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

import javax.net.SocketFactory;

/** Bound RTSP connection creation to a registered service and avoid OS-length connect stalls. */
final class ScopedRtspSocketFactory extends SocketFactory {
    private final String host;
    private final int port;
    ScopedRtspSocketFactory(String host, int port) { this.host = host; this.port = port; }
    @Override public Socket createSocket(String host, int port) throws IOException {
        if (!this.host.equalsIgnoreCase(host) || this.port != port)
            throw new IOException("RTSP redirect outside the configured camera service is blocked");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 12000);
            socket.setSoTimeout(15000);
            return socket;
        } catch (IOException e) { socket.close(); throw e; }
    }
    @Override public Socket createSocket() throws IOException { throw new IOException("Use the configured RTSP endpoint"); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        throw new IOException("Custom RTSP local endpoints are unsupported");
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        throw new IOException("Use the configured RTSP host");
    }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        throw new IOException("Use the configured RTSP host");
    }
}
