/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
package jdk.graal.compiler.util;

import java.util.Arrays;

/**
 * Utility class for building dynamically sized int arrays.
 */
public class IntArrayBuilder {
    private static final int[] EMPTY_INT_ARRAY = {};

    private int[] data;
    private int size;

    public IntArrayBuilder() {
        this.data = new int[1];
        this.size = 0;
    }

    private void ensureCapacity(int expectedSize) {
        if (expectedSize >= data.length) {
            final int nSize = Integer.highestOneBit(expectedSize) << 1;
            final int[] nData = new int[nSize];
            System.arraycopy(data, 0, nData, 0, size);
            data = nData;
        }
    }

    public void set(int index, int value) {
        ensureCapacity(index);
        data[index] = value;
        size = Math.max(size, index + 1);
    }

    public void add(int value) {
        ensureCapacity(size);
        data[size] = value;
        size++;
    }

    /**
     * @return The int array generated by the builder. May return the underlying int array or a
     *         correctly sized copy.
     */
    public int[] toArray() {
        if (size == 0) {
            return EMPTY_INT_ARRAY;
        } else if (size == data.length) {
            return data;
        }
        return Arrays.copyOf(data, size);
    }
}
