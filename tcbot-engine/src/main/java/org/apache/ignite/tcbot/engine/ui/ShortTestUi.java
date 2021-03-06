/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ignite.tcbot.engine.ui;

import com.google.common.base.Strings;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ignite.tcbot.engine.chain.TestCompactedMult;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class ShortTestUi {
    private static final Pattern TEST_CLASS_AND_METHOD_PATTERN = Pattern.compile("([^.]+[.][^.]+(\\[.+\\])?$)");

    /** Test full Name */
    public String name;

    /** suite (in code) short name */
    @Nullable public String suiteName;

    /** test short name with class and method */
    @Nullable public String testName;


    /** Test status */
    @Nullable public boolean status;

    public ShortTestUi initFrom(@Nonnull TestCompactedMult testCompactedMult, boolean status) {
        name = testCompactedMult.getName();

        String[] split = Strings.nullToEmpty(name).split("\\:");
        if (split.length >= 2) {
            this.suiteName = extractSuite(split[0]);
            this.testName = extractTest(split[1]);
        }

        this.status = status;

        return this;
    }

    public static String extractTest(String s) {
        String testShort = s.trim();
        Matcher matcher = TEST_CLASS_AND_METHOD_PATTERN.matcher(testShort);
        return matcher.find() ? matcher.group(0) : null;
    }

    public static String extractSuite(String s) {
        String suiteShort = s.trim();
        String[] suiteComps = suiteShort.split("\\.");
        if (suiteComps.length > 1)
            return suiteComps[suiteComps.length - 1];
        return null;
    }
}
