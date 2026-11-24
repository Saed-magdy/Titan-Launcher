package com.dongfeng.shinecockpit.shinewave.internet;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Localhost HTTP relay proxy for Android MediaPlayer internet radio playback.
 * Keeps a single live upstream HTTP/TLS stream and 128 KB RAM ring buffer open
 * across Stagefright's multiple prepare-time HTTP probe connections so stations
 * start fast without re-handshaking or expiring short-lived CDN tokens.
 */
public final class LocalStreamProxy {

    private static final String TAG = "LocalStreamProxy";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 11) ShineCockpit/2.1";
    private static final int MAX_REDIRECTS = 6;
    private static final int RING_BUFFER_BYTES = 131072; // 128 KB circular buffer
    private static final int INITIAL_BURST_BYTES = 65536; // Up to 64 KB burst for new sockets
    private static final int CHUNK_SIZE = 16384;
    private static final long READY_WAIT_TIMEOUT_MS = 12000L;
    private static final long SESSION_IDLE_MS = 15000L;
    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 5000;
    private static final int UPSTREAM_READ_TIMEOUT_MS = 10000;

    private static volatile LocalStreamProxy instance;
    private static volatile SSLSocketFactory trustAllSslFactory;
    private static final HostnameVerifier TRUST_ALL_HOSTNAME = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return true;
        }
    };

    private final ConcurrentHashMap<String, String> resolvedUrlCache = new ConcurrentHashMap<>();
    private ExecutorService clientExecutor;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private int listenPort = -1;
    private volatile boolean running = false;
    private UpstreamSession activeSession;

    private LocalStreamProxy() {
    }

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
        if (trustAllSslFactory == null) {
            synchronized (LocalStreamProxy.class) {
                if (trustAllSslFactory == null) {
                    try {
                        TrustManager[] trustAll = new TrustManager[]{
                                new X509TrustManager() {
                                    @Override
                                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                                    }

                                    @Override
                                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                                    }

                                    @Override
                                    public X509Certificate[] getAcceptedIssuers() {
                                        return new X509Certificate[0];
                                    }
                                }
                        };
                        SSLContext sc = SSLContext.getInstance("TLS");
                        sc.init(null, trustAll, new SecureRandom());
                        trustAllSslFactory = sc.getSocketFactory();
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to init trustAllSslFactory", t);
                    }
                }
            }
        }
        return trustAllSslFactory;
    }

    /**
     * Opens a normal web address (used for the radio station directory).
     * The security certificate is ALWAYS checked here: if someone on the same Wi-Fi
     * pretends to be the station directory, the connection is refused instead of
     * accepting their fake station list.
     */
    public static HttpURLConnection openFollowingRedirects(
            String urlStr, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        return openFollowingRedirectsWithFinalUrl(urlStr, connectTimeoutMs, readTimeoutMs, false, false).conn;
    }

    /**
     * Follows up to MAX_REDIRECTS "moved to another address" answers.
     *
     * About the security check ("certificate") on https addresses, in plain words:
     * - Every connection first tries the normal, safe check.
     * - Only when allowInsecureFallback is true (the radio AUDIO stream itself) and the
     *   safe check fails, the same address is tried once more without the check.
     *   Some small radio stations use expired or home-made certificates, and this keeps
     *   them playing. The worst a fake hotspot could do there is play different audio.
     * - The station directory never uses the fallback (see openFollowingRedirects).
     */
    private static RedirectResult openFollowingRedirectsWithFinalUrl(
            String urlStr, int connectTimeoutMs, int readTimeoutMs, boolean disableIcyMeta,
            boolean allowInsecureFallback) throws IOException {
        String current = urlStr;
        IOException lastError = null;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            URL url = new URL(current);
            HttpURLConnection conn = openOnce(url, connectTimeoutMs, readTimeoutMs, disableIcyMeta, false);
            try {
                int code;
                try {
                    code = conn.getResponseCode();
                } catch (SSLException safeCheckFailed) {
                    if (!allowInsecureFallback || !(conn instanceof HttpsURLConnection)) {
                        throw safeCheckFailed;
                    }
                    conn.disconnect();
                    Log.w(TAG, "Certificate check failed for audio stream " + url.getHost()
                            + " (" + safeCheckFailed.getMessage() + "); retrying this stream without the check");
                    conn = openOnce(url, connectTimeoutMs, readTimeoutMs, disableIcyMeta, true);
                    code = conn.getResponseCode();
                }
                if (code == HttpURLConnection.HTTP_MOVED_PERM
                        || code == HttpURLConnection.HTTP_MOVED_TEMP
                        || code == HttpURLConnection.HTTP_SEE_OTHER
                        || code == 307
                        || code == 308) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null || location.trim().isEmpty()) {
                        throw new IOException("Redirect without Location from " + current);
                    }
                    current = new URL(url, location.trim()).toString();
                    continue;
                }
                return new RedirectResult(conn, current, code);
            } catch (IOException e) {
                conn.disconnect();
                lastError = e;
                break;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IOException("Too many redirects for " + urlStr);
    }

    /** Prepares one connection. skipCertificateCheck = true only for the audio-stream retry above. */
    private static HttpURLConnection openOnce(URL url, int connectTimeoutMs, int readTimeoutMs,
            boolean disableIcyMeta, boolean skipCertificateCheck) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Connection", "close");
        if (disableIcyMeta) {
            conn.setRequestProperty("Icy-MetaData", "0");
        }
        if (skipCertificateCheck && conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            SSLSocketFactory sf = getTrustAllSslSocketFactory();
            if (sf != null) {
                https.setSSLSocketFactory(sf);
            }
            https.setHostnameVerifier(TRUST_ALL_HOSTNAME);
        }
        return conn;
    }

    public synchronized String getProxyStreamUrl(String upstreamUrl) {
        if (upstreamUrl == null) {
            return "";
        }
        String cleanUrl = upstreamUrl.trim();
        if (cleanUrl.isEmpty()) {
            return "";
        }
        if (cleanUrl.startsWith("http://127.0.0.1:")) {
            return cleanUrl;
        }
        try {
            ensureStarted();
            // Pre-connect upstream immediately in parallel with MediaPlayer native setup.
            getOrStartSessionLocked(cleanUrl);
            return "http://127.0.0.1:" + listenPort + "/live.mp3?u=" + URLEncoder.encode(cleanUrl, "UTF-8");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start LocalStreamProxy; falling back to upstream URL", t);
            return cleanUrl;
        }
    }

    public synchronized void stopActiveSession() {
        if (activeSession != null) {
            activeSession.close();
            activeSession = null;
        }
    }

    public synchronized void shutdown() {
        running = false;
        stopActiveSession();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Throwable ignored) {
            }
            serverSocket = null;
        }
        listenPort = -1;
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
            clientExecutor = null;
        }
        resolvedUrlCache.clear();
    }

    private void ensureStarted() throws IOException {
        if (clientExecutor == null || clientExecutor.isShutdown()) {
            clientExecutor = Executors.newCachedThreadPool(new ThreadFactory() {
                private final AtomicInteger seq = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "SW-ProxyClient-" + seq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        if (running && serverSocket != null && !serverSocket.isClosed() && listenPort > 0) {
            return;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Throwable ignored) {
            }
        }
        serverSocket = new ServerSocket(0, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        listenPort = serverSocket.getLocalPort();
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "SW-LocalStreamProxy");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Started LocalStreamProxy on 127.0.0.1:" + listenPort);
    }

    private void acceptLoop() {
        while (running) {
            try {
                ServerSocket ss = serverSocket;
                if (ss == null || ss.isClosed()) {
                    break;
                }
                final Socket client = ss.accept();
                ExecutorService exec = clientExecutor;
                if (exec != null && !exec.isShutdown()) {
                    exec.execute(new Runnable() {
                        @Override
                        public void run() {
                            handleLocalClient(client);
                        }
                    });
                } else {
                    client.close();
                }
            } catch (Throwable t) {
                if (running) {
                    Log.w(TAG, "Proxy accept error", t);
                }
            }
        }
    }

    private synchronized UpstreamSession getOrStartSessionLocked(String rawUrl) {
        if (activeSession != null && activeSession.isReusableFor(rawUrl)) {
            return activeSession;
        }
        if (activeSession != null) {
            activeSession.close();
        }
        UpstreamSession session = new UpstreamSession(rawUrl);
        activeSession = session;
        session.start();
        return session;
    }

    private void handleLocalClient(Socket client) {
        UpstreamSession session = null;
        boolean attached = false;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), "US-ASCII"));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            // Drain incoming request headers so socket closes cleanly without RST.
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // ignore
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0].trim() : "GET";
            String pathAndQuery = parts.length > 1 ? parts[1].trim() : "";
            String targetUrl = extractQueryParam(pathAndQuery, "u");
            OutputStream out = client.getOutputStream();
            if (targetUrl == null || targetUrl.isEmpty()) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                out.flush();
                return;
            }

            synchronized (this) {
                session = getOrStartSessionLocked(targetUrl);
                session.onClientAttached();
                attached = true;
            }

            if (!session.waitForReady(READY_WAIT_TIMEOUT_MS)) {
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes("US-ASCII"));
                out.flush();
                return;
            }

            String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + session.getContentType() + "\r\n"
                    + "Accept-Ranges: none\r\n"
                    + "Cache-Control: no-cache, no-store\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(headers.getBytes("US-ASCII"));
            out.flush();
            if ("HEAD".equalsIgnoreCase(method)) {
                return;
            }

            session.streamTo(out);
        } catch (Throwable ignored) {
            // Expected when Stagefright closes probe sockets mid-stream.
        } finally {
            if (attached && session != null) {
                session.onClientDetached();
            }
            try {
                client.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String extractQueryParam(String pathAndQuery, String key) {
        int q = pathAndQuery.indexOf('?');
        if (q < 0 || q >= pathAndQuery.length() - 1) {
            return null;
        }
        String query = pathAndQuery.substring(q + 1);
        String prefix = key + "=";
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            if (pair.startsWith(prefix)) {
                try {
                    return URLDecoder.decode(pair.substring(prefix.length()), "UTF-8").trim();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean isEphemeralUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("rj-tok")
                || lower.contains("rj-ttl")
                || lower.contains("zt=")
                || lower.contains("token=")
                || lower.contains("exp=")
                || lower.contains("expires=")
                || lower.contains("sig=")
                || lower.contains("hdnts=");
    }

    private static boolean isPlaylistResponse(String url, String contentType) {
        String lowerUrl = url != null ? url.toLowerCase(Locale.US) : "";
        int q = lowerUrl.indexOf('?');
        if (q >= 0) {
            lowerUrl = lowerUrl.substring(0, q);
        }
        String lowerCt = contentType != null ? contentType.toLowerCase(Locale.US) : "";
        if (lowerUrl.endsWith(".m3u8") || lowerCt.contains("vnd.apple.mpegurl")) {
            return false;
        }
        return lowerUrl.endsWith(".pls")
                || lowerUrl.endsWith(".m3u")
                || lowerCt.contains("scpls")
                || lowerCt.contains("pls+xml")
                || lowerCt.contains("mpegurl");
    }

    private static String sanitizeContentType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "audio/mpeg";
        }
        String base = raw.trim();
        int semi = base.indexOf(';');
        if (semi > 0) {
            base = base.substring(0, semi).trim();
        }
        String lower = base.toLowerCase(Locale.US);
        if (lower.startsWith("audio/") || "application/ogg".equals(lower)) {
            return base;
        }
        return "audio/mpeg";
    }

    private static String extractFirstPlaylistStreamUrl(HttpURLConnection conn, String baseUrl) throws IOException {
        InputStream in = conn.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int total = 0;
        int n;
        while (total < 16384 && (n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
            total += n;
        }
        String text = new String(baos.toByteArray(), "UTF-8");
        String[] lines = text.split("\\r?\\n");
        URL ctx = new URL(baseUrl);
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.toLowerCase(Locale.US).startsWith("file")) {
                int eq = line.indexOf('=');
                if (eq > 0 && eq < line.length() - 1) {
                    line = line.substring(eq + 1).trim();
                } else {
                    continue;
                }
            }
            if (line.regionMatches(true, 0, "http://", 0, 7)
                    || line.regionMatches(true, 0, "https://", 0, 8)) {
                return line;
            }
            if (!line.startsWith("[")) {
                try {
                    return new URL(ctx, line).toString();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static final class RedirectResult {
        final HttpURLConnection conn;
        final String finalUrl;
        final int code;

        RedirectResult(HttpURLConnection conn, String finalUrl, int code) {
            this.conn = conn;
            this.finalUrl = finalUrl;
            this.code = code;
        }
    }

    private final class UpstreamSession {
        final String rawUrl;
        final byte[] ring = new byte[RING_BUFFER_BYTES];
        final Object dataLock = new Object();
        final AtomicInteger activeClients = new AtomicInteger(0);

        long totalWritten = 0L;
        volatile boolean ready = false;
        volatile boolean eof = false;
        volatile boolean failed = false;
        volatile boolean closed = false;
        volatile String contentType = "audio/mpeg";
        volatile long lastClientDetachedAt = System.currentTimeMillis();
        volatile HttpURLConnection currentConn;
        Thread pumpThread;

        UpstreamSession(String rawUrl) {
            this.rawUrl = rawUrl;
        }

        void start() {
            pumpThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    pumpLoop();
                }
            }, "SW-ProxyUpstream");
            pumpThread.setDaemon(true);
            pumpThread.start();
        }

        boolean isReusableFor(String url) {
            return rawUrl.equals(url) && !closed && !failed && !eof;
        }

        void onClientAttached() {
            activeClients.incrementAndGet();
        }

        void onClientDetached() {
            int rem = activeClients.decrementAndGet();
            if (rem <= 0) {
                lastClientDetachedAt = System.currentTimeMillis();
            }
        }

        String getContentType() {
            return contentType;
        }

        boolean waitForReady(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            synchronized (dataLock) {
                while (!ready && !eof && !failed && !closed) {
                    long wait = deadline - System.currentTimeMillis();
                    if (wait <= 0L) {
                        return false;
                    }
                    try {
                        dataLock.wait(Math.min(wait, 500L));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return ready && !closed;
            }
        }

        void streamTo(OutputStream out) throws IOException {
            byte[] localBuf = new byte[CHUNK_SIZE];
            long readPos;
            synchronized (dataLock) {
                readPos = (totalWritten <= ring.length)
                        ? 0L
                        : Math.max(totalWritten - ring.length, totalWritten - INITIAL_BURST_BYTES);
            }
            while (!closed) {
                int avail;
                synchronized (dataLock) {
                    while (readPos >= totalWritten && !eof && !closed) {
                        try {
                            dataLock.wait(1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    if (closed) {
                        return;
                    }
                    long oldest = totalWritten - ring.length;
                    if (readPos < oldest) {
                        readPos = oldest;
                    }
                    avail = (int) Math.min(localBuf.length, totalWritten - readPos);
                    if (avail > 0) {
                        int startIdx = (int) (readPos % ring.length);
                        int firstPart = Math.min(avail, ring.length - startIdx);
                        System.arraycopy(ring, startIdx, localBuf, 0, firstPart);
                        if (avail > firstPart) {
                            System.arraycopy(ring, 0, localBuf, firstPart, avail - firstPart);
                        }
                        readPos += avail;
                    }
                    if (avail == 0 && eof) {
                        return;
                    }
                }
                if (avail > 0) {
                    out.write(localBuf, 0, avail);
                    out.flush();
                }
            }
        }

        void close() {
            closed = true;
            HttpURLConnection c = currentConn;
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) {
                }
            }
            if (pumpThread != null) {
                pumpThread.interrupt();
            }
            synchronized (dataLock) {
                eof = true;
                dataLock.notifyAll();
            }
        }

        private RedirectResult openWithCacheFallback() throws IOException {
            String cached = resolvedUrlCache.get(rawUrl);
            if (cached != null) {
                try {
                    RedirectResult res = openAudioTarget(cached);
                    if (res.code >= 200 && res.code < 300) {
                        return res;
                    }
                    res.conn.disconnect();
                } catch (IOException ignored) {
                }
                resolvedUrlCache.remove(rawUrl);
            }
            return openAudioTarget(rawUrl);
        }

        private RedirectResult openAudioTarget(String startUrl) throws IOException {
            RedirectResult res = openFollowingRedirectsWithFinalUrl(
                    startUrl, UPSTREAM_CONNECT_TIMEOUT_MS, UPSTREAM_READ_TIMEOUT_MS, true, true);
            currentConn = res.conn;
            if (closed) {
                res.conn.disconnect();
                throw new IOException("Session closed");
            }
            if (res.code >= 200 && res.code < 300 && isPlaylistResponse(res.finalUrl, res.conn.getContentType())) {
                String resolvedStream = extractFirstPlaylistStreamUrl(res.conn, res.finalUrl);
                res.conn.disconnect();
                if (resolvedStream == null || resolvedStream.isEmpty()) {
                    throw new IOException("Empty playlist at " + res.finalUrl);
                }
                res = openFollowingRedirectsWithFinalUrl(
                        resolvedStream, UPSTREAM_CONNECT_TIMEOUT_MS, UPSTREAM_READ_TIMEOUT_MS, true, true);
                currentConn = res.conn;
            }
            return res;
        }

        private void pumpLoop() {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                RedirectResult res = openWithCacheFallback();
                conn = res.conn;
                if (res.code < 200 || res.code >= 300) {
                    throw new IOException("HTTP " + res.code + " from " + res.finalUrl);
                }
                if (!isEphemeralUrl(rawUrl) && !isEphemeralUrl(res.finalUrl)) {
                    if (resolvedUrlCache.size() > 128) {
                        resolvedUrlCache.clear();
                    }
                    resolvedUrlCache.put(rawUrl, res.finalUrl);
                }
                contentType = sanitizeContentType(conn.getContentType());
                synchronized (dataLock) {
                    lastClientDetachedAt = System.currentTimeMillis();
                    ready = true;
                    dataLock.notifyAll();
                }

                in = conn.getInputStream();
                byte[] buf = new byte[CHUNK_SIZE];
                while (!closed) {
                    if (ready && activeClients.get() <= 0
                            && (System.currentTimeMillis() - lastClientDetachedAt > SESSION_IDLE_MS)) {
                        Log.d(TAG, "Closing idle upstream session: " + rawUrl);
                        break;
                    }
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    if (n == 0) {
                        continue;
                    }
                    synchronized (dataLock) {
                        int writeIdx = (int) (totalWritten % ring.length);
                        int firstPart = Math.min(n, ring.length - writeIdx);
                        System.arraycopy(buf, 0, ring, writeIdx, firstPart);
                        if (n > firstPart) {
                            System.arraycopy(buf, firstPart, ring, 0, n - firstPart);
                        }
                        totalWritten += n;
                        dataLock.notifyAll();
                    }
                }
            } catch (Throwable t) {
                if (!closed) {
                    failed = true;
                    resolvedUrlCache.remove(rawUrl);
                    Log.w(TAG, "Upstream session failed for " + rawUrl + ": " + t.getMessage());
                }
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
                currentConn = null;
                synchronized (dataLock) {
                    eof = true;
                    dataLock.notifyAll();
                }
            }
        }
    }
}
