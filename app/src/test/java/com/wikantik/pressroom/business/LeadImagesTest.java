package com.wikantik.pressroom.business;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Spec 2026-11-07 (pictures by default), criterion 1. */
class LeadImagesTest {

    private static final String MEDIA = "https://blog.example.com/coast/mediaresource/";

    @Test
    void eachWayOfShowingAPictureIsFoundInTextOrder() {
        assertEquals(List.of("a", "b", "c", "d"), LeadImages.mediaIds(
                "Intro.\n\n[image id=\"a\" caption=\"Harbour\"]\n\n"
                        + "<p><img alt=\"x\" src=\"" + MEDIA + "b?w=480\"></p>\n\n"
                        + "![Pier](" + MEDIA + "c)\n\n"
                        + "[image caption='late' id='d']\n"));
    }

    @Test
    void aPictureShownTwiceIsListedOnce() {
        assertEquals(List.of("a", "b"), LeadImages.mediaIds(
                "![one](" + MEDIA + "a)\n\n![two](" + MEDIA + "b)\n\n[image id=\"a\"]\n"));
    }

    @Test
    void codeLinksAndOutsideImagesAreNotPictures() {
        assertEquals(List.of(), LeadImages.mediaIds(
                "```\n![x](" + MEDIA + "fenced)\n```\n\n"
                        + "    <img src=\"" + MEDIA + "indented\">\n\n"
                        + "`[image id=\"inline-code\"]`\n\n"
                        + "[the full size](" + MEDIA + "linked) and <a href=\"" + MEDIA + "anchor\">this</a>\n\n"
                        + "![remote](https://raw.example.org/pic.jpg)\n"));
    }

    @Test
    void noTextShowsNothing() {
        assertEquals(List.of(), LeadImages.mediaIds(null));
        assertEquals(List.of(), LeadImages.mediaIds(""));
    }
}
