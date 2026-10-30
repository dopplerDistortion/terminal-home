package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Trending titles for the top-shelf carousel, from TMDB. The launcher's only network use: one refresh at most
 * every {@link #MAX_AGE_MS}, off the main thread, with the list and the backdrops kept in the cache directory.
 * Each item belongs to one of the apps the shelf opens; none of the three video apps publishes a feed to a
 * third-party launcher, so TMDB stands in for them. Spotify's feed comes from its own Web API (Spotify), in the
 * same list and cache, and only when its keys are set.
 */
final class Trending {
    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final String NUVIO = "com.nuvio.app";
    static final String APPLE_TV = "com.apple.atve.androidtv.appletv";
    static final String SPOTIFY = "com.spotify.tv.android";

    private static final long MAX_AGE_MS = 6L * 60 * 60 * 1000;
    // Ten a source: the home card pages through one app's feed, ten slides deep.
    private static final int PER_SOURCE = 10;
    private static final String API = "https://api.themoviedb.org/3";
    private static final String IMG = "https://image.tmdb.org/t/p/w780";
    private static final int APPLE_TV_PROVIDER = 350;

    static final class Item {
        String pkg;
        String title;
        String meta;
        String overview;
        String backdrop;
        String youtubeKey;
        /** The link that opens the item in its app (a Spotify album page), or null. */
        String uri;
        /** Picked for the signed-in user (Spotify's for-you feed), not a public chart. */
        boolean forYou;
        Bitmap image;
        // The parts of meta, kept apart for the terminal card's chip and meta line. Empty when unknown.
        String kind = "";
        String year = "";
        String genre = "";
        double rating;
    }

    interface Listener {
        void onItems(List<Item> items);
    }

    private final Context context;
    private final File listFile;
    private final File imageDir;
    private final String key;
    private final String region;
    private final Spotify spotify;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean loading;
    private boolean force;
    /** The last list handed over is the old one or lacks pictures (offline, say just after the TV wakes). */
    private volatile boolean incomplete;
    /** The pictures of the last list handed over, by URL: a reload reuses them instead of losing them. */
    private volatile Map<String, Bitmap> shown = new HashMap<>();

    Trending(Context context, String key, String region, Spotify spotify) {
        this.context = context.getApplicationContext();
        this.key = key;
        this.region = region;
        this.spotify = spotify;
        // v4: Spotify's albums, their links and the for-you mark; older lists are fetched again once.
        listFile = new File(this.context.getCacheDir(), "trending-v4.json");
        imageDir = new File(this.context.getCacheDir(), "trending");
    }

    boolean enabled() {
        return !key.isEmpty();
    }

    /** When the cached list was fetched, or 0 when there is none. */
    long cachedAt() {
        return listFile.exists() ? listFile.lastModified() : 0L;
    }

    /**
     * True when the last list was the cached one after a failed fetch, or some of its pictures could not be
     * loaded. The network coming back is then worth another try (MainActivity's network callback).
     */
    boolean incomplete() {
        return incomplete;
    }

    /** Like load, but fetches from TMDB and Spotify even when the cached list is still fresh. */
    void refresh(Listener listener) {
        force = true;
        load(listener);
    }

    /** Hands over the cached list at once, then refreshes it first if it is old. */
    void load(Listener listener) {
        if (!enabled() || loading) return;
        loading = true;
        final boolean refetch = force;
        force = false;
        io.execute(() -> {
            List<Item> items = new ArrayList<>();
            boolean stale = false;
            try {
                boolean fresh = !refetch && listFile.exists() && System.currentTimeMillis() - listFile.lastModified() < MAX_AGE_MS;
                if (!fresh) {
                    items = fetch();
                    if (!items.isEmpty()) write(items);
                }
            } catch (Exception ignored) {
                // Offline or TMDB down (the Wi-Fi is often not back yet when the TV wakes): the older list.
                items = new ArrayList<>();
                stale = true;
            }
            try {
                if (items.isEmpty() && listFile.exists()) items = read();
            } catch (Exception e) {
                items = new ArrayList<>();
            }
            // Pictures for the cached list too: the files are in the cache directory and need no network. Before,
            // a failed fetch handed over the old list without any, and the card stayed blank until a refresh.
            Map<String, Bitmap> previous = shown;
            Map<String, Bitmap> now = new HashMap<>();
            boolean missing = false;
            for (Item item : items) {
                Bitmap reuse = previous.get(item.backdrop);
                item.image = reuse != null ? reuse : image(item);
                if (item.image == null) missing = true;
                else now.put(item.backdrop, item.image);
            }
            final List<Item> result = items;
            final boolean gaps = stale || missing;
            main.post(() -> {
                loading = false;
                shown = now;
                incomplete = gaps;
                listener.onItems(result);
            });
        });
    }

    private List<Item> fetch() throws Exception {
        List<Item> youtube = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        JSONArray movies = get("/trending/movie/week", "").getJSONArray("results");
        for (int i = 0; i < movies.length() && youtube.size() < PER_SOURCE; i++) {
            JSONObject m = movies.getJSONObject(i);
            String trailer = trailerKey(m.getInt("id"));
            if (trailer == null) continue;
            Item item = item(m, YOUTUBE, "Trending trailer", "Trailer");
            if (item == null) continue;
            item.youtubeKey = trailer;
            used.add(m.getInt("id"));
            youtube.add(item);
        }
        List<Item> nuvio = new ArrayList<>();
        JSONArray all = get("/trending/all/week", "").getJSONArray("results");
        for (int i = 0; i < all.length() && nuvio.size() < PER_SOURCE; i++) {
            JSONObject o = all.getJSONObject(i);
            String type = o.optString("media_type");
            if (!type.equals("movie") && !type.equals("tv") || used.contains(o.getInt("id"))) continue;
            Item item = item(o, NUVIO, type.equals("tv") ? "Trending series" : "Trending movie", type.equals("tv") ? "Series" : "Movie");
            if (item == null) continue;
            used.add(o.getInt("id"));
            nuvio.add(item);
        }
        List<Item> apple = new ArrayList<>();
        JSONArray shows = get("/discover/tv", "&with_watch_providers=" + APPLE_TV_PROVIDER + "&watch_region=" + region
                + "&sort_by=popularity.desc").getJSONArray("results");
        for (int i = 0; i < shows.length() && apple.size() < PER_SOURCE; i++) {
            Item item = item(shows.getJSONObject(i), APPLE_TV, "Popular on Apple TV+", "Series");
            if (item != null) apple.add(item);
        }
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < PER_SOURCE; i++) {
            if (i < youtube.size()) out.add(youtube.get(i));
            if (i < nuvio.size()) out.add(nuvio.get(i));
            if (i < apple.size()) out.add(apple.get(i));
        }
        // After the interleaved three, so the mixed feed (the first ten) stays TMDB's. Spotify down or refusing
        // the keys costs only its own tile.
        if (spotify != null && spotify.enabled()) {
            try {
                List<Item> albums = spotify.fetch();
                out.addAll(albums.subList(0, Math.min(PER_SOURCE, albums.size())));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private Item item(JSONObject o, String pkg, String kind, String shortKind) {
        String path = o.optString("backdrop_path", "");
        if (path.isEmpty() || path.equals("null")) return null;
        Item item = new Item();
        item.pkg = pkg;
        item.title = o.optString("title", o.optString("name", ""));
        String date = o.optString("release_date", o.optString("first_air_date", ""));
        double rating = o.optDouble("vote_average", 0);
        StringBuilder meta = new StringBuilder(kind);
        if (date.length() >= 4) meta.append("  •  ").append(date, 0, 4);
        if (rating > 0) meta.append("  •  ★ ").append(String.format(Locale.US, "%.1f", rating));
        item.meta = meta.toString();
        item.kind = shortKind;
        item.year = date.length() >= 4 ? date.substring(0, 4) : "";
        item.rating = rating;
        JSONArray genres = o.optJSONArray("genre_ids");
        if (genres != null && genres.length() > 0) item.genre = GENRES.getOrDefault(genres.optInt(0), "");
        item.overview = o.optString("overview", "");
        item.backdrop = IMG + path;
        return item.title.isEmpty() ? null : item;
    }

    private String trailerKey(int id) throws Exception {
        JSONArray videos = get("/movie/" + id + "/videos", "").getJSONArray("results");
        for (int i = 0; i < videos.length(); i++) {
            JSONObject v = videos.getJSONObject(i);
            if ("YouTube".equals(v.optString("site")) && "Trailer".equals(v.optString("type"))) return v.getString("key");
        }
        return null;
    }

    private JSONObject get(String path, String query) throws Exception {
        // A v4 read token is a JWT and goes in a header; a v3 key is a 32-character hex string and goes in the URL.
        boolean bearer = key.startsWith("eyJ");
        String url = API + path + "?language=" + Locale.getDefault().toLanguageTag() + query + (bearer ? "" : "&api_key=" + key);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    private Bitmap image(Item item) {
        try {
            if (!imageDir.exists() && !imageDir.mkdirs()) return null;
            File f = new File(imageDir, Integer.toHexString(item.backdrop.hashCode()) + ".jpg");
            if (!f.exists()) {
                HttpURLConnection c = (HttpURLConnection) new URL(item.backdrop).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    data = readAll(in);
                } finally {
                    c.disconnect();
                }
                // Written whole and then renamed: a download cut off halfway must not leave a file that
                // exists, so is never fetched again, and never decodes.
                File part = new File(imageDir, f.getName() + ".part");
                try (FileOutputStream out = new FileOutputStream(part)) {
                    out.write(data);
                }
                if (!part.renameTo(f)) {
                    part.delete();
                    return null;
                }
            }
            // 565: forty pictures stay around while Home runs, and a photo shows no difference without alpha.
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            // A broken file (an older build could leave one): drop it so the next load downloads it again.
            if (bm == null) f.delete();
            return bm;
        } catch (Exception e) {
            return null;
        }
    }

    /** TMDB's genre ids, movie and TV together (they share the numbers that overlap). English: the card shows
     *  them in capitals next to TMDB's own words, and one more request per refresh for the names is not worth it. */
    private static final Map<Integer, String> GENRES = new HashMap<>();
    static {
        Object[] g = {28, "Action", 12, "Adventure", 16, "Animation", 35, "Comedy", 80, "Crime", 99, "Documentary",
                18, "Drama", 10751, "Family", 14, "Fantasy", 36, "History", 27, "Horror", 10402, "Music", 9648, "Mystery",
                10749, "Romance", 878, "Sci-Fi", 10770, "TV Movie", 53, "Thriller", 10752, "War", 37, "Western",
                10759, "Action", 10762, "Kids", 10763, "News", 10764, "Reality", 10765, "Sci-Fi", 10766, "Soap",
                10767, "Talk", 10768, "War"};
        for (int i = 0; i < g.length; i += 2) GENRES.put((Integer) g[i], (String) g[i + 1]);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }

    private void write(List<Item> items) throws Exception {
        JSONArray arr = new JSONArray();
        for (Item i : items) {
            arr.put(new JSONObject().put("pkg", i.pkg).put("title", i.title).put("meta", i.meta)
                    .put("overview", i.overview).put("backdrop", i.backdrop).put("yt", i.youtubeKey == null ? "" : i.youtubeKey).put("uri", i.uri == null ? "" : i.uri).put("forYou", i.forYou)
                    .put("kind", i.kind).put("year", i.year).put("genre", i.genre).put("rating", i.rating));
        }
        try (FileOutputStream out = new FileOutputStream(listFile)) {
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private List<Item> read() throws Exception {
        JSONArray arr;
        try (InputStream in = new java.io.FileInputStream(listFile)) {
            arr = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
        }
        List<Item> items = new ArrayList<>();
        for (int n = 0; n < arr.length(); n++) {
            JSONObject o = arr.getJSONObject(n);
            Item i = new Item();
            i.pkg = o.getString("pkg");
            i.title = o.getString("title");
            i.meta = o.getString("meta");
            i.overview = o.getString("overview");
            i.backdrop = o.getString("backdrop");
            i.youtubeKey = o.getString("yt").isEmpty() ? null : o.getString("yt");
            i.uri = o.optString("uri").isEmpty() ? null : o.optString("uri");
            i.forYou = o.optBoolean("forYou");
            i.kind = o.optString("kind");
            i.year = o.optString("year");
            i.genre = o.optString("genre");
            i.rating = o.optDouble("rating", 0);
            items.add(i);
        }
        return items;
    }
}
