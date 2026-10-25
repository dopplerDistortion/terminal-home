package com.gathin.tvhome;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RemoteMapTest {

    private static Map<String, Object> saved(Object... pairs) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    @Test public void nothingSavedGivesNoEntries() {
        assertTrue(RemoteMap.entries(saved()).isEmpty());
    }

    @Test public void aTapTargetCoversEveryGuessedKeycode() {
        List<String> e = RemoteMap.entries(saved("tap.mic", "none"));
        assertEquals(Arrays.asList("219:none", "84:none", "231:none"), e);
    }

    @Test public void aLearnedKeycodeReplacesTheGuesses() {
        List<String> e = RemoteMap.entries(saved("tap.mic", "home", "hold.mic", "apps", "code.mic", 231, "scan.mic", 582));
        assertEquals(Arrays.asList("231:home", "hold:231:apps"), e);
    }

    @Test public void aScancodeAloneBecomesAScanEntry() {
        List<String> e = RemoteMap.entries(saved("tap.app1", "com.spotify.tv.android", "scan.app1", 490));
        assertEquals(Collections.singletonList("scan:490:com.spotify.tv.android"), e);
    }

    @Test public void anAppKeyWithNothingLearnedHasNoEntry() {
        assertTrue(RemoteMap.entries(saved("tap.app1", "com.spotify.tv.android")).isEmpty());
    }

    @Test public void lockedButtonsNeverGetAnEntry() {
        assertTrue(RemoteMap.entries(saved("tap.home", "apps", "tap.up", "none")).isEmpty());
    }

    @Test public void theKeymapComesAfterConfigSoItWins() {
        List<String> all = RemoteMap.merged(Collections.singletonList("178:com.example.tv"), saved("tap.input", "inputs"));
        assertEquals(Arrays.asList("178:com.example.tv", "178:inputs"), all);
        Map<Integer, String> keys = RemoteKeys.parseAppKeys(all, name -> 0);
        assertEquals("inputs", keys.get(178));
    }

    @Test public void entriesParseBackThroughRemoteKeys() {
        List<String> e = RemoteMap.entries(saved("tap.app2", "com.netflix.ninja", "code.app2", 191, "hold.guide", "sleep"));
        assertEquals("com.netflix.ninja", RemoteKeys.parseAppKeys(e, name -> 0).get(191));
        assertEquals("sleep", RemoteKeys.parseHoldAppKeys(e, name -> 0).get(172));
        assertNull(RemoteKeys.parseAppKeys(e, name -> 0).get(172));
    }

    @Test public void findPrefersLearnedCodes() {
        assertEquals("input", RemoteMap.find(saved(), 178, 0).id);
        assertEquals("app3", RemoteMap.find(saved("code.app3", 191), 191, 12).id);
        assertEquals("app4", RemoteMap.find(saved("code.app4", 0, "scan.app4", 77), 0, 77).id);
        // Once ECO learned 178, Input no longer answers to its guess.
        assertEquals("eco", RemoteMap.find(saved("code.eco", 178), 178, 0).id);
        assertNull(RemoteMap.find(saved(), 191, 0));
    }

    @Test public void firmwareFollowsTheLearnedKeycode() {
        assertNull(RemoteMap.firmware(saved(), RemoteModel.byId("app1")));
        assertEquals("NETFLIX", RemoteMap.firmware(saved("code.app1", 191), RemoteModel.byId("app1")).label);
        assertEquals("com.arcelik.ecomode", RemoteMap.firmware(saved("code.eco", 178), RemoteModel.byId("eco")).pkg);
    }

    @Test public void suggestionsFixOnlyBrokenUnmappedNonAppKeys() {
        Map<String, String> s = RemoteMap.suggestions(saved("tap.guide", "apps"),
                new HashSet<>(Arrays.asList("input", "tv", "guide", "mic", "eco", "app5", "home")));
        assertEquals("inputs", s.get("input"));
        assertEquals("inputs", s.get("tv"));
        assertEquals("none", s.get("mic"));
        assertFalse(s.containsKey("guide"));   // already mapped
        assertFalse(s.containsKey("eco"));     // no keycode learned yet
        assertFalse(s.containsKey("app5"));    // the user's to fill
        assertFalse(s.containsKey("home"));    // locked
        assertEquals("screensaver", RemoteMap.suggestions(saved("code.eco", 140),
                Collections.singleton("eco")).get("eco"));
        assertEquals("home", RemoteMap.suggestions(saved(), Collections.singleton("guide")).get("guide"));
    }
}
