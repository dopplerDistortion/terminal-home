package com.gathin.tvhome;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Spotify tile's feed on the trending card, from the Spotify Web API. Called on Trending's own thread;
 * Trending keeps the list and the artwork.
 *
 * With a user's refresh token (tools/spotify-login.py) the feed is "for you": new albums and singles from the
 * user's top and followed artists, then albums to jump back into. Spotify's own picks (/recommendations,
 * Discover Weekly and the other Spotify playlists) are closed to new apps since November 2024, so the feed is
 * made here from what is open. Without the token, or when it fails, the feed is new releases in one market from
 * the app's own Client Credentials token: search for tag:new, because GET /browse/new-releases was removed in
 * the February 2026 Web API changes.
 */
final class Spotify {
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String API = "https://api.spotify.com/v1";
    private static final int ITEMS = 10;
    /** "New" is a release in the last eight weeks. */
    static final long NEW_DAYS = 56;
    /** An album played in the last week needs no reminder. */
    static final long PLAYED_RECENTLY_MS = 7L * 24 * 60 * 60 * 1000;
    /** Top and followed artists asked for their releases: one request each, every refresh (6 h). */
    private static final int MAX_ARTISTS = 40;
    /** Asks for a new token this long before Spotify's expiry, so a slow request never carries a dead one. */
    private static final long TOKEN_SLACK_MS = 60_000;
    private static final String PREF_REFRESH = "spotify_refresh_token";
    private static final String PREF_REFRESH_FROM = "spotify_refresh_from";

    /** An album with the reason it is in the feed. */
    static final class Album {
        String id = "";
        String name = "";
        String type = "";
        String date = "";
        int tracks;
        List<String> artists = new ArrayList<>();
        String image = "";
        /** The top or followed artist the album was found for; empty for jump-back-in albums. */
        String artist = "";
        /** When the user last played it (recently played), or 0. */
        long playedAt;
    }

    private final String id;
    private final String secret;
    private final String bakedRefresh;
    private final String market;
    private final SharedPreferences prefs;
    private String appToken;
    private long appTokenUntil;
    private String userToken;
    private long userTokenUntil;

    /** prefs keeps a refresh token Spotify rotates; null keeps it in memory only. */
    Spotify(String id, String secret, String refreshToken, String market, SharedPreferences prefs) {
        this.id = id.trim();
        this.secret = secret.trim();
        this.bakedRefresh = refreshToken.trim();
        this.market = market;
        this.prefs = prefs;
    }

    static boolean configured(String id, String secret) {
        return !id.trim().isEmpty() && !secret.trim().isEmpty();
    }

    boolean enabled() {
        return configured(id, secret);
    }

    List<Trending.Item> fetch() throws Exception {
        if (!enabled()) return new ArrayList<>();
        if (!refreshToken().isEmpty()) {
            try {
                List<Trending.Item> mine = forYou();
                if (!mine.isEmpty()) return mine;
            } catch (Exception ignored) {
                // A revoked sign-in or a scope missing: new releases instead, until the next refresh.
            }
        }
        return albums(get(API + "/search?q=tag%3Anew&type=album&limit=" + ITEMS + "&market=" + market, appToken()));
    }

    // ---- for you -----------------------------------------------------------------------------------

    private List<Trending.Item> forYou() throws Exception {
        String token = userToken();
        Map<String, String> artists = new LinkedHashMap<>();
        JSONArray top = get(API + "/me/top/artists?time_range=medium_term&limit=20", token).getJSONArray("items");
        for (int i = 0; i < top.length() && artists.size() < MAX_ARTISTS; i++) {
            artists.put(top.getJSONObject(i).getString("id"), top.getJSONObject(i).optString("name"));
        }
        JSONArray followed = get(API + "/me/following?type=artist&limit=50", token).getJSONObject("artists").getJSONArray("items");
        for (int i = 0; i < followed.length() && artists.size() < MAX_ARTISTS; i++) {
            artists.putIfAbsent(followed.getJSONObject(i).getString("id"), followed.getJSONObject(i).optString("name"));
        }
        List<Album> fresh = new ArrayList<>();
        for (Map.Entry<String, String> a : artists.entrySet()) {
            // 10 is this endpoint's largest page since February 2026; it lists the newest first.
            JSONArray items = get(API + "/artists/" + a.getKey() + "/albums?include_groups=album,single&limit=10&market=" + market, token)
                    .getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                Album al = album(items.getJSONObject(i));
                al.artist = a.getValue();
                fresh.add(al);
            }
        }
        List<Album> recent = new ArrayList<>();
        JSONArray played = get(API + "/me/player/recently-played?limit=50", token).getJSONArray("items");
        for (int i = 0; i < played.length(); i++) {
            JSONObject p = played.getJSONObject(i);
            Album al = album(p.getJSONObject("track").getJSONObject("album"));
            al.playedAt = Instant.parse(p.getString("played_at")).toEpochMilli();
            recent.add(al);
        }
        List<Album> topAlbums = new ArrayList<>();
        JSONArray tracks = get(API + "/me/top/tracks?time_range=medium_term&limit=20", token).getJSONArray("items");
        for (int i = 0; i < tracks.length(); i++) topAlbums.add(album(tracks.getJSONObject(i).getJSONObject("album")));
        return forYou(fresh, recent, topAlbums, System.currentTimeMillis());
    }

    /**
     * The for-you feed, ten deep: first the releases of the last {@link #NEW_DAYS} days from the user's artists,
     * newest first ("New from …"); then albums from recently played and top tracks that were not played in the
     * last week ("Jump back in"), recently played first. One slide an album.
     */
    static List<Trending.Item> forYou(List<Album> fresh, List<Album> recent, List<Album> top, long now) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate since = today.minusDays(NEW_DAYS);
        List<Album> newest = new ArrayList<>();
        for (Album a : fresh) {
            LocalDate d = day(a.date);
            if (d != null && !d.isBefore(since) && !d.isAfter(today)) newest.add(a);
        }
        // Stable: on one day, the user's top artists before the followed ones.
        newest.sort(Comparator.comparing((Album a) -> day(a.date)).reversed());

        Set<String> playedLastWeek = new HashSet<>();
        for (Album a : recent) if (now - a.playedAt < PLAYED_RECENTLY_MS) playedLastWeek.add(a.id);

        List<Trending.Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Album a : newest) add(out, seen, a, "New from " + a.artist);
        for (Album a : recent) if (!playedLastWeek.contains(a.id)) add(out, seen, a, "Jump back in");
        for (Album a : top) if (!playedLastWeek.contains(a.id)) add(out, seen, a, "Jump back in");
        return out;
    }

    private static void add(List<Trending.Item> out, Set<String> seen, Album a, String why) {
        if (out.size() >= ITEMS || seen.contains(a.id)) return;
        Trending.Item item = album(a.id, a.name, a.type, a.tracks, a.date, a.artists, a.image);
        if (item == null) return;
        item.kind = why;
        item.forYou = true;
        seen.add(a.id);
        out.add(item);
    }

    /** A release date at day precision, or null for "2026" or "2026-10" (too vague to call new). */
    static LocalDate day(String date) {
        if (date == null || date.length() != 10) return null;
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- new releases --------------------------------------------------------------------------------

    /** The items of a search response; albums without an id, a name or artwork are left out. */
    static List<Trending.Item> albums(JSONObject response) {
        List<Trending.Item> out = new ArrayList<>();
        JSONObject albums = response.optJSONObject("albums");
        JSONArray arr = albums == null ? null : albums.optJSONArray("items");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Album a = album(o);
            Trending.Item item = album(a.id, a.name, a.type, a.tracks, a.date, a.artists, a.image);
            if (item != null) out.add(item);
        }
        return out;
    }

    private static Album album(JSONObject o) {
        Album a = new Album();
        a.id = o.optString("id", "");
        a.name = o.optString("name", "");
        a.type = o.optString("album_type", "");
        a.tracks = o.optInt("total_tracks", 0);
        a.date = o.optString("release_date", "");
        JSONArray ar = o.optJSONArray("artists");
        for (int n = 0; ar != null && n < ar.length(); n++) {
            JSONObject artist = ar.optJSONObject(n);
            String name = artist == null ? "" : artist.optString("name", "");
            if (!name.isEmpty()) a.artists.add(name);
        }
        JSONArray images = o.optJSONArray("images");
        JSONObject img = images == null ? null : images.optJSONObject(0);
        a.image = img == null ? "" : img.optString("url", "");
        return a;
    }

    /**
     * One album as a card item: chip ALBUM or SINGLE (a compilation counts as an album), meta "artists · year ·
     * N tracks" with two artists at most and "+N" for the rest (a soundtrack credits a dozen), no overview.
     * Null when the album has no id, name or artwork, the card's three must-haves.
     */
    static Trending.Item album(String id, String name, String type, int tracks, String date, List<String> artists, String image) {
        if (id.isEmpty() || name.isEmpty() || image.isEmpty()) return null;
        Trending.Item item = new Trending.Item();
        item.pkg = Trending.SPOTIFY;
        item.title = name;
        item.kind = "single".equals(type) ? "Single" : "Album";
        item.year = date.length() >= 4 ? date.substring(0, 4) : "";
        StringBuilder meta = new StringBuilder(String.join(", ", artists.subList(0, Math.min(2, artists.size()))));
        if (artists.size() > 2) meta.append(" +").append(artists.size() - 2);
        if (!item.year.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append(item.year);
        if (tracks > 0) meta.append(meta.length() > 0 ? " · " : "").append(tracks).append(tracks == 1 ? " track" : " tracks");
        item.meta = meta.toString();
        item.overview = "";
        item.backdrop = image;
        // The web link, not spotify:album:…: Spotify's TV app opens the album page for it, and only loads the
        // album into its player for the URI.
        item.uri = "https://open.spotify.com/album/" + id;
        return item;
    }

    // ---- tokens --------------------------------------------------------------------------------------

    /**
     * The newest refresh token: the one Spotify last handed over (kept in the app's own storage, as it may
     * rotate them), unless a later build carries a different one from a new sign-in. Never logged.
     */
    private String refreshToken() {
        if (prefs != null && Integer.toHexString(bakedRefresh.hashCode()).equals(prefs.getString(PREF_REFRESH_FROM, ""))) {
            String kept = prefs.getString(PREF_REFRESH, "");
            if (!kept.isEmpty()) return kept;
        }
        return bakedRefresh;
    }

    private String userToken() throws Exception {
        if (userToken != null && System.currentTimeMillis() < userTokenUntil) return userToken;
        // A PKCE sign-in: the refresh goes with the client id and no secret.
        JSONObject o = token("grant_type=refresh_token&client_id=" + id + "&refresh_token="
                + URLEncoder.encode(refreshToken(), "UTF-8"), null);
        String rotated = o.optString("refresh_token", "");
        if (!rotated.isEmpty() && prefs != null) {
            prefs.edit().putString(PREF_REFRESH, rotated)
                    .putString(PREF_REFRESH_FROM, Integer.toHexString(bakedRefresh.hashCode())).apply();
        }
        userToken = o.getString("access_token");
        userTokenUntil = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - TOKEN_SLACK_MS;
        return userToken;
    }

    /** The app's own token (Client Credentials), kept in memory until shortly before it expires. */
    private String appToken() throws Exception {
        if (appToken != null && System.currentTimeMillis() < appTokenUntil) return appToken;
        String basic = Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
        JSONObject o = token("grant_type=client_credentials", basic);
        appToken = o.getString("access_token");
        appTokenUntil = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - TOKEN_SLACK_MS;
        return appToken;
    }

    private static JSONObject token(String body, String basic) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(TOKEN_URL).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            if (basic != null) c.setRequestProperty("Authorization", "Basic " + basic);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject get(String url, String token) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Authorization", "Bearer " + token);
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
        return buf.toByteArray();
    }
}
