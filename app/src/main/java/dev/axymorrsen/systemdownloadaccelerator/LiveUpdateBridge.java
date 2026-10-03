package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import java.util.concurrent.ConcurrentHashMap;

final class LiveUpdateBridge {
    private static final Uri URI =
            Uri.parse(
                    "content://"
                            + TelemetryProvider.AUTHORITY
                            + "/live");
    private static final ConcurrentHashMap<Long, String> LAST =
            new ConcurrentHashMap<>();

    private LiveUpdateBridge() {}

    static void publish(
            Context context,
            ConnectionRegistry.Metadata metadata,
            long current,
            long total) {
        if (context == null
                || metadata == null
                || metadata.downloadId < 0L) {
            return;
        }

        Bundle extras = new Bundle();
        extras.putLong("id", metadata.downloadId);
        extras.putLong("current", Math.max(0L, current));
        extras.putLong("total", total);
        extras.putString("title", metadata.title);
        extras.putString("source", metadata.sourcePackage);
        android.graphics.drawable.Icon nativeIcon =
                DownloadIconResolver.nativeSmallIcon();
        if (nativeIcon != null) {
            extras.putParcelable("smallIcon", nativeIcon);
        }
        extras.putAll(
                DownloadControlController.liveActionBundle(
                        context,
                        metadata.downloadId,
                        metadata.sourcePackage));

        try {
            Bundle result =
                    context.getContentResolver().call(
                            URI,
                            "live_progress",
                            null,
                            extras);
            if (result == null) return;

            String detail = result.getString("detail");
            if (detail == null) {
                detail = result.getString("error");
            }
            if (detail == null) {
                detail = "posted="
                        + result.getBoolean("posted", false);
            }

            String old = LAST.put(
                    metadata.downloadId,
                    detail);
            if (!detail.equals(old)) {
                EngineTelemetry.emit(
                        context,
                        "LIVE_UPDATE",
                        "id=" + metadata.downloadId
                                + " "
                                + detail);
            }
        } catch (Throwable t) {
            EngineTelemetry.emit(
                    context,
                    "LIVE_BRIDGE_ERROR",
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
        }
    }

    static void end(
            Context context,
            ConnectionRegistry.Metadata metadata) {
        if (context == null
                || metadata == null
                || metadata.downloadId < 0L) {
            return;
        }

        LAST.remove(metadata.downloadId);
        Bundle extras = new Bundle();
        extras.putLong("id", metadata.downloadId);
        try {
            context.getContentResolver().call(
                    URI,
                    "live_end",
                    null,
                    extras);
        } catch (Throwable ignored) {
        }
    }
}
