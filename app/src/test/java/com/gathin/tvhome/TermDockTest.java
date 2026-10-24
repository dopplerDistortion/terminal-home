package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** The terminal home's dock order: default, the version 1 order moved to version 2, and pins at slot 05. */
public class TermDockTest {

    private static final String YT = Trending.YOUTUBE, ATV = Trending.APPLE_TV, NV = Trending.NUVIO, SP = Trending.SPOTIFY;
    private static final String KK = TermDock.KICK, YM = TermDock.YT_MUSIC, PV = TermDock.PRIME;
    private static final String NETFLIX = "com.netflix.ninja", PLEX = "com.plexapp.android";

    private static String csv(String... p) {
        return String.join(",", p);
    }

    @Test
    public void defaultPutsTheFourFeedAppsFirst() {
        assertEquals(Arrays.asList(YT, ATV, NV, SP, YM, PV, KK), TermDock.load("", TermDock.VERSION));
        assertEquals(Arrays.asList(YT, ATV, NV, SP, YM, PV, KK), TermDock.load("", 1));
    }

    @Test
    public void oldDefaultSavedSwapsSpotifyAndKick() {
        assertEquals(Arrays.asList(YT, ATV, NV, SP, YM, PV, KK), TermDock.load(csv(YT, ATV, NV, KK, YM, PV, SP), 1));
    }

    @Test
    public void oldPinBringsSpotifyBackAtSlot4() {
        // A version 1 pin went in at 04 and pushed Spotify out of the dock.
        assertEquals(Arrays.asList(YT, ATV, NV, SP, NETFLIX, KK, YM), TermDock.load(csv(YT, ATV, NV, NETFLIX, KK, YM, PV), 1));
    }

    @Test
    public void oldOrderWithKickElsewhereMovesSpotifyOnly() {
        assertEquals(Arrays.asList(YT, ATV, NV, SP, NETFLIX, KK, YM), TermDock.load(csv(YT, ATV, NV, NETFLIX, KK, YM, SP), 1));
    }

    @Test
    public void versionTwoOrderIsKeptAsSaved() {
        List<String> saved = Arrays.asList(YT, ATV, NV, SP, NETFLIX, YM, PV);
        assertEquals(saved, TermDock.load(String.join(",", saved), 2));
    }

    @Test
    public void pinGoesInAtSlot5AndTheLastAppMakesRoom() {
        List<String> dock = TermDock.DEFAULT;
        assertEquals(Arrays.asList(YT, ATV, NV, SP, NETFLIX, YM, PV), TermDock.pin(dock, NETFLIX));
        assertEquals(Arrays.asList(YT, ATV, NV, SP, PLEX, NETFLIX, YM), TermDock.pin(TermDock.pin(dock, NETFLIX), PLEX));
    }

    @Test
    public void pinningAnAppAlreadyDockedChangesNothing() {
        assertEquals(TermDock.DEFAULT, TermDock.pin(TermDock.DEFAULT, KK));
    }
}
