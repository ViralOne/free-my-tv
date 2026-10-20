package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String poster;          // TMDB's poster (portrait), for the rows below the first
        boolean detailed;
        // Your own servers ("jellyfin" and "anime" kinds): the Jellyfin item to open in Wholphin (null: not in the
        // library), and the image: a path on the Jellyfin server or a full https address (Hikari's AniList covers)
        String itemId, itemType, seriesId, seasonId, image;
        int season = -1, episode = -1;
        // The row the title belongs to (TRENDING, NEW, EVERYWHERE; Hikari.WATCHING...), and the episode OK plays
        // (anime you're watching; null: opens the show)
        String section, playId;
        long positionMs;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMAGES = "https://image.tmdb.org/t/p/original";
    private static final String IMAGES_SMALL = "https://image.tmdb.org/t/p/w780", IMAGES_POSTER = "https://image.tmdb.org/t/p/w500";
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 5;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    static final String[] PERIOD_LABEL = {"PARA ESTA MANHÃ", "PARA ESTA TARDE", "PARA ESTA NOITE"};
    static final String[] PERIOD_LABEL_EN = {"THIS MORNING", "THIS AFTERNOON", "TONIGHT"};
    static final String[] PERIOD_LABEL_ES = {"PARA ESTA MAÑANA", "PARA ESTA TARDE", "PARA ESTA NOCHE"};
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put(Jellyfin.KIND, "Wholphin");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    private static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts
    /** Lists from your own servers, by kind (Jellyfin's "Continue", Hikari's anime), each refreshed on its own. */
    private final Map<String, List<Pick>> own = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> ownAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> ownFor = new java.util.concurrent.ConcurrentHashMap<>();  // what it was fetched for
    private final java.util.Set<String> ownFetching = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private interface Fetch { List<Pick> run(int max) throws Exception; }
    /** Most cards in a row (the home screen's first row and each row below it). */
    static final int MAX_OWN = 12;
    /** TMDB rows: trending on your apps, new on your apps, and trending but on none of them (Jellyfin or Jellyseerr). */
    static final String TRENDING = "trending", NEW = "new", EVERYWHERE = "everywhere";
    /** Poster size for the rows below the first (the focused size, so it stays sharp). */
    static final int POSTER_W = 268, POSTER_H = 402;
    private final ExecutorService posterIo = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> posters = new LinkedHashMap<>(64, 0.75f, true);   // least recently drawn first
    private final java.util.Set<String> posterPending = new java.util.HashSet<>();
    /** Your Jellyfin library by TMDB id ("movie:603", "tv:1399" → "item id/Movie"), for opening titles in Wholphin. */
    private volatile Map<String, String> library = new HashMap<>();
    private volatile long libraryAt;

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
            for (String kind : new String[] {Jellyfin.KIND, Hikari.KIND}) {
                String list = cache.getString(kind, null);
                if (list != null) own.put(kind, Jellyfin.parse(new JSONArray(list)));
            }
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        return (Ui.es ? PERIOD_LABEL_ES : Ui.en ? PERIOD_LABEL_EN : PERIOD_LABEL)[period(System.currentTimeMillis())];
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        boolean continueTab = Jellyfin.KIND.equals(kind), anime = Hikari.KIND.equals(kind);
        if (continueTab || anime) {
            // Without Wholphin, "Continue" has nothing to open; the anime cards still say what's airing.
            boolean on = continueTab ? Jellyfin.configured() && installed(Jellyfin.WHOLPHIN) : Hikari.configured();
            List<Pick> all = own.get(kind);
            if (!on || all == null) return list;
            if (continueTab) {
                list.addAll(all.subList(0, Math.min(all.size(), Prefs.cards())));
                return list;
            }
            // Anime: the first row is what you're watching; with nothing started, the first group there is.
            String first = all.isEmpty() ? null : all.get(0).section;
            for (Pick p : all) if (first != null && first.equals(p.section) && list.size() < Prefs.cards()) list.add(p);
            return list;
        }
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // A new set every hour (as many as Home Settings asks for), never two from the same franchise ("Law & Order"
        // and "Law & Order: Special…").
        int n = Prefs.cards();
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < n; i++) {
            Pick p = list.get((int) ((hour * n + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /**
     * A row below the first: the titles of `section` (of `kind`; null = movies and shows), without the ones in `skip`.
     * TMDB's: only what an app on the TV streams, except EVERYWHERE (what none of them has). Anime: Hikari's group.
     */
    List<Pick> row(String kind, String section, List<Pick> skip) {
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (Pick p : skip) shown.add(key(p));
        List<Pick> out = new ArrayList<>();
        boolean anime = Hikari.KIND.equals(kind);
        List<Pick> from = anime ? own.get(Hikari.KIND) : picks;
        if (from == null || (anime && !Hikari.configured()) || (!anime && !hasKey(context))) return out;
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : from) {
            if (out.size() >= MAX_OWN) break;
            if (!section.equals(p.section) || shown.contains(key(p)) || (!anime && kind != null && !kind.equals(p.kind))) continue;
            if (!anime && !p.detailed) continue;
            boolean streams = p.pkg != null && have.computeIfAbsent(p.pkg, this::installed);
            if (!anime && (EVERYWHERE.equals(section) ? streams : !streams)) continue;
            out.add(p);
        }
        return out;
    }

    /** The Jellyfin item for a TMDB title (movie or show) if it's in your library, else null. */
    String libraryItem(Pick p) {
        if (p == null || p.itemId != null || !("movie".equals(p.kind) || "tv".equals(p.kind))) return null;
        return library.get(p.kind + ":" + p.id);
    }

    /** Your library by TMDB id, every 10 minutes (the "In your library" check costs nothing on the UI thread). */
    private void refreshLibrary() {
        long now = System.currentTimeMillis();
        if (!Jellyfin.configured() || (now - libraryAt < 10 * 60_000L && now >= libraryAt)) return;
        libraryAt = now;
        posterIo.execute(() -> {
            try {
                Map<String, String> map = new HashMap<>();
                for (String[] item : Jellyfin.library()) {
                    if (!item[7].isEmpty()) {
                        map.put(("Movie".equals(item[8]) ? "movie:" : "tv:") + item[7], item[0] + "/" + item[8]);
                    }
                }
                library = map;
            } catch (Exception e) {
                AppLog.w("jellyfin library: " + e.getMessage());
            }
        });
    }

    /**
     * The title's poster for the smaller cards (cut from its image, already at the focused size), or null: loads it in
     * the background and notifies when ready. The 48 most recently drawn stay in memory (GPU).
     */
    Bitmap poster(Pick p) {
        if (p == null || p.backdrop == null) return null;
        String key = posterKey(p);
        synchronized (posters) {
            Bitmap b = posters.get(key);
            if (b != null) return b;
            if (!posterPending.add(key)) return null;
        }
        posterIo.execute(() -> {
            try {
                File f = posterFile(p);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), bounds);
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 1;
                // Keep at least twice the poster's height before cutting (a 16:9 photo loses its sides).
                while (bounds.outHeight / (opts.inSampleSize * 2) >= POSTER_H * 2) opts.inSampleSize *= 2;
                Bitmap src = BitmapFactory.decodeFile(f.getPath(), opts);
                if (src == null) throw new IOException("unreadable image");
                float ratio = (float) POSTER_W / POSTER_H;
                int cw = Math.min(src.getWidth(), Math.round(src.getHeight() * ratio));
                int ch = Math.min(src.getHeight(), Math.round(cw / ratio));
                Bitmap cut = Bitmap.createBitmap(src, (src.getWidth() - cw) / 2, (src.getHeight() - ch) / 2, cw, ch);
                Bitmap out = Bitmap.createScaledBitmap(cut, POSTER_W, POSTER_H, true);
                if (cut != src && cut != out) cut.recycle();
                if (src != out) src.recycle();
                Bitmap gpu = Backdrop.gpu(out);
                synchronized (posters) {
                    posters.put(key, gpu);
                    while (posters.size() > 48) posters.remove(posters.keySet().iterator().next());
                }
                notifyListeners();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                AppLog.w("poster: " + e.getMessage());
            } finally {
                synchronized (posters) { posterPending.remove(key); }
            }
        });
        return null;
    }

    /**
     * A smaller copy of the title's image for its poster (TMDB's 780-wide, Jellyfin's at 780), in its own folder of
     * up to 150, so the rows don't push the large images out.
     */
    /** TMDB's real poster when it has one, else a cut of the title's image. */
    private static String posterKey(Pick p) {
        return p.poster != null ? "poster" + p.poster.replace("/", "") : p.backdrop.replace("/", "");
    }

    private File posterFile(Pick p) throws IOException {
        File dir = new File(context.getCacheDir(), "posters");
        File f = new File(dir, posterKey(p));
        if (f.exists()) return f;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        HttpURLConnection c = p.poster != null ? (HttpURLConnection) new URL(IMAGES_POSTER + p.poster).openConnection()
                : p.image == null ? (HttpURLConnection) new URL(IMAGES_SMALL + p.backdrop).openConnection()
                : p.image.startsWith("https://") ? (HttpURLConnection) new URL(p.image).openConnection()
                : Jellyfin.open(p.image.replace("maxWidth=1920", "maxWidth=780"));
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        File part = new File(f.getPath() + ".part");
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the poster");
        prune(dir, 150);
        return f;
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        String user = Prefs.jellyfinUser().toLowerCase(Locale.ROOT) + "/" + Prefs.cards();
        if (Jellyfin.configured()) refreshOwn(Jellyfin.KIND, user, 60_000L, Jellyfin::fetch);
        if (Hikari.configured()) refreshOwn(Hikari.KIND, user, 5 * 60_000L, Hikari::fetch);
        refreshLibrary();
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                // The rows below the first need the details of the rest of the list too (which app streams it).
                for (Pick pick : picks) {
                    if (pick.detailed) continue;
                    try {
                        fetchDetails(pick);
                    } catch (Exception ignored) {
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /**
     * One of your own lists, every `every` ms at most (they change as you watch), and right away when `forWhom` (the
     * Jellyfin user and the card count) changed.
     */
    private void refreshOwn(String kind, String forWhom, long every, Fetch fetch) {
        long now = System.currentTimeMillis(), at = ownAt.containsKey(kind) ? ownAt.get(kind) : 0;
        if (forWhom.equals(ownFor.get(kind)) && now - at < every && now >= at) return;
        if (!ownFetching.add(kind)) return;
        ownAt.put(kind, now);
        io.execute(() -> {
            try {
                List<Pick> list = fetch.run(Prefs.cards());
                ownFor.put(kind, forWhom);
                for (Pick p : list) {
                    try {
                        download(p);
                    } catch (IOException e) {
                        AppLog.w(kind + " image: " + e.getMessage());
                    }
                }
                List<Pick> before = own.get(kind);
                own.put(kind, list);
                cache.edit().putString(kind, Jellyfin.save(list).toString()).apply();
                if (before == null || !sameList(list, before)) notifyListeners();
            } catch (Exception e) {
                AppLog.w(kind + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            } finally {
                ownFetching.remove(kind);
            }
        });
    }

    private static boolean sameList(List<Pick> a, List<Pick> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!key(a.get(i)).equals(key(b.get(i))) || !a.get(i).meta.equals(b.get(i).meta)
                    || !a.get(i).backdrop.equals(b.get(i).backdrop)) return false;
        }
        return true;
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        if (p.itemId != null) return p.kind + ":" + p.itemId;
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException("none of the TV's streaming services are on TMDB");
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        List<Pick> elseMovies = new ArrayList<>(), elseShows = new ArrayList<>();   // trending, on none of the TV's apps
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                pick.section = pick.pkg != null ? TRENDING : EVERYWHERE;
                (pick.pkg != null ? (kind.equals("movie") ? hotMovies : hotShows)
                        : (kind.equals("movie") ? elseMovies : elseShows)).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        for (Pick pick : newMovies) pick.section = NEW;
        for (Pick pick : newShows) pick.section = NEW;
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int max = Math.max(24, 4 * Prefs.cards());   // enough for every tab's cards to change each hour
        alternate(list, seen, hotMovies, hotShows, max);
        alternate(list, seen, newMovies, newShows, max);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        alternate(list, seen, elseMovies, elseShows, list.size() + 2 * MAX_OWN);
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** Language of titles and genres; the services come from the region. */
    private static String lang() {
        if (Ui.es) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        return Ui.en ? "en-US" : "pt-BR";
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        String poster = o.optString("poster_path", "");
        p.poster = poster.isEmpty() || "null".equals(poster) ? null : poster;
        list.add(p);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language + ",en");
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Série", "Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(Ui.en ? name : genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0 && Ui.en) parts.add(minutes >= 60 ? (minutes / 60) + "h " + (minutes % 60) + "m" : minutes + "m");
            else if (minutes > 0) parts.add(minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min");
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0 && Ui.en) parts.add(seasons == 1 ? "1 season" : seasons + " seasons");
            else if (seasons > 0) parts.add(seasons == 1 ? "1 temporada" : seasons + " temporadas");
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /** TV genres that TMDB returns in English even in pt-BR. */
    private static final String[][] GENRES_PT = {
        {"Action & Adventure", "Ação e Aventura"}, {"Sci-Fi & Fantasy", "Ficção Científica e Fantasia"},
        {"War & Politics", "Guerra e Política"}, {"Kids", "Infantil"}, {"News", "Notícias"}, {"Soap", "Novela"},
        {"Talk", "Entrevistas"},
    };
    private static final String[][] GENRES_ES = {
        {"Action & Adventure", "Acción y Aventura"}, {"Sci-Fi & Fantasy", "Ciencia ficción y Fantasía"},
        {"War & Politics", "Guerra y Política"}, {"Kids", "Infantil"}, {"News", "Noticias"},
        {"Soap", "Telenovela"}, {"Talk", "Entrevistas"},
    };

    private static String genre(String name) {
        for (String[] g : Ui.es ? GENRES_ES : GENRES_PT) if (g[0].equalsIgnoreCase(name)) return g[1];
        return name;
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), at the highest resolution
     * (up to 3840, TMDB's 4K) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 3840) * 100 + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    private static String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String[] s : SERVICES) {
            if (s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0])) return s;
        }
        return null;
    }

    private boolean installed(String pkg) {
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode();
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (originals on disk, enough for the whole list; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = p.image != null && p.image.startsWith("https://") ? (HttpURLConnection) new URL(p.image).openConnection()
                : p.image != null ? Jellyfin.open(p.image)
                : (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile(), 24 + 4 * Prefs.cards());
    }

    /** Reads the original (≥ 1920 wide) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                // The light design keeps every tab's cards (three per tab) ready, the "Continue" tab's too.
                // The current tab's cards (up to 12 on the anime tab) and its neighbors' first three.
                int max = forLight ? Math.max(Prefs.cards(), MAX_OWN) + 7 : 4;
                while (images.size() > max) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** Keeps the `keep` newest images (every tab's cards fit, so they aren't downloaded again and again). */
    private static void prune(File dir, int keep) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keep) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - keep; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed).put("section", p.section == null ? "" : p.section)
                        .put("poster", p.poster == null ? "" : p.poster));
            }
            cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.section = o.optString("section").isEmpty() ? null : o.optString("section");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
