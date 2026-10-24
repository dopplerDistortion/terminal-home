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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Trending titles for the top-shelf carousel, from TMDB. The launcher's only network use: one refresh at most
 * every {@link #MAX_AGE_MS}, off the main thread, with the list and the backdrops kept in the cache directory.
 * Each item belongs to one of the three apps the shelf opens; none of them publishes a feed to a third-party
 * launcher, so TMDB stands in for all three.
 */
final class Trending {
    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final String NUVIO = "com.nuvio.app";
    static final String APPLE_TV = "com.apple.atve.androidtv.appletv";

    private static final long MAX_AGE_MS = 6L * 60 * 60 * 1000;
    private static final int PER_SOURCE = 4;
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
        Bitmap image;
    }

    interface Listener {
        void onItems(List<Item> items);
    }

    private final Context context;
    private final File listFile;
    private final File imageDir;
    private final String key;
    private final String region;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean loading;

    Trending(Context context, String key, String region) {
        this.context = context.getApplicationContext();
        this.key = key;
        this.region = region;
        listFile = new File(this.context.getCacheDir(), "trending.json");
        imageDir = new File(this.context.getCacheDir(), "trending");
    }

    boolean enabled() {
        return !key.isEmpty();
    }

    /** Hands over the cached list at once, then refreshes it first if it is old. */
    void load(Listener listener) {
        if (!enabled() || loading) return;
        loading = true;
        io.execute(() -> {
            List<Item> items = new ArrayList<>();
            try {
                boolean fresh = listFile.exists() && System.currentTimeMillis() - listFile.lastModified() < MAX_AGE_MS;
                if (!fresh) {
                    items = fetch();
                    if (!items.isEmpty()) write(items);
                }
                if (items.isEmpty() && listFile.exists()) items = read();
                for (Item item : items) item.image = image(item);
            } catch (Exception ignored) {
                // Offline or TMDB down: show the older list if there is one, otherwise no shelf.
                try { if (items.isEmpty() && listFile.exists()) items = read(); } catch (Exception e) { items = new ArrayList<>(); }
            }
            final List<Item> result = items;
            main.post(() -> {
                loading = false;
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
            Item item = item(m, YOUTUBE, "Trending trailer");
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
            Item item = item(o, NUVIO, type.equals("tv") ? "Trending series" : "Trending movie");
            if (item == null) continue;
            used.add(o.getInt("id"));
            nuvio.add(item);
        }
        List<Item> apple = new ArrayList<>();
        JSONArray shows = get("/discover/tv", "&with_watch_providers=" + APPLE_TV_PROVIDER + "&watch_region=" + region
                + "&sort_by=popularity.desc").getJSONArray("results");
        for (int i = 0; i < shows.length() && apple.size() < PER_SOURCE; i++) {
            Item item = item(shows.getJSONObject(i), APPLE_TV, "Popular on Apple TV+");
            if (item != null) apple.add(item);
        }
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < PER_SOURCE; i++) {
            if (i < youtube.size()) out.add(youtube.get(i));
            if (i < nuvio.size()) out.add(nuvio.get(i));
            if (i < apple.size()) out.add(apple.get(i));
        }
        return out;
    }

    private Item item(JSONObject o, String pkg, String kind) {
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
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(f)) {
                    out.write(readAll(in));
                } finally {
                    c.disconnect();
                }
            }
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
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
                    .put("overview", i.overview).put("backdrop", i.backdrop).put("yt", i.youtubeKey == null ? "" : i.youtubeKey));
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
            items.add(i);
        }
        return items;
    }
}
