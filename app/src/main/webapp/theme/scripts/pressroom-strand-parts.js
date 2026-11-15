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
 * replacement for them. Moving and dragging are pressroom-reorder.js, loaded
 * first and parameterised here.
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

        // Once now as well: draft recovery can tick the box without a change
        // event, and the panel must follow the box as it stands.
        panel.hidden = !toggle.checked;
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

        // Move and drag are the shared script's; remove is this panel's own.
        window.PressroomReorder.install({
            list: list,
            rowSelector: ".strand-part",
            upSelector: ".strand-part-up",
            downSelector: ".strand-part-down",
            onMove: changed
        });

        list.addEventListener("click", function (event) {
            var button = event.target.closest("button.strand-part-remove");
            if (!button || !list.contains(button)) {
                return;
            }
            var row = button.closest(".strand-part");
            offer(row);
            row.remove();
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
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", install);
    } else {
        install();
    }
}());
