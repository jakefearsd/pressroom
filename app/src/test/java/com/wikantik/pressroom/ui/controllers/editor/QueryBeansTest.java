/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.ui.controllers.editor;

import java.util.List;

import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.MediaFileFilter.MediaFileOrder;
import com.wikantik.pressroom.pojos.MediaFileFilter.SizeFilterType;
import com.wikantik.pressroom.pojos.MediaFileType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the search/filter bean behind the media list page,
 * {@link MediaFileSearchBean}. {@link EntriesBean}, the Entries-list twin of
 * this bean, has its own top-level {@link EntriesBeanTest}.
 *
 * <p>This is a pure translation layer from form fields to query criteria, with
 * no business tier behind it — which makes it cheap to test exhaustively
 * and easy to get subtly wrong. A mis-mapped filter silently returns the wrong
 * rows rather than failing, so every branch of the mapping is pinned here.
 */
class QueryBeansTest {

    @Nested
    class MediaFileSearchBeanTest {

        @Test
        void eachFileTypeOptionMapsToItsMediaFileType() {
            assertEquals(MediaFileType.AUDIO, typeFor("mediaFileView.audio"));
            assertEquals(MediaFileType.VIDEO, typeFor("mediaFileView.video"));
            assertEquals(MediaFileType.IMAGE, typeFor("mediaFileView.image"));
            assertEquals(MediaFileType.OTHERS, typeFor("mediaFileView.others"));
        }

        @Test
        void theAnyOptionAppliesNoTypeFilter() {
            // "any" is a real option in the dropdown and must not be mapped to
            // a concrete type, which would hide everything else.
            assertNull(typeFor("mediaFileView.any"));
        }

        @Test
        void noTypeChosenLeavesTheFilterUntouched() {
            MediaFileFilter filter = new MediaFileFilter();
            filter.setType(MediaFileType.IMAGE);
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setType("");

            bean.copyTo(filter);

            assertEquals(MediaFileType.IMAGE, filter.getType(),
                    "An empty type must leave any pre-existing filter alone rather than "
                            + "clearing it");
        }

        @Test
        void eachSizeComparisonMapsToItsFilterType() {
            assertEquals(SizeFilterType.GT, sizeFilterFor("mediaFileView.gt"));
            assertEquals(SizeFilterType.GTE, sizeFilterFor("mediaFileView.ge"));
            assertEquals(SizeFilterType.EQ, sizeFilterFor("mediaFileView.eq"));
            assertEquals(SizeFilterType.LTE, sizeFilterFor("mediaFileView.le"));
            assertEquals(SizeFilterType.LT, sizeFilterFor("mediaFileView.lt"));
        }

        @Test
        void anUnrecognisedSizeComparisonFallsBackToEquals() {
            assertEquals(SizeFilterType.EQ, sizeFilterFor("nonsense"));
        }

        @Test
        void sizeUnitsAreConvertedToBytes() {
            // The filter is applied against a byte column; forgetting the
            // conversion makes "larger than 1 MB" mean "larger than 1 byte".
            assertEquals(5L, sizeInBytes(5, "mediaFileView.bytes"));
            assertEquals(5L * PressroomConstants.ONE_KB_IN_BYTES, sizeInBytes(5, "mediaFileView.kb"));
            assertEquals(5L * PressroomConstants.ONE_MB_IN_BYTES, sizeInBytes(5, "mediaFileView.mb"));
        }

        @Test
        void anUnrecognisedSizeUnitIsTreatedAsBytes() {
            assertEquals(5L, sizeInBytes(5, "nonsense"));
        }

        @Test
        void aSizeOfZeroMeansNoSizeFilterAtAll() {
            // Zero is the "not filled in" value for the numeric field; treating
            // it as a real filter would match nothing.
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setSize(0);
            bean.setSizeFilterType("mediaFileView.gt");

            bean.copyTo(filter);

            assertNull(filter.getSizeFilterType());
        }

        @Test
        void tagsAreSplitOnSpaces() {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setTags("holiday beach");

            bean.copyTo(filter);

            assertEquals(List.of("holiday", "beach"), filter.getTags());
        }

        @Test
        void noTagsLeavesTheTagFilterUnset() {
            MediaFileFilter filter = new MediaFileFilter();
            new MediaFileSearchBean().copyTo(filter);

            assertNull(filter.getTags());
        }

        @Test
        void pagingAsksForOneRowMoreThanAPageSoTheUiCanTellThereIsANextPage() {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setPageNum(3);

            bean.copyTo(filter);

            assertEquals(3 * MediaFileSearchBean.PAGE_SIZE, filter.getStartIndex());
            assertEquals(MediaFileSearchBean.PAGE_SIZE + 1, filter.getLength(),
                    "The lookahead row is how the pager knows whether to show 'next'");
        }

        @Test
        void eachSortOptionMapsToItsOrdering() {
            assertEquals(MediaFileOrder.NAME, orderFor(0));
            assertEquals(MediaFileOrder.DATE_UPLOADED, orderFor(1));
            assertEquals(MediaFileOrder.TYPE, orderFor(2));
        }

        @Test
        void anUnrecognisedSortOptionLeavesTheOrderingToTheManager() {
            assertNull(orderFor(99));
        }

        private MediaFileType typeFor(String option) {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setType(option);
            bean.copyTo(filter);
            return filter.getType();
        }

        private SizeFilterType sizeFilterFor(String option) {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setSize(10);
            bean.setSizeFilterType(option);
            bean.copyTo(filter);
            return filter.getSizeFilterType();
        }

        private long sizeInBytes(long size, String unit) {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setSize(size);
            bean.setSizeUnit(unit);
            bean.copyTo(filter);
            return filter.getSize();
        }

        private MediaFileOrder orderFor(int sortOption) {
            MediaFileFilter filter = new MediaFileFilter();
            MediaFileSearchBean bean = new MediaFileSearchBean();
            bean.setSortOption(sortOption);
            bean.copyTo(filter);
            return filter.getOrder();
        }
    }
}
