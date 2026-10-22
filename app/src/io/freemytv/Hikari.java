package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The "Anime" tab, from your Hikari deck (config.properties "hikari_url", "hikari_token"), in three groups like
 * Hikari's own: what you're watching (in your Jellyfin library; OK plays the next episode in Wholphin), what's airing
 * now and what's trending this week. Owned titles open in Wholphin; the others can be requested through Hikari (OK,
 * then OK again to confirm). The token goes only in a header.
 */
final class Hikari {
    private Hikari() {}

    static final String KIND = "anime";
    static final String WATCHING = "watching", AIRING = "airing", TRENDING = "trending";
    private static final String URL_BASE = Config.HIKARI_URL.trim();

    static boolean configured() {
        return !URL_BASE.isEmpty() && !Config.HIKARI_TOKEN.trim().isEmpty();
    }

    /** The group's name above its first card. */
    static String sectionLabel(String section) {
        if (WATCHING.equals(section)) return Ui.tr("Watching");
        if (AIRING.equals(section)) return Ui.tr("Airing now");
        if (TRENDING.equals(section)) return Ui.tr("Trending this week");
        return "";
    }

    /** What you're watching (as many as Home Settings' cards), then up to 12 airing and 12 trending. Off the UI thread. */
    static List<Recs.Pick> fetch(int cards) throws IOException, org.json.JSONException {
        JSONArray rows = new JSONObject(read("/api/discover")).getJSONArray("rows");
        Map<String, JSONArray> byId = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            byId.put(r.optString("id"), r.optJSONArray("media"));
        }
        List<String[]> library = libraryOrEmpty();
        List<Recs.Pick> out = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        // Watching: started in Jellyfin and not finished, the most recently played first, from any of Hikari's rows.
        List<JSONObject> watching = new ArrayList<>();
        for (JSONArray media : byId.values()) {
            for (int i = 0; media != null && i < media.length(); i++) {
                JSONObject m = media.getJSONObject(i), w = m.optJSONObject("watch");
                if (w != null && w.optInt("played") > 0 && w.optInt("played") < w.optInt("total") && seen.add(m.optInt("id"))) {
                    watching.add(m);
                }
            }
        }
        java.util.Collections.sort(watching, (a, b) -> b.optJSONObject("watch").optString("lastPlayed")
                .compareTo(a.optJSONObject("watch").optString("lastPlayed")));
        add(out, watching, WATCHING, cards, library, true);
        add(out, list(byId.get("airing"), seen), AIRING, Recs.MAX_OWN, library, false);
        add(out, list(byId.get("trending"), seen), TRENDING, Recs.MAX_OWN, library, false);
        return out;
    }

    private static List<JSONObject> list(JSONArray media, Set<Integer> seen) throws org.json.JSONException {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; media != null && i < media.length(); i++) {
            JSONObject m = media.getJSONObject(i);
            if (seen.add(m.optInt("id"))) out.add(m);
        }
        return out;
    }

    private static void add(List<Recs.Pick> out, List<JSONObject> media, String section, int each, List<String[]> library,
                            boolean ownedOnly) {
        int added = 0;
        for (JSONObject m : media) {
            if (added >= each) return;
            Recs.Pick p = pick(m, library, out.isEmpty());
            if (p == null || (ownedOnly && p.itemId == null)) continue;
            p.section = section;
            if (WATCHING.equals(section)) {
                try {
                    String[] next = Jellyfin.nextEpisode(p.itemId);
                    if (next != null) {
                        p.playId = next[0];
                        p.positionMs = Long.parseLong(next[1]);
                    }
                } catch (Exception e) {
                    AppLog.w("anime: next episode: " + e.getMessage());
                }
            }
            out.add(p);
            added++;
        }
    }

    /** Jellyfin's shows, when it is set up (without it nothing can open, but the tab still shows). */
    private static List<String[]> libraryOrEmpty() {
        if (!Jellyfin.configured()) return new ArrayList<>();
        try {
            return Jellyfin.library();
        } catch (Exception e) {
            AppLog.w("anime: jellyfin library: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static Recs.Pick pick(JSONObject m, List<String[]> library, boolean wide) {
        JSONObject title = m.optJSONObject("title");
        if (title == null) return null;
        Recs.Pick p = new Recs.Pick();
        p.kind = KIND;
        p.id = m.optInt("id");
        p.title = title.optString("display");
        p.detailed = true;
        String[] item = match(m, title, library);
        if (item != null) {
            p.app = Jellyfin.KIND;
            p.itemId = item[0];
            p.itemType = "Series";
            p.pkg = Jellyfin.WHOLPHIN;
        }
        // The wide card wants a landscape picture (the show's backdrop on Jellyfin, else AniList's banner); the tall
        // ones, the cover.
        String cover = m.optString("cover", ""), banner = m.optString("banner", "");
        if (cover.equals("null")) cover = "";
        if (banner.equals("null")) banner = "";
        if (wide && item != null && !item[4].isEmpty()) {
            p.backdrop = "jf-" + item[0] + "-" + item[4];
            p.image = "/Items/" + item[0] + "/Images/Backdrop/0?tag=" + item[4] + "&maxWidth=1920&quality=90";
        } else if (wide && !banner.isEmpty()) {
            p.backdrop = "hk-" + p.id + "-banner";
            p.image = banner;
        } else if (!cover.isEmpty()) {
            p.backdrop = "hk-" + p.id + "-cover";
            p.image = cover;
        } else {
            return null;
        }
        p.meta = meta(m, item != null);
        if (!banner.isEmpty()) p.banner = banner;
        String story = m.optString("synopsis", "");
        if (!story.isEmpty() && !story.equals("null")) p.synopsis = story.length() > 400 ? story.substring(0, 400) : story;
        JSONObject w = m.optJSONObject("watch");
        if (w != null && w.optInt("total") > 0) p.progress = Math.min(1f, (float) w.optInt("played") / w.optInt("total"));
        return p;
    }

    /** "Next: E5 · Name" while watching; otherwise the genre, the year and whether it is in your library. */
    private static String meta(JSONObject m, boolean owned) {
        JSONObject w = m.optJSONObject("watch");
        JSONObject next = w == null ? null : w.optJSONObject("next");
        if (w != null && w.optInt("played") > 0 && next != null) {
            String name = next.optString("name", "");
            return Ui.trf("Next: E{n}", "n", next.optInt("episode"))
                    + (name.isEmpty() || name.equals("null") ? "" : " · " + name);
        }
        List<String> parts = new ArrayList<>();
        JSONArray genres = m.optJSONArray("genres");
        if (genres != null && genres.length() > 0) parts.add(genres.optString(0));
        if (m.optInt("seasonYear") > 0) parts.add(String.valueOf(m.optInt("seasonYear")));
        // What OK does is said apart (Wholphin / Request under the card), so only "in your library" here.
        if (owned) parts.add(Ui.tr("In your library"));
        return String.join(" · ", parts);
    }

    /**
     * The Jellyfin item for this anime, or null when it isn't there: by AniList id, else by the AniDB or Shoko series
     * id Hikari got from Shoko (Shokofin names items its own way: "Kusuriya no Hitorigoto (2026)" for "… 3rd
     * Season"), else by title.
     */
    private static String[] match(JSONObject m, JSONObject title, List<String[]> library) {
        String id = String.valueOf(m.optInt("id"));
        for (String[] item : library) if (item[3].equals(id)) return item;
        JSONObject shoko = m.optJSONObject("shoko");
        if (shoko != null) {
            String anidb = shoko.optInt("anidbId") > 0 ? String.valueOf(shoko.optInt("anidbId")) : "";
            String series = shoko.optInt("shokoId") > 0 ? String.valueOf(shoko.optInt("shokoId")) : "";
            for (String[] item : library) {
                if ((!anidb.isEmpty() && item[5].equals(anidb)) || (!series.isEmpty() && item[6].equals(series))) return item;
            }
        }
        Set<String> names = new HashSet<>();
        if (shoko != null) names.add(normalize(shoko.optString("name", "")));
        names.remove("");
        for (String k : new String[] {"romaji", "english", "native"}) {
            String n = normalize(title.optString(k, ""));
            if (n.length() >= 3) names.add(n);
        }
        for (String[] item : library) {
            if (names.contains(normalize(item[1])) || names.contains(normalize(item[2]))) return item;
        }
        return null;
    }

    /** "Re:Zero – Starting Life" → "rezerostartinglife": no case, accents, spaces or punctuation. */
    private static String normalize(String s) {
        if (s == null || s.equals("null")) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        return n.replaceAll("[\\p{M}\\p{P}\\p{S}\\s]+", "");
    }

    // MARK: - Requests (the same choice Hikari's Request button makes)

    /** What OK would request: the body for /api/request, and a sentence saying it; or why it can't. */
    static final class Plan {
        JSONObject body;        // null: nothing to request (`why` says so)
        String what = "", why = "";
    }

    /** Asks Hikari about the title and picks the season like its panel does. Runs off the UI thread. */
    static Plan plan(Recs.Pick p) throws IOException, org.json.JSONException {
        JSONObject d = new JSONObject(read("/api/anime/" + p.id));
        JSONObject r = d.optJSONObject("request");
        Plan plan = new Plan();
        if (r == null || !r.optBoolean("matched")) {
            plan.why = Ui.tr("Hikari couldn't match it on TMDB: request it in Hikari.");
            return plan;
        }
        String type = r.optString("mediaType");
        JSONObject body = new JSONObject().put("tmdbId", r.optInt("tmdbId")).put("mediaType", type);
        if ("tv".equals(type)) {
            JSONArray seasons = r.optJSONArray("seasons");
            List<Integer> open = new ArrayList<>();
            for (int i = 0; seasons != null && i < seasons.length(); i++) {
                JSONObject season = seasons.getJSONObject(i);
                if (!season.optBoolean("taken")) open.add(season.optInt("seasonNumber"));
            }
            if (seasons != null && seasons.length() > 0 && open.isEmpty()) {
                plan.why = Ui.tr("Already requested.");
                return plan;
            }
            int suggested = r.optInt("suggestedSeason", 0);
            JSONArray chosen = new JSONArray();
            if (seasons == null || seasons.length() == 0) {
                body.put("seasons", "all");
            } else if (suggested > 0 && open.contains(suggested)) {
                chosen.put(suggested);
            } else if (open.size() == 1) {
                chosen.put(open.get(0));
            } else {
                plan.why = Ui.tr("Pick the season in Hikari.");
                return plan;
            }
            if (chosen.length() > 0) body.put("seasons", chosen);
            JSONObject routing = d.optJSONObject("routing");
            body.put("forceAnime", routing != null && routing.has("treatedAsAnime") && !routing.optBoolean("treatedAsAnime"))
                    .put("anilistId", p.id).put("whole", false);
            plan.what = chosen.length() > 0
                    ? Ui.trf("season {n} of {title}", "n", chosen.getInt(0), "title", p.title)
                    : p.title;
        } else {
            plan.what = p.title;
        }
        plan.body = body;
        return plan;
    }

    /** Sends the request; returns the sentence for the toast. Runs off the UI thread. */
    static String request(Plan plan) throws IOException {
        HttpURLConnection c = open("/api/request");
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.getOutputStream().write(plan.body.toString().getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            if (code == 409) return Ui.tr("Already requested.");
            if (code == 401) throw new IOException("Hikari rejected the token");
            if (code != 200 && code != 201) throw new IOException("Hikari returned HTTP " + code);
            return Ui.trf("Requested: {what}.", "what", plan.what);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_BASE + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(30000);    // Hikari builds the deck from several services; the first call can take a while
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + Config.HIKARI_TOKEN.trim());
        return c;
    }

    private static String read(String path) throws IOException {
        HttpURLConnection c = open(path);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Hikari rejected the token");
            if (code != 200) throw new IOException("Hikari returned HTTP " + code);
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
