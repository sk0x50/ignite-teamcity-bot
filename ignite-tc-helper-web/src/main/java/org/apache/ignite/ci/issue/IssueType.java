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

package org.apache.ignite.ci.issue;

/**
 * Type of Issue detectable by the Bot.
 */
public enum IssueType {
    /** New failure. */
    newFailure("newFailure", "New test failure"),

    /** New contributed test failure. */
    newContributedTestFailure("newContributedTestFailure", "Recently contributed test failed"),

    /** New failure for flaky test. */
    newFailureForFlakyTest("newFailureForFlakyTest", "New stable failure of a flaky test"),

    /** New critical failure. */
    newCriticalFailure("newCriticalFailure", "New Critical Failure"),

    /** New trusted suite failure. */
    newTrustedSuiteFailure("newTrustedSuiteFailure", "New Trusted Suite failure");

    /** Code. */
    private final String code;
    /** Display name. */
    private final String displayName;

    /**
     * @param code Code.
     * @param displayName Display name.
     */
    private IssueType(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    /**
     *
     */
    public String code() {
        return code;
    }

    /**
     *
     */
    public String displayName() {
        return displayName;
    }
}
