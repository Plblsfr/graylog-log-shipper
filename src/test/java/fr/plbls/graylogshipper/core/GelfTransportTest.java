package fr.plbls.graylogshipper.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelfTransportTest {

    private static Map<String, Object> msg(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", "1.1");
        m.put("host", "test");
        m.put("short_message", text);
        return m;
    }

    private static GelfConfig cfg(GelfConfig.Protocol p, int port) {
        return new GelfConfig(p, "127.0.0.1", port, "/gelf", "test", Map.of(), true, true, false, "_", 10000, 300);
    }

    @Test
    void tcpFramesAreNulTerminated() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<String> frames = new CopyOnWriteArrayList<>();
            CountDownLatch got = new CountDownLatch(2);
            Thread t = new Thread(() -> {
                try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                    ByteArrayOutputStream cur = new ByteArrayOutputStream();
                    int b;
                    while ((b = in.read()) >= 0) {
                        if (b == 0) {
                            frames.add(cur.toString(StandardCharsets.UTF_8));
                            cur.reset();
                            got.countDown();
                        } else {
                            cur.write(b);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            t.start();
            GelfDispatcher d = new GelfDispatcher(cfg(GelfConfig.Protocol.TCP, server.getLocalPort()), 100, e -> { }, () -> { });
            d.start();
            d.offer(msg("one é"));
            d.offer(msg("two\nlines"));
            assertTrue(got.await(5, TimeUnit.SECONDS));
            d.shutdown(1000);
            assertEquals("{\"version\":\"1.1\",\"host\":\"test\",\"short_message\":\"one é\"}", frames.get(0));
            assertTrue(frames.get(1).contains("two\\nlines"));
            assertEquals(2, d.stats().sent());
        }
    }

    @Test
    void udpSmallAndChunked() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(3000);
            String err = GelfDispatcher.sendOnce(cfg(GelfConfig.Protocol.UDP, server.getLocalPort()), msg("small"));
            assertNull(err);
            DatagramPacket p = new DatagramPacket(new byte[9000], 9000);
            server.receive(p);
            assertEquals("{\"version\":\"1.1\",\"host\":\"test\",\"short_message\":\"small\"}",
                    new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));

            // incompressible payload big enough to need several chunks
            StringBuilder sb = new StringBuilder();
            java.util.Random r = new java.util.Random(1);
            for (int i = 0; i < 30000; i++) {
                sb.append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".charAt(r.nextInt(62)));
            }
            byte[][] chunks = GelfTransport.Udp.datagrams(GelfTransport.encode(msg(sb.toString())));
            assertTrue(chunks.length > 1);
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (int i = 0; i < chunks.length; i++) {
                assertEquals(0x1e, chunks[i][0]);
                assertEquals(0x0f, chunks[i][1]);
                assertEquals(i, chunks[i][10]);
                assertEquals(chunks.length, chunks[i][11]);
                assertTrue(chunks[i].length <= 8192);
                all.write(chunks[i], 12, chunks[i].length - 12);
            }
            try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(all.toByteArray()))) {
                String json = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(json.contains(sb.substring(0, 50)));
            }
        }
    }

    @Test
    void httpPostsJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> bodies = new ArrayList<>();
        server.createContext("/gelf", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            assertNull(GelfDispatcher.sendOnce(cfg(GelfConfig.Protocol.HTTP, port), msg("over http")));
            assertTrue(bodies.get(0).contains("over http"));
            GelfConfig wrongPath = new GelfConfig(GelfConfig.Protocol.HTTP, "127.0.0.1", port, "nope", "t",
                    Map.of(), true, true, false, "_", 0, 10);
            String err = GelfDispatcher.sendOnce(wrongPath, msg("x"));
            assertNotNull(err);
            assertTrue(err.contains("404"), err);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dispatcherReportsErrorThenRecovers() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch recovered = new CountDownLatch(1);
        GelfDispatcher d = new GelfDispatcher(cfg(GelfConfig.Protocol.TCP, port), 100, errors::add, recovered::countDown);
        d.start();
        d.offer(msg("lost"));
        long deadline = System.currentTimeMillis() + 3000;
        while (errors.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("TCP 127.0.0.1:" + port), errors.get(0));

        try (ServerSocket server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            CountDownLatch got = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                try (Socket s = server.accept(); InputStream in = s.getInputStream()) {
                    while (in.read() > 0) {
                        // read until NUL
                    }
                    got.countDown();
                } catch (Exception ignored) {
                }
            });
            t.start();
            Thread.sleep(GelfDispatcher.RETRY_MILLIS + 100);
            d.offer(msg("back"));
            assertTrue(got.await(5, TimeUnit.SECONDS));
            assertTrue(recovered.await(5, TimeUnit.SECONDS));
            assertEquals(1, errors.size());
        } finally {
            d.shutdown(500);
        }
    }
}
