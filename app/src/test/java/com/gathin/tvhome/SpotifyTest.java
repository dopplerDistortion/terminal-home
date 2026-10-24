package com.gathin.tvhome;

import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Spotify's album objects as card items, and the for-you feed made of them. The JSON walk (Spotify.albums) needs org.json, which the JVM tests
 *  only have as Android stubs, so the tests start from the album's fields. */
public class SpotifyTest {

    private static final String ART = "https://i.scdn.co/image/ab67616d0000b273aaaa";

    @Test
    public void albumBecomesAnItemWithChipMetaAndDeepLink() {
        Trending.Item it = Spotify.album("4aawyAB9vmqN3uQ7FjRGTy", "Global Warming", "album", 18, "2012-11-16",
                Arrays.asList("Pitbull", "Christina Aguilera"), ART);
        assertEquals(Trending.SPOTIFY, it.pkg);
        assertEquals("Global Warming", it.title);
        assertEquals("Album", it.kind);
        assertEquals("2012", it.year);
        assertEquals("Pitbull, Christina Aguilera · 2012 · 18 tracks", it.meta);
        assertEquals("", it.overview);
        assertEquals(ART, it.backdrop);
        assertEquals("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy", it.uri);
        assertFalse(it.forYou);
        assertNull(it.youtubeKey);
    }

    @Test
    public void singleSaysOneTrack() {
        Trending.Item it = Spotify.album("id1", "Outfit", "single", 1, "2026-10-23", Collections.singletonList("La Rvfleuze"), ART);
        assertEquals("Single", it.kind);
        assertEquals("La Rvfleuze · 2026 · 1 track", it.meta);
    }

    @Test
    public void moreThanTwoArtistsAreCounted() {
        Trending.Item it = Spotify.album("id1", "Jailer 2", "album", 6, "2026-10-20",
                Arrays.asList("Anirudh Ravichander", "Deepak Ram", "Super Subu", "Ritesh G Rao"), ART);
        assertEquals("Anirudh Ravichander, Deepak Ram +2 · 2026 · 6 tracks", it.meta);
    }

    @Test
    public void compilationCountsAsAlbum() {
        assertEquals("Album", Spotify.album("id1", "Hits", "compilation", 20, "2026", Collections.emptyList(), ART).kind);
    }

    @Test
    public void releaseDatePrecisionYearOrMonth() {
        assertEquals("2026", Spotify.album("id1", "A", "album", 3, "2026", Collections.singletonList("X"), ART).year);
        assertEquals("2026", Spotify.album("id1", "A", "album", 3, "2026-10", Collections.singletonList("X"), ART).year);
    }

    @Test
    public void missingPartsAreLeftOutOfMeta() {
        assertEquals("2026", Spotify.album("id1", "A", "album", 0, "2026-10-01", Collections.emptyList(), ART).meta);
        assertEquals("X · 4 tracks", Spotify.album("id1", "A", "album", 4, "", Collections.singletonList("X"), ART).meta);
        assertEquals("", Spotify.album("id1", "A", "album", 4, "", Collections.emptyList(), ART).year);
    }

    @Test
    public void noIdNameOrArtworkMeansNoItem() {
        assertNull(Spotify.album("", "A", "album", 3, "2026", Collections.singletonList("X"), ART));
        assertNull(Spotify.album("id1", "", "album", 3, "2026", Collections.singletonList("X"), ART));
        assertNull(Spotify.album("id1", "A", "album", 3, "2026", Collections.singletonList("X"), ""));
    }

    @Test
    public void keysMustBothBeSet() {
        assertTrue(Spotify.configured("abc", "def"));
        assertFalse(Spotify.configured("", "def"));
        assertFalse(Spotify.configured("abc", " "));
    }

    // ---- for you ---------------------------------------------------------------------------------

    private static final long NOW = Instant.parse("2026-10-24T18:00:00Z").toEpochMilli();
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static Spotify.Album al(String id, String date, String artist) {
        Spotify.Album a = new Spotify.Album();
        a.id = id;
        a.name = "Album " + id;
        a.type = "album";
        a.tracks = 9;
        a.date = date;
        a.artists = new ArrayList<>(Collections.singletonList(artist.isEmpty() ? "Someone" : artist));
        a.image = ART + id;
        a.artist = artist;
        return a;
    }

    private static Spotify.Album played(String id, long agoMs) {
        Spotify.Album a = al(id, "2019-01-01", "");
        a.playedAt = NOW - agoMs;
        return a;
    }

    private static List<String> ids(List<Trending.Item> items) {
        List<String> out = new ArrayList<>();
        for (Trending.Item it : items) out.add(it.uri.substring(it.uri.lastIndexOf('/') + 1));
        return out;
    }

    @Test
    public void newReleasesOfTheLastEightWeeksComeFirstNewestFirst() {
        List<Spotify.Album> fresh = Arrays.asList(
                al("old", "2026-08-28", "Haken"),        // 57 days: too old
                al("mid", "2026-09-20", "Haken"),
                al("edge", "2026-08-29", "Opeth"),       // 56 days: still new
                al("last", "2026-10-23", "Soen"),
                al("future", "2026-11-06", "Soen"),      // pre-release
                al("vague", "2026-10", "Soen"));         // month precision only
        List<Trending.Item> out = Spotify.forYou(fresh, Collections.emptyList(), Collections.emptyList(), NOW);
        assertEquals(Arrays.asList("last", "mid", "edge"), ids(out));
        assertEquals("New from Soen", out.get(0).kind);
        assertTrue(out.get(0).forYou);
    }

    @Test
    public void jumpBackInSkipsAlbumsPlayedThisWeek() {
        List<Spotify.Album> recent = Arrays.asList(played("week", 2 * DAY), played("month", 20 * DAY));
        List<Spotify.Album> top = Arrays.asList(al("week", "2019-01-01", ""), al("top", "2018-01-01", ""));
        List<Trending.Item> out = Spotify.forYou(Collections.emptyList(), recent, top, NOW);
        assertEquals(Arrays.asList("month", "top"), ids(out));
        assertEquals("Jump back in", out.get(0).kind);
    }

    @Test
    public void oneSlideAnAlbumAcrossAllSources() {
        // Two artists on one album, and the same album in recently played and top tracks.
        List<Spotify.Album> fresh = Arrays.asList(al("duet", "2026-10-10", "Haken"), al("duet", "2026-10-10", "Opeth"));
        List<Spotify.Album> recent = Arrays.asList(played("duet", 30 * DAY), played("a", 30 * DAY), played("a", 40 * DAY));
        List<Spotify.Album> top = Arrays.asList(al("a", "2019-01-01", ""), al("b", "2019-01-01", ""));
        List<Trending.Item> out = Spotify.forYou(fresh, recent, top, NOW);
        assertEquals(Arrays.asList("duet", "a", "b"), ids(out));
        assertEquals("New from Haken", out.get(0).kind);
    }

    @Test
    public void tenAtMostNewFirst() {
        List<Spotify.Album> fresh = new ArrayList<>();
        for (int i = 1; i <= 7; i++) fresh.add(al("n" + i, String.format("2026-10-%02d", i), "Haken"));
        List<Spotify.Album> top = new ArrayList<>();
        for (int i = 1; i <= 6; i++) top.add(al("t" + i, "2019-01-01", ""));
        List<Trending.Item> out = Spotify.forYou(fresh, Collections.emptyList(), top, NOW);
        assertEquals(Arrays.asList("n7", "n6", "n5", "n4", "n3", "n2", "n1", "t1", "t2", "t3"), ids(out));
    }

    @Test
    public void albumsWithoutArtworkAreSkippedNotCounted() {
        Spotify.Album bare = al("bare", "2026-10-20", "Haken");
        bare.image = "";
        List<Trending.Item> out = Spotify.forYou(Collections.singletonList(bare), Collections.emptyList(),
                Collections.singletonList(al("t", "2019-01-01", "")), NOW);
        assertEquals(Collections.singletonList("t"), ids(out));
    }

    @Test
    public void nothingToShowGivesAnEmptyFeed() {
        assertTrue(Spotify.forYou(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), NOW).isEmpty());
    }
}
