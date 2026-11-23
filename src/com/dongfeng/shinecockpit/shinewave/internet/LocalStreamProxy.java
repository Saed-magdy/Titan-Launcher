package com.dongfeng.shinecockpit.shinewave.internet;

import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Localhost HTTP stream proxy for live Internet Radio playback on Android 8.1 (Stagefright).
 * Bridges modern TLS / expired system clock constraints and cross-protocol (HTTP <-> HTTPS)
 * 301/302 redirects cleanly into plain localhost HTTP so native MediaPlayer never fails.
 */
public final class LocalStreamProxy {
    private static final String TAG = "SWStreamProxy";
    private static volatile LocalStreamProxy instance;
    private static volatile SSLSocketFactory trustAllFactory;

    private static final HostnameVerifier TRUST_ALL_HOST = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private ServerSocket serverSocket;
    private int listenPort = -1;
    private final ExecutorService workers = Executors.newCachedThreadPool();

    private LocalStreamProxy() {}

    public static LocalStreamProxy getInstance() {
        if (instance == null) {
            synchronized (LocalStreamProxy.class) {
                if (instance == null) {
                    instance = new LocalStreamProxy();
                }
            }
        }
        return instance;
    }

    public static SSLSocketFactory getTrustAllSslSocketFactory() {
        if (trustAllFactory == null) {
            synchronized (LocalStreamProxy.class) {
                if (trustAllFactory == null) {
                    try {
                        TrustManager[] trustAll = new TrustManager[]{
                            new X509TrustManager() {
                                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                            }
                        };
                        SSLContext sc = SSLContext.getInstance("TLS");
                        sc.init(null, trustAll, new SecureRandom());
                        trustAllFactory = sc.getSocketFactory();
                    } catch (Throwable t) {
                        Log.w(TAG, "TrustAll SSL init warning: " + t.getMessage());
                    }
                }
            }
        }
        return trustAllFactory;
    }

    public static HttpURLConnection openFollowingRedirects(String urlStr, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        String currentUrl = urlStr;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            if (conn instanceof HttpsURLConnection) {
                SSLSocketFactory sf = getTrustAllSslSocketFactory();
                if (sf != null) {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(sf);
                }
                ((HttpsURLConnection) conn).setHostnameVerifier(TRUST_ALL_HOST);
            }
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent", "TitanShineWave/2.1 Android");
            conn.setRequestProperty("Icy-MetaData", "0");
            conn.setRequestProperty("Accept", "*/*");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.trim().isEmpty()) {
                    throw new IOException("Redirect without Location from " + currentUrl);
                }
                currentUrl = new URL(new URL(currentUrl), loc.trim()).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("Too many redirects from " + urlStr);
    }

    public synchronized String getProxyStreamUrl(String upstreamUrl) {
        if (upstreamUrl == null || upstreamUrl.trim().isEmpty()) return "";
        try {
            ensureStarted();
            if (listenPort > 0) {
                return "http://127.0.0.1:" + listenPort + "/live.mp3?u="
                        + URLEncoder.encode(upstreamUrl.trim(), "UTF-8");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Proxy start failed, falling back to direct URL: " + t.getMessage());
        }
        return upstreamUrl;
    }

    private synchronized void ensureStarted() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed() && listenPort > 0) {
            return;
        }
        serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        listenPort = serverSocket.getLocalPort();
        Thread acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        final Socket client = serverSocket.accept();
                        workers.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleClient(client);
                            }
                        });
                    } catch (IOException e) {
                        if (serverSocket == null || serverSocket.isClosed()) break;
                    }
                }
            }
        }, "SW-LocalStreamProxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Started LocalStreamProxy on 127.0.0.1:" + listenPort);
    }

    private void handleClient(Socket client) {
        HttpURLConnection upstream = null;
        try {
            client.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String reqLine = reader.readLine();
            if (reqLine == null || reqLine.isEmpty()) return;

            // Drain HTTP headers
            String header;
            while ((header = reader.readLine()) != null && !header.isEmpty()) {
                // ignore
            }

            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            String targetUrl = extractQueryParam(path, "u");
            if (targetUrl == null || targetUrl.isEmpty()) {
                sendError(client.getOutputStream(), 400);
                return;
            }

            upstream = openFollowingRedirects(targetUrl, 7000, 15000);
            int status = upstream.getResponseCode();
            if (status < 200 || status >= 300) {
                Log.w(TAG, "Upstream returned HTTP " + status + " for " + targetUrl);
                sendError(client.getOutputStream(), 502);
                return;
            }

            String contentType = upstream.getContentType();
            String lowerCt = contentType != null ? contentType.toLowerCase() : "";
            String lowerUrl = targetUrl.toLowerCase();

            // Resolve M3U/PLS playlists automatically to the first actual stream URL
            if (lowerCt.contains("mpegurl") || lowerCt.contains("scpls")
                    || lowerUrl.endsWith(".m3u") || lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".pls")) {
                String resolved = resolveFirstPlaylistStream(upstream.getInputStream());
                upstream.disconnect();
                upstream = null;
                if (resolved == null) {
                    sendError(client.getOutputStream(), 502);
                    return;
                }
                upstream = openFollowingRedirects(resolved, 7000, 15000);
                status = upstream.getResponseCode();
                if (status < 200 || status >= 300) {
                    sendError(client.getOutputStream(), 502);
                    return;
                }
                contentType = upstream.getContentType();
            }

            if (contentType == null || contentType.trim().isEmpty() || contentType.contains("octet-stream")) {
                contentType = "audio/mpeg";
            }

            OutputStream out = client.getOutputStream();
            String respHdr = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(respHdr.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            if ("HEAD".equalsIgnoreCase(method)) return;

            InputStream in = upstream.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } catch (Throwable ignored) {
            // Normal disconnect when MediaPlayer stops or switches stations
        } finally {
            if (upstream != null) {
                try { upstream.disconnect(); } catch (Throwable ignored) {}
            }
            try { client.close(); } catch (Throwable ignored) {}
        }
    }

    private static String resolveFirstPlaylistStream(InputStream in) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = br.readLine()) != null && lines++ < 40) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.startsWith("File1=") || t.startsWith("file1=")) {
                    t = t.substring(6).trim();
                }
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    return t;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractQueryParam(String path, String name) {
        int q = path.indexOf('?');
        if (q < 0) return null;
        String query = path.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && name.equals(pair.substring(0, eq))) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void sendError(OutputStream out, int code) {
        try {
            String msg = "HTTP/1.1 " + code + " Error\r\nConnection: close\r\nContent-Length: 0\r\n\r\n";
            out.write(msg.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (Throwable ignored) {}
    }

    public synchronized void shutdown() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
            listenPort = -1;
        } catch (Throwable ignored) {}
    }
}
