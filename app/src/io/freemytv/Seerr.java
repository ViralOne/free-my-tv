package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Requests from the home screen's movie and show cards through your Jellyseerr (config.properties "jellyseerr_url",
 * "jellyseerr_key"): for a title that is neither in your Jellyfin library nor on an app on the TV. Only the seasons
 * nobody has asked for yet, like Hikari does. The key goes only in a header.
 */
final class Seerr {
    private Seerr() {}

    private static final String URL_BASE = Config.JELLYSEERR_URL.trim();
    // Jellyseerr's media statuses (2 pending ... 5 available) and live request statuses (1 pending, 2 approved, 5 done).
    private static final int MEDIA_PENDING = 2;

    static boolean configured() {
        return !URL_BASE.isEmpty() && !Config.JELLYSEERR_KEY.trim().isEmpty();
    }

    /** What OK would request: the body for /api/v1/request and a sentence saying it, or why it can't. */
    static final class Plan {
        JSONObject body;
        String what = "", why = "";
    }

    /** Asks Jellyseerr what is already there or asked for. Runs off the UI thread. */
    static Plan plan(Recs.Pick p) throws IOException, org.json.JSONException {
        boolean tv = "tv".equals(p.kind);
        JSONObject d = new JSONObject(send("GET", "/api/v1/" + (tv ? "tv/" : "movie/") + p.id, null));
        JSONObject info = d.optJSONObject("mediaInfo");
        Plan plan = new Plan();
        JSONObject body = new JSONObject().put("mediaType", tv ? "tv" : "movie").put("mediaId", p.id);
        if (!tv) {
            if (info != null && info.optInt("status") >= MEDIA_PENDING) {
                plan.why = Ui.tr("Já foi pedido.", "Already requested.", "Ya está pedido.");
                return plan;
            }
            plan.what = p.title;
            plan.body = body;
            return plan;
        }
        // Seasons spoken for: available or on the way, or in a request that is still live.
        Set<Integer> taken = new HashSet<>();
        JSONArray have = info == null ? null : info.optJSONArray("seasons");
        for (int i = 0; have != null && i < have.length(); i++) {
            if (have.getJSONObject(i).optInt("status") >= MEDIA_PENDING) taken.add(have.getJSONObject(i).optInt("seasonNumber"));
        }
        JSONArray requests = info == null ? null : info.optJSONArray("requests");
        for (int i = 0; requests != null && i < requests.length(); i++) {
            JSONObject r = requests.getJSONObject(i);
            int status = r.optInt("status");
            if (status != 1 && status != 2 && status != 5) continue;
            JSONArray seasons = r.optJSONArray("seasons");
            for (int j = 0; seasons != null && j < seasons.length(); j++) taken.add(seasons.getJSONObject(j).optInt("seasonNumber"));
        }
        JSONArray open = new JSONArray();
        JSONArray seasons = d.optJSONArray("seasons");
        for (int i = 0; seasons != null && i < seasons.length(); i++) {
            int n = seasons.getJSONObject(i).optInt("seasonNumber");
            if (n > 0 && !taken.contains(n)) open.put(n);     // specials (season 0) are left out
        }
        if (open.length() == 0) {
            plan.why = Ui.tr("Já foi pedido.", "Already requested.", "Ya está pedido.");
            return plan;
        }
        body.put("seasons", open);
        plan.what = open.length() == 1
                ? Ui.tr("a temporada ", "season ", "la temporada ") + open.getInt(0) + Ui.tr(" de ", " of ", " de ") + p.title
                : p.title + " (" + open.length() + Ui.tr(" temporadas)", " seasons)", " temporadas)");
        plan.body = body;
        return plan;
    }

    /** Sends the request; returns the sentence for the toast. Runs off the UI thread. */
    static String request(Plan plan) throws IOException {
        send("POST", "/api/v1/request", plan.body.toString());
        return Ui.tr("Pedido: ", "Requested: ", "Pedido: ") + plan.what + ".";
    }

    private static String send(String method, String path, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_BASE + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(20000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Api-Key", Config.JELLYSEERR_KEY.trim());
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("Jellyseerr rejected the key");
            if (code == 409) throw new IOException(Ui.tr("já foi pedido", "already requested", "ya está pedido"));
            if (code < 200 || code >= 300) throw new IOException("Jellyseerr returned HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }
}
