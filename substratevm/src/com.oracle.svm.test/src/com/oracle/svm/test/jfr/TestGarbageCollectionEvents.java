/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2025, 2025, Red Hat Inc. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.test.jfr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

import com.oracle.svm.core.jfr.JfrEvent;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;

public class TestGarbageCollectionEvents extends JfrRecordingTest {
    @Test
    public void test() throws Throwable {
        String[] events = new String[]{JfrEvent.GarbageCollection.getName()};
        Recording recording = startRecording(events);
        System.gc();
        System.gc();
        System.gc();
        stopRecording(recording, TestGarbageCollectionEvents::validateEvents);
    }

    private static void validateEvents(List<RecordedEvent> events) {
        assertFalse(events.isEmpty());
        int foundSystemGc = 0;
        Set<Integer> ids = new HashSet<>();
        for (RecordedEvent event : events) {
            assertTrue(ids.add(event.getInt("gcId")));
            assertNotNull(event.getThread("eventThread").getJavaName());
            assertFalse(event.getDuration().isZero());
            assertNotNull(event.getString("name"));
            assertTrue(event.getLong("longestPause") > 0);
            assertTrue(event.getLong("sumOfPauses") > 0);
            if (event.getString("cause").equals("java.lang.System.gc()")) {
                foundSystemGc++;
            }
        }
        assertTrue(foundSystemGc >= 3);
    }
}
