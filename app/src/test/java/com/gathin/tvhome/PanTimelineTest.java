package com.gathin.tvhome;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The trending screensaver's timeline: intro, 45 s slides, the fold, the dissolve, and motion off. */
public class PanTimelineTest {

    private static PanTimeline run(PanTimeline p, int ticks) {
        for (int i = 0; i < ticks; i++) p.step();
        return p;
    }

    @Test
    public void introTypesTheHeaderThenBuildsTheFirstPicture() {
        PanTimeline p = new PanTimeline(3, true, false);
        assertEquals(PanTimeline.Phase.INTRO, p.phase());
        assertEquals(0, p.headerTyped());
        assertFalse(p.baseShown());
        run(p, PanTimeline.INTRO_TYPE);
        assertEquals(Integer.MAX_VALUE, p.headerTyped());
        assertEquals(0, p.revealed());
        run(p, PanTimeline.BUILD);
        assertEquals(PanTimeline.BLOCKS, p.revealed());
        p.step();
        assertEquals(PanTimeline.Phase.HOLD, p.phase());
        assertTrue(p.baseShown());
    }

    @Test
    public void aHeaderAlreadyTypedSkipsStraightToTheBuild() {
        PanTimeline p = new PanTimeline(3, true, true);
        assertEquals(Integer.MAX_VALUE, p.headerTyped());
        run(p, PanTimeline.BUILD + 1);
        assertEquals(PanTimeline.Phase.HOLD, p.phase());
    }

    @Test
    public void aSlideHolds45SecondsThenDissolvesToTheNext() {
        PanTimeline p = run(new PanTimeline(3, true, true), PanTimeline.BUILD + 1);
        assertEquals(45_000, PanTimeline.HOLD * PanTimeline.TICK_MS);
        run(p, PanTimeline.HOLD - 1);
        assertEquals(PanTimeline.Phase.HOLD, p.phase());
        p.step();
        assertEquals(PanTimeline.Phase.CUT, p.phase());
        assertEquals(0, p.slide());
        run(p, PanTimeline.CUT);
        assertEquals(PanTimeline.BLOCKS, p.revealed());
        p.step();
        assertEquals(1, p.slide());
        assertEquals(PanTimeline.Phase.HOLD, p.phase());
    }

    @Test
    public void thePanMovesOneStepAtATimeAcrossItsWidthAndAlternates() {
        PanTimeline p = new PanTimeline(2, true, true);
        assertEquals(0, p.panDp(0, 0));
        assertEquals(PanTimeline.PAN_DP, p.panDp(0, PanTimeline.HOLD));
        assertEquals(PanTimeline.PAN_DP, p.panDp(1, 0));
        assertEquals(0, p.panDp(1, PanTimeline.HOLD));
        for (int t = 1; t <= PanTimeline.HOLD; t++) assertTrue(p.panDp(0, t) - p.panDp(0, t - 1) <= 1);
    }

    @Test
    public void thePanelFoldsAPartAtATimeFrom20Seconds() {
        PanTimeline p = run(new PanTimeline(2, true, true), PanTimeline.BUILD + 1);
        run(p, PanTimeline.FOLD_AT - 1);
        assertEquals(0, p.fold(false));
        p.step();
        assertEquals(1, p.fold(false));
        run(p, PanTimeline.FOLD_STEP);
        assertEquals(2, p.fold(false));
        run(p, PanTimeline.FOLD_STEP);
        assertEquals(3, p.fold(false));
        run(p, 100);
        assertEquals(3, p.fold(false));
        assertEquals(3, new PanTimeline(2, true, true).fold(true));
    }

    @Test
    public void metaThenOverviewTypeIn() {
        PanTimeline p = run(new PanTimeline(2, true, true), PanTimeline.BUILD + 1);
        assertEquals(0, p.metaTyped(20));
        run(p, 10);
        assertEquals(6, p.metaTyped(20));
        assertEquals(0, p.overviewTyped(20, 100));
        run(p, 30);
        assertEquals(20, p.metaTyped(20));
        assertEquals(48, p.overviewTyped(20, 100));
        run(p, 100);
        assertEquals(100, p.overviewTyped(20, 100));
    }

    @Test
    public void thePagerFillsInSixteenSteps() {
        PanTimeline p = run(new PanTimeline(2, true, true), PanTimeline.BUILD + 1);
        Set<Integer> seen = new HashSet<>();
        for (int t = 0; t < PanTimeline.HOLD; t++) {
            seen.add(p.pagerSteps());
            p.step();
        }
        assertEquals(PanTimeline.PAGER_STEPS, seen.size());
    }

    @Test
    public void leftCutsBackAndRightDissolvesForward() {
        PanTimeline p = run(new PanTimeline(3, true, true), PanTimeline.BUILD + 1);
        p.previous();
        assertEquals(2, p.slide());
        assertEquals(PanTimeline.Phase.HOLD, p.phase());
        p.next();
        assertEquals(PanTimeline.Phase.CUT, p.phase());
        p.next();   // ignored mid-dissolve
        p.previous();
        assertEquals(2, p.slide());
        run(p, PanTimeline.CUT + 1);
        assertEquals(0, p.slide());
    }

    @Test
    public void motionOffShowsFinalStatesAndCuts() {
        PanTimeline p = new PanTimeline(2, false, false);
        assertEquals(PanTimeline.Phase.HOLD, p.phase());
        assertEquals(Integer.MAX_VALUE, p.headerTyped());
        assertEquals(PanTimeline.PAN_DP / 2, p.panDp());
        assertEquals(20, p.metaTyped(20));
        assertEquals(100, p.overviewTyped(20, 100));
        assertTrue(p.cursorOn());
        assertArrayEquals(new int[]{0, 0}, p.spot());
        run(p, PanTimeline.HOLD);
        assertEquals(1, p.slide());
        assertEquals(PanTimeline.Phase.HOLD, p.phase());
        assertEquals(0, p.revealed());
    }

    @Test
    public void theHudMovesOnceAMinuteAndComesBack() {
        PanTimeline p = new PanTimeline(1, true, true);
        assertArrayEquals(new int[]{0, 0}, p.spot());
        run(p, PanTimeline.SHIFT);
        assertArrayEquals(new int[]{4, 0}, p.spot());
        run(p, 3 * PanTimeline.SHIFT);
        assertArrayEquals(new int[]{0, 0}, p.spot());
    }

    @Test
    public void nightRunsFrom23To6() {
        assertTrue(PanTimeline.night(23));
        assertTrue(PanTimeline.night(0));
        assertTrue(PanTimeline.night(5));
        assertFalse(PanTimeline.night(6));
        assertFalse(PanTimeline.night(22));
    }

    @Test
    public void theBlockOrderIsAFixedPermutation() {
        int[] a = PanTimeline.blockRanks(), b = PanTimeline.blockRanks();
        assertArrayEquals(a, b);
        Set<Integer> ranks = new HashSet<>();
        for (int r : a) ranks.add(r);
        assertEquals(PanTimeline.BLOCKS, ranks.size());
    }

    @Test
    public void theHintShowsAfterTheIntroAndAfterAKey() {
        PanTimeline p = new PanTimeline(2, true, false);
        assertFalse(p.hintShown(false));
        run(p, PanTimeline.INTRO_TYPE + PanTimeline.BUILD + 1);
        assertTrue(p.hintShown(false));
        assertFalse(p.hintShown(true));
        run(p, PanTimeline.HINT);
        assertFalse(p.hintShown(false));
        p.keyPressed();
        assertTrue(p.hintShown(false));
    }
}
