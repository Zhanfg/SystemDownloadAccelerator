package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;

import dev.axymorrsen.systemdownloadaccelerator.core.ContentRange;
import dev.axymorrsen.systemdownloadaccelerator.core.MicroPartPlanner;
import dev.axymorrsen.systemdownloadaccelerator.core.RangePart;
import dev.axymorrsen.systemdownloadaccelerator.engine.AdaptivePolicy;
import dev.axymorrsen.systemdownloadaccelerator.engine.Conditions;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * AB-inspired independent range engine.
 *
 * It never writes DownloadProvider's destination directly. Instead, it presents
 * a sequential InputStream backed by parallel Range workers, so Android's native
 * DownloadThread remains authoritative for destination writes, progress,
 * pause/cancel, retry, resume and completion.
 */
final class ParallelRangeEngine {
    private static final long MIN_ACCEL_BYTES = 8L * 1024L * 1024L;
    private static final int PROBE_END = 255;

    private static final AdaptivePolicy ADAPTIVE = new AdaptivePolicy();

    static final class EntityValidator {
        final String headerValue;

        EntityValidator(String headerValue) {
            this.headerValue = headerValue;
        }
    }

    private ParallelRangeEngine() {}

    static InputStream maybeCreate(
            HttpURLConnection original,
            ConnectionRegistry.Metadata metadata) {
        if (original == null || metadata == null) {
            return null;
        }
        if (ConnectionRegistry.isInternalRequest()) {
            return null;
        }

        Context context = metadata.context;
        try {
            int code = original.getResponseCode();
            long startOffset;
            long totalLength;

            if (code == HttpURLConnection.HTTP_OK) {
                startOffset = 0L;
                totalLength = original.getHeaderFieldLong("Content-Length", -1L);
            } else if (code == 206) {
                ContentRange range = ContentRange.parse(
                        original.getHeaderField("Content-Range"));
                if (range == null) {
                    fallback(context, "206 without valid Content-Range");
                    return null;
                }
                startOffset = range.from;
                totalLength = range.total;
            } else {
                return null;
            }

            long remaining = totalLength - startOffset;
            if (totalLength <= 0L || remaining < MIN_ACCEL_BYTES) {
                fallback(context, "remaining file below 8 MiB");
                return null;
            }

            String encoding = clean(original.getHeaderField("Content-Encoding"));
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                fallback(context, "encoded response: " + encoding);
                return null;
            }

            EntityValidator validator = chooseValidator(original);
            if (validator == null) {
                fallback(context, "no ETag/Last-Modified validator");
                return null;
            }

            Network network = metadata.network;

            if (metadata.factoryKind == ConnectionRegistry.FactoryKind.HTTP_ENGINE
                    && network == null) {
                fallback(
                        context,
                        "HttpEngine origin has no replayable bound Network");
                return null;
            }

            if (metadata.factoryKind == ConnectionRegistry.FactoryKind.UNKNOWN
                    && network == null) {
                fallback(
                        context,
                        "unknown connection factory without Network");
                return null;
            }

            EngineTelemetry.emit(
                    context,
                    "STREAM_INTERCEPT",
                    "build=" + BuildConfig.BUILD_ID
                            + " code=" + code
                            + " start=" + startOffset
                            + " total=" + totalLength
                            + " factory=" + metadata.factoryKind
                            + " boundNetwork=" + (network != null));

            if (code == HttpURLConnection.HTTP_OK
                    && !probeRange(
                            original,
                            metadata,
                            validator,
                            totalLength)) {
                fallback(context, "server rejected Range probe");
                return null;
            }

            Conditions conditions = detectConditions(context, network);
            int policyInitial =
                    ADAPTIVE.initialWorkers(remaining, conditions);
            int maxWorkers =
                    ADAPTIVE.maxWorkers(remaining, conditions);
            int initialWorkers =
                    HostProfileStore.recommendedInitial(
                            context,
                            original.getURL().getHost(),
                            policyInitial,
                            maxWorkers);
            if (initialWorkers < 2 || maxWorkers < 2) {
                fallback(context, "adaptive policy selected one worker");
                return null;
            }

            List<RangePart> parts =
                    MicroPartPlanner.plan(
                            startOffset,
                            totalLength,
                            maxWorkers);
            if (parts.size() < 2) {
                fallback(context, "planner produced one part");
                return null;
            }

            File cacheRoot = chooseCacheRoot(context);
            if (cacheRoot == null) {
                fallback(context, "no cache directory for reorder window");
                return null;
            }

            EngineTelemetry.emit(
                    context,
                    "PARTS",
                    "build=" + BuildConfig.BUILD_ID
                            + " initial=" + initialWorkers
                            + " policyInitial=" + policyInitial
                            + " max=" + maxWorkers
                            + " parts=" + parts.size()
                            + " network=" + conditions.networkKind
                            + " metered=" + conditions.metered
                            + " linkKbps=" + conditions.downstreamKbps);

            ParallelRangeInputStream stream = new ParallelRangeInputStream(
                    original.getURL(),
                    metadata,
                    snapshotHeaders(metadata),
                    original,
                    validator,
                    totalLength,
                    parts,
                    initialWorkers,
                    maxWorkers,
                    cacheRoot);

            // Abort the unused original full-body socket. DownloadThread has
            // already parsed all response headers before it asks for the body.
            try {
                original.disconnect();
            } catch (Throwable ignored) {
            }

            EngineTelemetry.emit(
                    context,
                    "PARALLEL",
                    "parallel stream armed");
            return stream;
        } catch (Throwable t) {
            fallback(
                    context,
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
            return null;
        }
    }

    static ParallelRangeInputStream.RangeConnection openRange(
            URL url,
            ConnectionRegistry.Metadata metadata,
            Map<String, List<String>> headers,
            HttpURLConnection original,
            EntityValidator validator,
            long from,
            long to,
            long totalLength) throws Exception {
        return ConnectionRegistry.internal(() -> {
            HttpURLConnection conn =
                    openSiblingConnection(url, metadata);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(
                    original.getConnectTimeout() > 0
                            ? original.getConnectTimeout()
                            : 20_000);
            conn.setReadTimeout(
                    original.getReadTimeout() > 0
                            ? original.getReadTimeout()
                            : 20_000);

            if (conn instanceof HttpsURLConnection
                    && original instanceof HttpsURLConnection) {
                SSLSocketFactory ssl =
                        ((HttpsURLConnection) original).getSSLSocketFactory();
                if (ssl != null) {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(ssl);
                }
            }

            copyHeaders(headers, conn);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
            conn.setRequestProperty("If-Range", validator.headerValue);

            int code = conn.getResponseCode();
            if (code != 206) {
                long retryAfterMs =
                        parseRetryAfterMillis(
                                conn.getHeaderField("Retry-After"));
                conn.disconnect();
                throw new RangeHttpException(
                        code,
                        retryAfterMs);
            }

            ContentRange range =
                    ContentRange.parse(conn.getHeaderField("Content-Range"));
            if (range == null
                    || range.from != from
                    || range.total != totalLength) {
                conn.disconnect();
                throw new IOException(
                        "bad Content-Range "
                                + conn.getHeaderField("Content-Range")
                                + " expected start=" + from
                                + " total=" + totalLength);
            }

            verifyValidator(conn, validator);
            InputStream body = conn.getInputStream();
            return new ParallelRangeInputStream.RangeConnection(
                    conn,
                    body,
                    range.to);
        });
    }

    private static HttpURLConnection openSiblingConnection(
            URL url,
            ConnectionRegistry.Metadata metadata) throws IOException {
        switch (metadata.factoryKind) {
            case NETWORK:
                if (metadata.network == null) {
                    throw new IOException(
                            "NETWORK provenance missing bound Network");
                }
                return (HttpURLConnection)
                        metadata.network.openConnection(url);

            case URL:
                /*
                 * Recreate the request through the same factory that created
                 * the original ColorOS connection. This preserves the process
                 * default proxy/VPN/routing policy without inventing a Network
                 * object that the host never exposed.
                 */
                return (HttpURLConnection) url.openConnection();

            case HTTP_ENGINE:
                if (metadata.network == null) {
                    throw new IOException(
                            "HttpEngine provenance is not safely replayable");
                }
                return (HttpURLConnection)
                        metadata.network.openConnection(url);

            case UNKNOWN:
            default:
                if (metadata.network != null) {
                    return (HttpURLConnection)
                            metadata.network.openConnection(url);
                }
                throw new IOException(
                        "unknown connection factory is not replayable");
        }
    }

    private static boolean probeRange(
            HttpURLConnection original,
            ConnectionRegistry.Metadata metadata,
            EntityValidator validator,
            long totalLength) {
        ParallelRangeInputStream.RangeConnection probe = null;
        try {
            EngineTelemetry.emit(
                    metadata.context,
                    "RANGE_PROBE",
                    "bytes=0-" + PROBE_END);

            probe = openRange(
                    original.getURL(),
                    metadata,
                    snapshotHeaders(metadata),
                    original,
                    validator,
                    0L,
                    Math.min(PROBE_END, totalLength - 1L),
                    totalLength);

            EngineTelemetry.emit(
                    metadata.context,
                    "RANGE_OK",
                    "server supports byte ranges");
            return true;
        } catch (Throwable t) {
            EngineTelemetry.emit(
                    metadata.context,
                    "RANGE_REJECT",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
            return false;
        } finally {
            if (probe != null) {
                probe.close();
            }
        }
    }

    private static EntityValidator chooseValidator(HttpURLConnection conn) {
        String etag = clean(conn.getHeaderField("ETag"));
        if (etag != null && !etag.regionMatches(true, 0, "W/", 0, 2)) {
            return new EntityValidator(etag);
        }

        String modified = clean(conn.getHeaderField("Last-Modified"));
        if (modified != null) {
            return new EntityValidator(modified);
        }
        return null;
    }

    private static void verifyValidator(
            HttpURLConnection conn,
            EntityValidator validator) throws IOException {
        String etag = clean(conn.getHeaderField("ETag"));
        String modified = clean(conn.getHeaderField("Last-Modified"));

        if (etag != null
                && !etag.regionMatches(true, 0, "W/", 0, 2)
                && !validator.headerValue.equals(etag)) {
            throw new IOException("ETag changed during range transfer");
        }
        if (etag == null
                && modified != null
                && !validator.headerValue.equals(modified)) {
            throw new IOException("Last-Modified changed during range transfer");
        }
    }

    private static Map<String, List<String>> snapshotHeaders(
            ConnectionRegistry.Metadata metadata) {
        return metadata.snapshotHeaders();
    }

    private static void copyHeaders(
            Map<String, List<String>> headers,
            HttpURLConnection conn) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (isControlledHeader(name)) {
                continue;
            }
            for (String value : entry.getValue()) {
                conn.addRequestProperty(name, value);
            }
        }
    }

    private static boolean isControlledHeader(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return "range".equals(key)
                || "if-range".equals(key)
                || "if-match".equals(key)
                || "accept-encoding".equals(key)
                || "host".equals(key)
                || "content-length".equals(key)
                // Hop-by-hop headers belong to the original Android request.
                // Replaying Connection: close on every micro-part destroys
                // OkHttp/HttpURLConnection connection pooling and forces
                // repeated TCP/TLS setup.
                || "connection".equals(key)
                || "proxy-connection".equals(key)
                || "keep-alive".equals(key)
                || "transfer-encoding".equals(key)
                || "te".equals(key)
                || "trailer".equals(key)
                || "upgrade".equals(key);
    }

    private static Conditions detectConditions(
            Context context,
            Network network) {
        try {
            if (context == null) {
                return new Conditions(
                        Conditions.NetworkKind.UNKNOWN,
                        true,
                        false,
                        0);
            }

            ConnectivityManager cm =
                    context.getSystemService(ConnectivityManager.class);
            Network policyNetwork = network;
            if (policyNetwork == null && cm != null) {
                try {
                    policyNetwork = cm.getActiveNetwork();
                } catch (Throwable ignored) {
                }
            }

            NetworkCapabilities caps =
                    cm == null || policyNetwork == null
                            ? null
                            : cm.getNetworkCapabilities(policyNetwork);

            Conditions.NetworkKind kind =
                    Conditions.NetworkKind.UNKNOWN;
            boolean metered = true;

            if (caps != null) {
                metered = !caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    kind = Conditions.NetworkKind.VPN;

                    /*
                     * Some VPN implementations advertise the VPN network as
                     * metered even when the only validated physical underlay
                     * is unmetered Wi-Fi/Ethernet. For concurrency policy we
                     * can safely use the physical cost hint while keeping the
                     * transport classified as VPN (so the VPN worker ceiling
                     * still applies).
                     */
                    if (metered
                            && cm != null
                            && (network == null
                            || policyNetwork.equals(cm.getActiveNetwork()))) {
                        NetworkCostAssessment cost =
                                NetworkCostAssessment.assess(context);
                        if (cost.activeVpn
                                && cost.uniqueUnderlyingUnmetered) {
                            metered = false;
                            EngineTelemetry.emit(
                                    context,
                                    "VPN_UNDERLAY",
                                    "systemMetered=true underlay="
                                            + cost.underlyingKind
                                            + " effectiveMetered=false");
                        }
                    }
                } else if (caps.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI)) {
                    kind = Conditions.NetworkKind.WIFI;
                } else if (caps.hasTransport(
                        NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    kind = Conditions.NetworkKind.ETHERNET;
                } else if (caps.hasTransport(
                        NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    kind = Conditions.NetworkKind.CELLULAR;
                }
            }

            PowerManager pm =
                    context.getSystemService(PowerManager.class);
            boolean powerSave = pm != null && pm.isPowerSaveMode();
            int thermal = pm == null ? 0 : pm.getCurrentThermalStatus();

            int downstreamKbps = caps == null
                    ? 0
                    : Math.max(
                            0,
                            caps.getLinkDownstreamBandwidthKbps());

            return new Conditions(
                    kind,
                    metered,
                    powerSave,
                    thermal,
                    downstreamKbps);
        } catch (Throwable ignored) {
            return new Conditions(
                    Conditions.NetworkKind.UNKNOWN,
                    true,
                    false,
                    0);
        }
    }

    private static File chooseCacheRoot(Context context) {
        if (context == null) return null;

        File base = context.getNoBackupFilesDir();
        if (base == null) {
            base = context.getFilesDir();
        }
        if (base == null) {
            base = context.getCacheDir();
        }
        if (base == null) return null;

        File root = new File(base, "sysdl_range");
        if (!root.exists() && !root.mkdirs()) {
            return null;
        }
        return root;
    }

    private static long parseRetryAfterMillis(String value) {
        String text = clean(value);
        if (text == null) return -1L;
        try {
            long seconds = Long.parseLong(text);
            if (seconds < 0L) return -1L;
            return Math.min(
                    30_000L,
                    seconds * 1000L);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private static String clean(String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }

    private static void fallback(Context context, String reason) {
        EngineTelemetry.emit(context, "FALLBACK", reason);
    }
}
