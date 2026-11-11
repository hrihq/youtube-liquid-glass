package com.ytliquid;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NavLabelsTest {

    @Test
    public void matchesEnglishLabels() {
        assertTrue(NavLabels.isNavLabel("Home"));
        assertTrue(NavLabels.isNavLabel("Shorts"));
        assertTrue(NavLabels.isNavLabel("Subscriptions"));
        assertTrue(NavLabels.isNavLabel("You"));
        assertTrue(NavLabels.isNavLabel("Create"));
        assertTrue(NavLabels.isNavLabel("Library"));
    }

    @Test
    public void matchesIndonesianLabels() {
        assertTrue(NavLabels.isNavLabel("Beranda"));
        assertTrue(NavLabels.isNavLabel("Langganan"));
        assertTrue(NavLabels.isNavLabel("Anda"));
        assertTrue(NavLabels.isNavLabel("Buat"));
        assertTrue(NavLabels.isNavLabel("Koleksi"));
    }

    @Test
    public void toleratesPunctuationAndSuffixes() {
        assertTrue(NavLabels.isNavLabel("Home."));
        assertTrue(NavLabels.isNavLabel("Home tab"));
        assertTrue(NavLabels.isNavLabel(" Beranda "));
    }

    @Test
    public void rejectsUnrelatedText() {
        assertFalse(NavLabels.isNavLabel("Amazing cat videos"));
        assertFalse(NavLabels.isNavLabel("Top 10 shortcuts"));
        assertFalse(NavLabels.isNavLabel(""));
        assertFalse(NavLabels.isNavLabel(null));
    }
}
