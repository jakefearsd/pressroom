/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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

/*
 * Reordering a list of rows by up/down buttons, with native HTML5
 * drag-and-drop as a shortcut for the buttons, never a replacement for them.
 * Shared by the page editor's strand Parts panel and the Pages screen; each
 * names its own list and selectors:
 *
 *   PressroomReorder.install({
 *       list:        the element whose children are the rows (an ol, a tbody),
 *       rowSelector: matches one row,
 *       upSelector:  matches a row's move-up button,
 *       downSelector: matches a row's move-down button,
 *       onMove:      optional, called after every move
 *   });
 *
 * The script only moves rows. What a row's position means (hidden inputs the
 * server reads in posted order) is the page's business.
 */
(function () {
    "use strict";

    function install(options) {
        var list = options.list;
        var rowSelector = options.rowSelector;

        function moved() {
            if (options.onMove) {
                options.onMove();
            }
        }

        list.addEventListener("click", function (event) {
            var button = event.target.closest("button");
            if (!button || !list.contains(button)) {
                return;
            }
            var row = button.closest(rowSelector);
            if (!row) {
                return;
            }
            if (button.matches(options.upSelector) && row.previousElementSibling) {
                list.insertBefore(row, row.previousElementSibling);
            } else if (button.matches(options.downSelector) && row.nextElementSibling) {
                list.insertBefore(row.nextElementSibling, row);
            } else {
                return;
            }
            // Moving a node can drop focus; keyboard users keep their place.
            button.focus();
            moved();
        });

        // Drag-and-drop: the dragged row follows the pointer, inserted before
        // or after the row under it by which half the pointer is in.
        var dragging = null;
        list.addEventListener("dragstart", function (event) {
            var row = event.target.closest(rowSelector);
            if (!row) {
                return;
            }
            dragging = row;
            row.classList.add("is-dragging");
            event.dataTransfer.effectAllowed = "move";
            // Firefox starts no drag without data.
            event.dataTransfer.setData("text/plain", "");
        });
        list.addEventListener("dragover", function (event) {
            if (!dragging) {
                return;
            }
            event.preventDefault();
            var over = event.target.closest(rowSelector);
            if (!over || over === dragging) {
                return;
            }
            var box = over.getBoundingClientRect();
            var after = event.clientY > box.top + box.height / 2;
            list.insertBefore(dragging, after ? over.nextElementSibling : over);
        });
        list.addEventListener("drop", function (event) {
            if (dragging) {
                event.preventDefault();
            }
        });
        list.addEventListener("dragend", function () {
            if (dragging) {
                dragging.classList.remove("is-dragging");
                dragging = null;
                moved();
            }
        });
    }

    window.PressroomReorder = { install: install };
}());
