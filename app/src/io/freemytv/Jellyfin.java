package io.freemytv;

import android.content.ComponentName;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The "Continue" tab: what you're in the middle of and the next episodes, from your own Jellyfin server
 * (config.properties "jellyfin_url", "jellyfin_key", "jellyfin_user"). A card opens the title in Wholphin, with the same
 * intent Wholphin puts on its own Google TV row. The key goes only in a request header, never in a URL or the log.
 */
final class Jellyfin {
    private Jellyfin() {}

    static final String KIND = "jellyfin";
    static final String WHOLPHIN = "com.github.damontecres.wholphin";
    private static final String URL_BASE = Config.JELLYFIN_URL.trim();
    private static volatile String userId, serverId, userName;
    private static volatile List<String> users = new ArrayList<>();

    static boolean configured() {
        return !URL_BASE.isEmpty() && !Config.JELLYFIN_KEY.trim().isEmpty() && !Config.JELLYFIN_USER.trim().isEmpty();
    }

    /** Resumable titles first (most recent first), then the next episodes of other shows. Runs off the UI thread. */
    static List<Recs.Pick> fetch(int max) throws IOException, org.json.JSONException {
        String want = Prefs.jellyfinUser();
        if (userId == null || !want.equalsIgnoreCase(userName)) findUser(want);
        String fields = "&fields=ParentId&enableImageTypes=Backdrop,Thumb,Primary&imageTypeLimit=1";
        JSONArray resume = get("/UserItems/Resume?userId=" + userId + "&limit=" + max + "&mediaTypes=Video" + fields)
                .getJSONArray("Items");
        JSONArray next = get("/Shows/NextUp?userId=" + userId + "&limit=" + max + "&enableResumable=false" + fields)
                .getJSONArray("Items");
        List<Recs.Pick> out = new ArrayList<>();
        Set<String> shows = new HashSet<>();
        for (JSONArray list : new JSONArray[] {resume, next}) {
            for (int i = 0; i < list.length() && out.size() < max; i++) {
                JSONObject o = list.getJSONObject(i);
                String series = o.optString("SeriesId", "");
                if (!series.isEmpty() && !shows.add(series)) continue;   // one card per show
                Recs.Pick p = pick(o);
                if (p != null) out.add(p);
            }
        }
        return out;
    }

    private static Recs.Pick pick(JSONObject o) {
        Recs.Pick p = new Recs.Pick();
        p.kind = KIND;
        p.itemId = o.optString("Id");
        p.itemType = o.optString("Type");
        p.id = p.itemId.hashCode();
        p.app = KIND;
        p.pkg = WHOLPHIN;
        p.detailed = true;
        boolean episode = "Episode".equals(p.itemType);
        p.title = episode ? o.optString("SeriesName", o.optString("Name")) : o.optString("Name");
        // The card is wide: a backdrop (the show's, for an episode), else a thumb, else the poster.
        String[] image = episode
                ? first(o, "ParentBackdropItemId", "ParentBackdropImageTags", "ParentThumbItemId", "ParentThumbImageTag")
                : first(o, "Id", "BackdropImageTags", "Id", null);
        if (image == null) {
            String tag = o.optJSONObject("ImageTags") == null ? "" : o.optJSONObject("ImageTags").optString("Primary");
            if (!tag.isEmpty()) image = new String[] {p.itemId, "Primary", tag};
        }
        if (image == null) return null;
        p.backdrop = "jf-" + image[0] + "-" + image[2];
        p.image = "/Items/" + image[0] + "/Images/" + image[1] + "?tag=" + image[2] + "&maxWidth=1920&quality=90";
        StringBuilder meta = new StringBuilder();
        if (episode) {
            p.seriesId = o.optString("SeriesId");
            p.seasonId = o.optString("SeasonId");
            p.season = o.optInt("ParentIndexNumber", -1);
            p.episode = o.optInt("IndexNumber", -1);
            if (p.season >= 0 && p.episode >= 0) {
                meta.append(Ui.tr("T", "S", "T")).append(p.season).append(" E").append(p.episode).append(" · ");
            }
            meta.append(o.optString("Name"));
        }
        JSONObject user = o.optJSONObject("UserData");
        long left = (o.optLong("RunTimeTicks") - (user == null ? 0 : user.optLong("PlaybackPositionTicks"))) / 600_000_000L;
        if (user != null && user.optLong("PlaybackPositionTicks") > 0 && left > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(left >= 60 ? (left / 60) + " h " + (left % 60) + " min" : left + " min")
                    .append(Ui.tr(" restantes", " left", " restantes"));
        }
        p.meta = meta.toString();
        return p;
    }

    /** [item id, image type, tag] for the first image the item has: a backdrop, else a thumb. */
    private static String[] first(JSONObject o, String backdropId, String backdropTags, String thumbId, String thumbTag) {
        JSONArray tags = o.optJSONArray(backdropTags);
        if (tags != null && tags.length() > 0 && !o.optString(backdropId).isEmpty()) {
            return new String[] {o.optString(backdropId), "Backdrop/0", tags.optString(0)};
        }
        String tag = thumbTag != null ? o.optString(thumbTag)
                : o.optJSONObject("ImageTags") == null ? "" : o.optJSONObject("ImageTags").optString("Thumb");
        if (!tag.isEmpty() && !o.optString(thumbId).isEmpty()) return new String[] {o.optString(thumbId), "Thumb", tag};
        return null;
    }

    /**
     * The shows and movies on the server, for matching Hikari's anime to them: {id, name, original title, AniList id,
     * backdrop tag, AniDB id, Shoko series id, TMDB id} (empty strings where missing; Shokofin sets the ids).
     */
    static List<String[]> library() throws IOException, org.json.JSONException {
        String want = Prefs.jellyfinUser();
        if (userId == null || !want.equalsIgnoreCase(userName)) findUser(want);
        JSONArray items = get("/Items?userId=" + userId + "&includeItemTypes=Series,Movie&recursive=true"
                + "&fields=ProviderIds,OriginalTitle&enableImageTypes=Backdrop&imageTypeLimit=1").getJSONArray("Items");
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.getJSONObject(i);
            JSONObject ids = o.optJSONObject("ProviderIds");
            JSONArray tags = o.optJSONArray("BackdropImageTags");
            if (ids == null) ids = new JSONObject();
            out.add(new String[] {o.optString("Id"), o.optString("Name"), o.optString("OriginalTitle"), ids.optString("AniList"),
                    tags == null || tags.length() == 0 ? "" : tags.optString(0), ids.optString("AniDB"),
                    ids.optString("Shoko Series"), ids.optString("TheMovieDb")});
        }
        return out;
    }

    /** The episode to play next in a show and where to resume it (ms): {episode id, position}, or null. */
    static String[] nextEpisode(String seriesId) throws IOException, org.json.JSONException {
        String want = Prefs.jellyfinUser();
        if (userId == null || !want.equalsIgnoreCase(userName)) findUser(want);
        JSONArray items = get("/Shows/NextUp?userId=" + userId + "&seriesId=" + seriesId + "&limit=1&enableResumable=true")
                .getJSONArray("Items");
        if (items.length() == 0) return null;
        JSONObject e = items.getJSONObject(0), user = e.optJSONObject("UserData");
        long ticks = user == null ? 0 : user.optLong("PlaybackPositionTicks");
        return new String[] {e.optString("Id"), String.valueOf(ticks / 10_000L)};
    }

    /** Plays that episode in Wholphin, from `positionMs` (Wholphin's own playback intent). */
    static Intent play(Recs.Pick p) {
        Intent i = new Intent(WHOLPHIN + ".PLAYBACK").setComponent(new ComponentName(WHOLPHIN, WHOLPHIN + ".MainActivity"))
                .putExtra("itemId", uuid(p.playId)).putExtra("position", p.positionMs);
        if (userId != null && serverId != null) i.putExtra("serverId", uuid(serverId)).putExtra("userId", uuid(userId));
        return i;
    }

    /** Opens the title in Wholphin, signed in as the same user (Wholphin switches to it if needed). */
    static Intent intent(Recs.Pick p) {
        Intent i = new Intent().setComponent(new ComponentName(WHOLPHIN, WHOLPHIN + ".MainActivity"))
                .putExtra("itemId", uuid(p.itemId)).putExtra("itemType", p.itemType);
        if (p.seriesId != null && p.seasonId != null && p.season >= 0 && p.episode >= 0) {
            i.putExtra("seriesId", uuid(p.seriesId)).putExtra("seaId", uuid(p.seasonId))
                    .putExtra("seaNum", p.season).putExtra("epNum", p.episode);
        }
        if (userId != null && serverId != null) i.putExtra("serverId", uuid(serverId)).putExtra("userId", uuid(userId));
        return i;
    }

    /** Jellyfin writes ids without dashes; Wholphin reads them as UUIDs. */
    private static String uuid(String id) {
        if (id == null || id.length() != 32) return id;
        return id.substring(0, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16) + "-" + id.substring(16, 20)
                + "-" + id.substring(20);
    }

    private static void findUser(String name) throws IOException, org.json.JSONException {
        JSONArray list = readUsers();
        for (int i = 0; i < list.length(); i++) {
            JSONObject u = list.getJSONObject(i);
            if (u.optString("Name").equalsIgnoreCase(name)) {
                serverId = u.optString("ServerId", null);
                userId = u.optString("Id");
                userName = name;
                return;
            }
        }
        throw new IOException("Jellyfin has no user named " + name);
    }

    private static JSONArray readUsers() throws IOException, org.json.JSONException {
        JSONArray list = new JSONArray(read("/Users"));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) names.add(list.getJSONObject(i).optString("Name"));
        users = names;
        return list;
    }

    /** The server's user names (Home Settings → Jellyfin profile); empty until loadUsers has answered. */
    static List<String> users() { return users; }

    static void loadUsers(Runnable done) {
        new Thread(() -> {
            try {
                readUsers();
            } catch (Exception e) {
                AppLog.w("jellyfin users: " + e.getMessage());
            }
            done.run();
        }, "jellyfin-users").start();
    }

    private static JSONObject get(String path) throws IOException, org.json.JSONException {
        return new JSONObject(read(path));
    }

    private static String read(String path) throws IOException {
        HttpURLConnection c = open(path);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("Jellyfin rejected the key");
            if (code != 200) throw new IOException("Jellyfin returned HTTP " + code);
            return text(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** A request to the server, with the key in the header. Used for the images too (Recs). */
    static HttpURLConnection open(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_BASE + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "MediaBrowser Client=\"Free My TV\", Device=\"TV\", DeviceId=\"freemytv\", "
                + "Version=\"1\", Token=\"" + Config.JELLYFIN_KEY.trim() + "\"");
        return c;
    }

    private static String text(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // MARK: - Saved list (shown right away on a cold start, before the server answers; Hikari's too)

    static JSONArray save(List<Recs.Pick> list) throws org.json.JSONException {
        JSONArray a = new JSONArray();
        for (Recs.Pick p : list) {
            a.put(new JSONObject().put("kind", p.kind).put("id", p.id).put("pkg", p.pkg == null ? "" : p.pkg)
                    .put("itemId", p.itemId == null ? "" : p.itemId).put("itemType", p.itemType).put("title", p.title)
                    .put("meta", p.meta).put("backdrop", p.backdrop).put("image", p.image)
                    .put("seriesId", p.seriesId == null ? "" : p.seriesId).put("seasonId", p.seasonId == null ? "" : p.seasonId)
                    .put("season", p.season).put("episode", p.episode).put("section", p.section == null ? "" : p.section)
                    .put("playId", p.playId == null ? "" : p.playId).put("positionMs", p.positionMs));
        }
        return a;
    }

    static List<Recs.Pick> parse(JSONArray a) {
        List<Recs.Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Recs.Pick p = new Recs.Pick();
            p.kind = o.optString("kind", KIND);
            p.pkg = o.has("pkg") ? (o.optString("pkg").isEmpty() ? null : o.optString("pkg")) : WHOLPHIN;
            p.app = p.pkg == null ? null : KIND;
            p.detailed = true;
            p.itemId = o.optString("itemId").isEmpty() ? null : o.optString("itemId");
            p.id = o.has("id") ? o.optInt("id") : p.itemId.hashCode();
            p.itemType = o.optString("itemType");
            p.title = o.optString("title");
            p.meta = o.optString("meta");
            p.backdrop = o.optString("backdrop");
            p.image = o.optString("image");
            p.seriesId = o.optString("seriesId").isEmpty() ? null : o.optString("seriesId");
            p.seasonId = o.optString("seasonId").isEmpty() ? null : o.optString("seasonId");
            p.season = o.optInt("season", -1);
            p.episode = o.optInt("episode", -1);
            p.section = o.optString("section").isEmpty() ? null : o.optString("section");
            p.playId = o.optString("playId").isEmpty() ? null : o.optString("playId");
            p.positionMs = o.optLong("positionMs");
            out.add(p);
        }
        return out;
    }
}
