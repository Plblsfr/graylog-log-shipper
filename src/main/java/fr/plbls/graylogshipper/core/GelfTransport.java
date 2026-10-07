package fr.plbls.graylogshipper.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Sends encoded GELF messages to a Graylog GELF input. */
public interface GelfTransport extends Closeable {

    void send(Map<String, Object> message) throws IOException;

    @Override
    void close();

    static GelfTransport create(GelfConfig cfg) {
        return switch (cfg.protocol()) {
            case TCP -> new Tcp(cfg.host(), cfg.port());
            case UDP -> new Udp(cfg.host(), cfg.port());
            case HTTP -> new Http(cfg.httpUrl());
        };
    }

    static byte[] encode(Map<String, Object> message) {
        return Json.write(message).getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ TCP

    /** GELF TCP: uncompressed JSON frames terminated by a NUL byte. Reconnects lazily. */
    final class Tcp implements GelfTransport {
        private static final int CONNECT_TIMEOUT_MS = 3000;
        private final String host;
        private final int port;
        private Socket socket;
        private OutputStream out;

        public Tcp(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public void send(Map<String, Object> message) throws IOException {
            byte[] payload = encode(message);
            try {
                write(payload);
            } catch (IOException first) {
                close(); // stale connection (Graylog restarted…): reconnect once
                write(payload);
            }
        }

        private void write(byte[] payload) throws IOException {
            if (socket == null || socket.isClosed()) {
                Socket s = new Socket();
                s.setKeepAlive(true);
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(5000);
                socket = s;
                out = new java.io.BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            }
            out.write(payload);
            out.write(0);
            out.flush();
        }

        @Override
        public void close() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
            socket = null;
            out = null;
        }
    }

    // ------------------------------------------------------------------ UDP

    /** GELF UDP: gzip when large, chunked (max 128 chunks) when still above one datagram. */
    final class Udp implements GelfTransport {
        static final int MAX_CHUNK_PAYLOAD = 8192 - 12;
        static final int MAX_CHUNKS = 128;
        private static final SecureRandom RANDOM = new SecureRandom();

        private final String host;
        private final int port;
        private DatagramSocket socket;
        private InetAddress address;

        public Udp(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public void send(Map<String, Object> message) throws IOException {
            if (socket == null) {
                address = InetAddress.getByName(host);
                socket = new DatagramSocket();
            }
            for (byte[] datagram : datagrams(encode(message))) {
                socket.send(new DatagramPacket(datagram, datagram.length, address, port));
            }
        }

        static byte[][] datagrams(byte[] payload) throws IOException {
            byte[] data = payload;
            if (data.length > 1024) {
                data = gzip(data);
            }
            if (data.length <= MAX_CHUNK_PAYLOAD) {
                return new byte[][]{data};
            }
            int count = (data.length + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD;
            if (count > MAX_CHUNKS) {
                throw new IOException("Message too large for GELF UDP (" + data.length + " bytes compressed)");
            }
            byte[] id = new byte[8];
            RANDOM.nextBytes(id);
            byte[][] chunks = new byte[count][];
            for (int i = 0; i < count; i++) {
                int from = i * MAX_CHUNK_PAYLOAD;
                int len = Math.min(MAX_CHUNK_PAYLOAD, data.length - from);
                byte[] chunk = new byte[12 + len];
                chunk[0] = 0x1e;
                chunk[1] = 0x0f;
                System.arraycopy(id, 0, chunk, 2, 8);
                chunk[10] = (byte) i;
                chunk[11] = (byte) count;
                System.arraycopy(data, from, chunk, 12, len);
                chunks[i] = chunk;
            }
            return chunks;
        }

        private static byte[] gzip(byte[] data) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 4 + 64);
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(data);
            }
            return bos.toByteArray();
        }

        @Override
        public void close() {
            if (socket != null) {
                socket.close();
            }
            socket = null;
        }
    }

    // ------------------------------------------------------------------ HTTP

    /** GELF HTTP input: one POST per message, Graylog answers 202 Accepted. */
    final class Http implements GelfTransport {
        private final URI uri;
        private final HttpClient client;

        public Http(String url) {
            this.uri = URI.create(url);
            this.client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(3))
                    .proxy(java.net.ProxySelector.of(null)) // local Graylog: never go through the IDE proxy
                    .build();
        }

        @Override
        public void send(Map<String, Object> message) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(encode(message)))
                    .build();
            HttpResponse<Void> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from " + uri);
            }
        }

        @Override
        public void close() {
            // HttpClient has nothing to release explicitly on Java 17/21
        }
    }
}
