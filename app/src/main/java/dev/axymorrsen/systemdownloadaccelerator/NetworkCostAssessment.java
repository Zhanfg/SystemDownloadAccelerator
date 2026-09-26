package dev.axymorrsen.systemdownloadaccelerator;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.util.ArrayList;
import java.util.List;

/** Conservative interpretation of VPN metering versus physical underlay. */
final class NetworkCostAssessment {
    final boolean activeVpn;
    final boolean systemMetered;
    final boolean uniqueUnderlyingUnmetered;
    final String underlyingKind;
    final int physicalCandidates;
    final String detail;

    private NetworkCostAssessment(
            boolean activeVpn,
            boolean systemMetered,
            boolean uniqueUnderlyingUnmetered,
            String underlyingKind,
            int physicalCandidates,
            String detail) {
        this.activeVpn = activeVpn;
        this.systemMetered = systemMetered;
        this.uniqueUnderlyingUnmetered = uniqueUnderlyingUnmetered;
        this.underlyingKind = underlyingKind;
        this.physicalCandidates = physicalCandidates;
        this.detail = detail;
    }

    static NetworkCostAssessment assess(Context context) {
        try {
            ConnectivityManager cm =
                    context.getSystemService(ConnectivityManager.class);
            if (cm == null) {
                return unknown("ConnectivityManager unavailable");
            }

            Network active = cm.getActiveNetwork();
            NetworkCapabilities activeCaps =
                    active == null
                            ? null
                            : cm.getNetworkCapabilities(active);

            boolean vpn =
                    activeCaps != null
                            && activeCaps.hasTransport(
                                    NetworkCapabilities.TRANSPORT_VPN);
            boolean metered = cm.isActiveNetworkMetered();

            if (!vpn) {
                return new NetworkCostAssessment(
                        false,
                        metered,
                        false,
                        physicalKind(activeCaps),
                        activeCaps == null ? 0 : 1,
                        "active=" + physicalKind(activeCaps)
                                + " metered=" + metered);
            }

            List<NetworkCapabilities> physical =
                    new ArrayList<>();
            for (Network network : cm.getAllNetworks()) {
                if (active != null && active.equals(network)) {
                    continue;
                }
                NetworkCapabilities caps =
                        cm.getNetworkCapabilities(network);
                if (caps == null) continue;
                if (caps.hasTransport(
                        NetworkCapabilities.TRANSPORT_VPN)) {
                    continue;
                }
                if (!caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        || !caps.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    continue;
                }
                physical.add(caps);
            }

            boolean uniqueUnmetered = false;
            String kind = "UNKNOWN";
            if (physical.size() == 1) {
                NetworkCapabilities caps = physical.get(0);
                kind = physicalKind(caps);
                uniqueUnmetered =
                        caps.hasCapability(
                                NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            }

            return new NetworkCostAssessment(
                    true,
                    metered,
                    uniqueUnmetered,
                    kind,
                    physical.size(),
                    "vpnMetered=" + metered
                            + " physicalCandidates=" + physical.size()
                            + " underlay=" + kind
                            + " underlayUnmetered=" + uniqueUnmetered);
        } catch (Throwable t) {
            return unknown(
                    t.getClass().getSimpleName()
                            + ": "
                            + String.valueOf(t.getMessage()));
        }
    }

    private static NetworkCostAssessment unknown(String detail) {
        return new NetworkCostAssessment(
                false,
                true,
                false,
                "UNKNOWN",
                0,
                detail);
    }

    private static String physicalKind(NetworkCapabilities caps) {
        if (caps == null) return "UNKNOWN";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "WIFI";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "ETHERNET";
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "CELLULAR";
        }
        return "OTHER";
    }
}
