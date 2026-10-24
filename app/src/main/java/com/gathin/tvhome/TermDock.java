package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The terminal home's dock order: the default, the saved order read back, and pins. The four apps with a feed on
 * the trending card (YouTube, Apple TV, Nuvio, Spotify) hold slots 01-04; a pinned app goes in at 05.
 */
final class TermDock {
    private TermDock() { }

    static final int APPS = 7;
    static final int FEED_SLOTS = 4;
    /** The saved order's format; 2 since Spotify, with a feed of its own, moved up to slot 04. */
    static final int VERSION = 2;
    static final String KICK = "com.kick.mobile";
    static final String YT_MUSIC = "com.google.android.youtube.tvmusic";
    static final String PRIME = "com.amazon.amazonvideo.livingroom";
    static final List<String> DEFAULT = Arrays.asList(Trending.YOUTUBE, Trending.APPLE_TV, Trending.NUVIO,
            Trending.SPOTIFY, YT_MUSIC, PRIME, KICK);

    /** The dock from the saved "a,b,c" order (of format {@code version}), topped up from the default. */
    static List<String> load(String saved, int version) {
        List<String> out = new ArrayList<>();
        for (String p : saved.split(",")) if (LibraryApps.validPackage(p) && !out.contains(p)) out.add(p);
        if (!out.isEmpty() && version < 2) out = spotifyToSlot4(out);
        for (String p : DEFAULT) if (out.size() < APPS && !out.contains(p)) out.add(p);
        return new ArrayList<>(out.subList(0, APPS));
    }

    /**
     * Version 1 to 2: Spotify goes to slot 04, back in if a pin had pushed it out. Kick, the old default's 04,
     * takes Spotify's old place (the end when it was gone); the rest keep their order, the last one making room.
     */
    static List<String> spotifyToSlot4(List<String> v1) {
        List<String> out = new ArrayList<>(v1);
        int spotifyAt = out.indexOf(Trending.SPOTIFY);
        boolean kickAt4 = out.size() > 3 && KICK.equals(out.get(3));
        out.remove(Trending.SPOTIFY);
        if (kickAt4) out.remove(KICK);
        out.add(Math.min(3, out.size()), Trending.SPOTIFY);
        if (kickAt4) out.add(spotifyAt < 0 ? out.size() : Math.min(spotifyAt, out.size()), KICK);
        return out.size() > APPS ? new ArrayList<>(out.subList(0, APPS)) : out;
    }

    /** The dock with {@code pkg} pinned at slot 05, after the feed apps; the dock's last app makes room. */
    static List<String> pin(List<String> dock, String pkg) {
        if (dock.contains(pkg)) return dock;
        List<String> out = new ArrayList<>(dock);
        out.add(Math.min(FEED_SLOTS, out.size()), pkg);
        return new ArrayList<>(out.subList(0, Math.min(APPS, out.size())));
    }
}
