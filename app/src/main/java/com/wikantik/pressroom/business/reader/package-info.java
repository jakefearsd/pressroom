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

/**
 * What a signed-in reader keeps: saved entries, followed weblogs and reading
 * history. {@link com.wikantik.pressroom.business.reader.ReaderStateService}
 * holds every rule and is the only writer of the three
 * {@code pressroom_reader_*} tables; {@link com.wikantik.pressroom.business.reader.ReaderVisibility}
 * is the one rule for what a reader may see in any of it.
 */
package com.wikantik.pressroom.business.reader;
