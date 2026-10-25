package com.gathin.tvhome;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RemoteModelTest {
    private static final float EPS = 1e-4f;

    /** Inside the outline: a rectangle of width 1 and length LENGTH with half-circle ends. */
    private static boolean inside(float x, float y) {
        if (x < -EPS || x > 1 + EPS || y < -EPS || y > RemoteModel.LENGTH + EPS) return false;
        float cap = y < .5f ? .5f : y > RemoteModel.LENGTH - .5f ? RemoteModel.LENGTH - .5f : y;
        return Math.hypot(x - .5f, y - cap) <= .5f + EPS;
    }

    @Test public void everyButtonIsInsideTheOutline() {
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            boolean circle = b.shape == RemoteModel.Shape.ROUND || b.shape == RemoteModel.Shape.ICON;
            if (circle) {
                float cap = b.cy < .5f ? .5f : b.cy > RemoteModel.LENGTH - .5f ? RemoteModel.LENGTH - .5f : b.cy;
                double reach = Math.hypot(b.cx - .5f, b.cy - cap) + b.w / 2;
                assertTrue(b.id, b.cx - b.w / 2 >= 0 && b.cx + b.w / 2 <= 1 && (b.cy != cap ? reach <= .5f + EPS : true));
            } else {
                assertTrue(b.id, inside(b.left(), b.top()) && inside(b.right(), b.top())
                        && inside(b.left(), b.bottom()) && inside(b.right(), b.bottom()));
            }
        }
    }

    @Test public void noTwoButtonsOverlap() {
        List<RemoteModel.Button> all = RemoteModel.buttons();
        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                RemoteModel.Button a = all.get(i), b = all.get(j);
                boolean apart = a.right() <= b.left() + EPS || b.right() <= a.left() + EPS
                        || a.bottom() <= b.top() + EPS || b.bottom() <= a.top() + EPS;
                assertTrue(a.id + " overlaps " + b.id, apart);
            }
        }
    }

    @Test public void idsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (RemoteModel.Button b : RemoteModel.buttons()) assertTrue(b.id, ids.add(b.id));
    }

    @Test public void theCursorReachesEveryButtonFromEveryButton() {
        List<RemoteModel.Button> all = RemoteModel.buttons();
        for (RemoteModel.Button start : all) {
            Set<RemoteModel.Button> seen = new HashSet<>();
            ArrayDeque<RemoteModel.Button> todo = new ArrayDeque<>();
            todo.add(start);
            seen.add(start);
            while (!todo.isEmpty()) {
                RemoteModel.Button b = todo.poll();
                for (RemoteModel.Dir dir : RemoteModel.Dir.values()) {
                    RemoteModel.Button n = RemoteModel.next(b, dir);
                    if (n != null && seen.add(n)) todo.add(n);
                }
            }
            assertEquals("from " + start.id, all.size(), seen.size());
        }
    }

    @Test public void lyingDownTheTopIsLeftAndTheRightSideIsUp() {
        RemoteModel.Button power = RemoteModel.byId("power");
        // Power is the leftmost button on screen; INPUT (right side, standing) is above SETTINGS (left side).
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            assertTrue(b.id, RemoteModel.u(b.cx, b.cy) >= RemoteModel.u(power.cx, power.cy) - EPS);
        }
        RemoteModel.Button input = RemoteModel.byId("input"), settings = RemoteModel.byId("settings");
        assertTrue(RemoteModel.v(input.cx, input.cy) < RemoteModel.v(settings.cx, settings.cy));
        assertEquals(RemoteModel.byId("mic"), RemoteModel.next(settings, RemoteModel.Dir.UP));
        assertEquals(input, RemoteModel.next(RemoteModel.byId("mic"), RemoteModel.Dir.UP));
    }

    @Test public void stepsKeepToTheirRow() {
        RemoteModel.Button d1 = RemoteModel.byId("d1");
        assertEquals(RemoteModel.byId("d4"), RemoteModel.next(d1, RemoteModel.Dir.RIGHT));
        assertEquals(RemoteModel.byId("d2"), RemoteModel.next(d1, RemoteModel.Dir.UP));
        assertNull(RemoteModel.next(d1, RemoteModel.Dir.DOWN));   // the remote's edge: focus drops to the panel
        assertNull(RemoteModel.next(RemoteModel.byId("power"), RemoteModel.Dir.LEFT));
    }

    @Test public void keycodesFindTheirButton() {
        assertEquals("input", RemoteModel.byKeycode(RemoteModel.TV_INPUT).id);
        assertEquals("mic", RemoteModel.byKeycode(RemoteModel.ASSIST).id);
        assertNull(RemoteModel.byKeycode(RemoteModel.BUTTON_4));   // the app keys start blank
        assertNotNull(RemoteModel.firmware(RemoteModel.BUTTON_4));
        assertEquals(RemoteModel.LAUNCHERX, RemoteModel.firmware(RemoteModel.GUIDE).pkg);
    }

    @Test public void sixBlankAppKeysAndNoKnownCodeForEco() {
        int apps = 0;
        for (RemoteModel.Button b : RemoteModel.buttons()) {
            if (!b.isAppKey()) continue;
            apps++;
            assertEquals(b.id, 0, b.keycodes.length);
        }
        assertEquals(6, apps);
        assertEquals(0, RemoteModel.byId("eco").keycodes.length);
    }
}
