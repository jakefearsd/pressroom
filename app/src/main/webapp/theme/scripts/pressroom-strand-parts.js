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
 * The page editor's strand Parts panel (PageEdit.jsp). Each row carries a
 * hidden bean.partIds input, and the server stores the parts in the order
 * those inputs are posted, validating every id: so all this script does is
 * move rows. Up/down/remove buttons on each row, a picker that appends a row,
 * and native HTML5 drag-and-drop as a shortcut for the buttons, never a
 * replacement for them.
 *
 * Every change fires a bubbling "change" on the list, which is what the
 * editor's leave-page warning listens for.
 */
(function () {
    "use strict";

    function install() {
        var panel = document.getElementById("page_strand_parts");
        var toggle = document.getElementById("page_bean_strand");
        if (!panel || !toggle) {
            return;
        }
        var list = panel.querySelector(".strand-parts-list");
        var picker = document.getElementById("strandAddSelect");
        var template = document.getElementById("strandPartTemplate");

        toggle.addEventListener("change", function () {
            panel.hidden = !toggle.checked;
        });

        function changed() {
            list.dispatchEvent(new Event("change", { bubbles: true }));
        }

        function titleOf(row) {
            return row.querySelector(".strand-part-title").textContent;
        }

        // A removed post goes back into the picker, unless it is in the trash
        // (the picker offers only live posts).
        function offer(row) {
            if (!picker || row.dataset.status === "trashed") {
                return;
            }
            var pill = row.querySelector(".status-pill");
            var option = document.createElement("option");
            option.value = row.querySelector("input[name='bean.partIds']").value;
            option.textContent = titleOf(row);
            option.dataset.status = row.dataset.status || "";
            option.dataset.statusLabel = pill ? pill.textContent : "";
            picker.appendChild(option);
        }

        list.addEventListener("click", function (event) {
            var button = event.target.closest("button");
            if (!button || !list.contains(button)) {
                return;
            }
            var row = button.closest(".strand-part");
            if (button.classList.contains("strand-part-up") && row.previousElementSibling) {
                list.insertBefore(row, row.previousElementSibling);
            } else if (button.classList.contains("strand-part-down") && row.nextElementSibling) {
                list.insertBefore(row.nextElementSibling, row);
            } else if (button.classList.contains("strand-part-remove")) {
                offer(row);
                row.remove();
                changed();
                return;
            } else {
                return;
            }
            // Moving a node can drop focus; keyboard users keep their place.
            button.focus();
            changed();
        });

        if (picker && template) {
            picker.addEventListener("change", function () {
                var option = picker.options[picker.selectedIndex];
                if (!option || !option.value) {
                    return;
                }
                var row = template.content.firstElementChild.cloneNode(true);
                var title = option.textContent;
                row.dataset.status = option.dataset.status || "";
                row.querySelector("input[name='bean.partIds']").value = option.value;
                row.querySelector(".strand-part-title").textContent = title;
                var pill = row.querySelector(".status-pill");
                pill.className = "status-pill status-" + (option.dataset.status || "");
                pill.textContent = option.dataset.statusLabel || "";
                row.querySelectorAll("button[data-label]").forEach(function (b) {
                    b.setAttribute("aria-label", b.dataset.label + ": " + title);
                });
                list.appendChild(row);
                option.remove();
                picker.selectedIndex = 0;
                changed();
            });
        }

        // Drag-and-drop: the dragged row follows the pointer, inserted before
        // or after the row under it by which half the pointer is in.
        var dragging = null;
        list.addEventListener("dragstart", function (event) {
            var row = event.target.closest(".strand-part");
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
            var over = event.target.closest(".strand-part");
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
                changed();
            }
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", install);
    } else {
        install();
    }
}());
