package com.wikantik.pressroom.ui.restapi.dto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.lang.reflect.RecordComponent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** QueueComment repeats CommentView's fields by hand: a field added to one must be added to the other. */
class QueueCommentShapeTest {

    private static List<String> names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    void aQueueCommentIsACommentViewPlusItsBlogAndEntryAddress() {
        List<String> expected = new ArrayList<>(names(CommentDtos.CommentView.class));
        expected.add("weblog");
        expected.add("entryPermalink");
        assertEquals(expected, names(CommentDtos.QueueComment.class));
    }
}
