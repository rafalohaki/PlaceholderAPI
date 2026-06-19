/*
 * This file is part of PlaceholderAPI
 *
 * PlaceholderAPI
 * Copyright (c) 2015 - 2026 PlaceholderAPI Team
 *
 * PlaceholderAPI free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PlaceholderAPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package me.clip.placeholderapi;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the version-normalization logic in {@link PlaceholderAPIPlugin} that previously crashed
 * the entire plugin with {@code ExceptionInInitializerError} on forks (Canvas/Folia) whose
 * {@code Bukkit#getBukkitVersion()} embeds non-numeric build suffixes such as {@code "1.21.b821"}.
 *
 * <p>The methods under test are private static, so we invoke them reflectively. They do not touch
 * Bukkit, so no mock is required.</p>
 */
class PlaceholderAPIPluginVersionTest {

    private static String normalize(final String raw) throws Exception {
        final Method m = PlaceholderAPIPlugin.class.getDeclaredMethod("normalizeServerVersion", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, raw);
    }

    @Test
    void twoSegmentVersionGetsR1Suffix() throws Exception {
        assertEquals("v1_21_R1", normalize("1.21"));
    }

    @Test
    void threeSegmentNumericPatchUsesPatchAsR() throws Exception {
        assertEquals("v1_21_R11", normalize("1.21.11"));
    }

    @Test
    void singleDigitPatch() throws Exception {
        assertEquals("v1_21_R3", normalize("1.21.3"));
    }

    @Test
    void canvasStyleNonNumericPatchDoesNotCrash() throws Exception {
        // This is the exact shape that triggered NumberFormatException: For input string: "b"
        // on Canvas 26.2 (Folia 1.21.11). The leading digit run is empty → default to R1.
        assertEquals("v1_21_R1", normalize("1.21.b821"));
    }

    @Test
    void patchWithTrailingBuildSuffixKeepsLeadingDigits() throws Exception {
        assertEquals("v1_21_R11", normalize("1.21.11b821"));
    }
}
