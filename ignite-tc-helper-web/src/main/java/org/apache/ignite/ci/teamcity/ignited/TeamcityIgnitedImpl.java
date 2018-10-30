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
package org.apache.ignite.ci.teamcity.ignited;


import com.google.common.collect.Sets;
import org.apache.ignite.ci.ITeamcity;
import org.apache.ignite.ci.di.AutoProfiling;
import org.apache.ignite.ci.di.MonitoredTask;
import org.apache.ignite.ci.di.scheduler.IScheduler;
import org.apache.ignite.ci.tcbot.condition.BuildCondition;
import org.apache.ignite.ci.tcbot.condition.BuildConditionDao;
import org.apache.ignite.ci.tcmodel.hist.BuildRef;
import org.apache.ignite.ci.tcmodel.result.Build;
import org.apache.ignite.ci.teamcity.ignited.change.ChangeCompacted;
import org.apache.ignite.ci.teamcity.ignited.change.ChangeDao;
import org.apache.ignite.ci.teamcity.ignited.change.ChangeSync;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.FatBuildCompacted;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.FatBuildDao;
import org.apache.ignite.ci.teamcity.ignited.fatbuild.ProactiveFatBuildSync;
import org.apache.ignite.ci.teamcity.pure.ITeamcityConn;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class TeamcityIgnitedImpl implements ITeamcityIgnited {
    /** Logger. */
    private static final Logger logger = LoggerFactory.getLogger(TeamcityIgnitedImpl.class);

    /** Max build id diff to enforce reload during incremental refresh. */
    public static final int MAX_ID_DIFF_TO_ENFORCE_CONTINUE_SCAN = 3000;

    /**
     * Max builds to check during incremental sync. If this value is reached (50 pages) and some stuck builds still not
     * found, then iteration stops
     */
    public static final int MAX_INCREMENTAL_BUILDS_TO_CHECK = 5000;

    /** Server id. */
    private String srvNme;

    /** Pure HTTP Connection API. */
    private ITeamcityConn conn;

    /** Scheduler. */
    @Inject private IScheduler scheduler;

    /** Build reference DAO. */
    @Inject private BuildRefDao buildRefDao;

    /** Build condition DAO. */
    @Inject private BuildConditionDao buildConditionDao;

    /** Build DAO. */
    @Inject private FatBuildDao fatBuildDao;

    @Inject private ProactiveFatBuildSync buildSync;

    /** Changes DAO. */
    @Inject private ChangeDao changesDao;

    /** Changes DAO. */
    @Inject private ChangeSync changeSync;

    /** Changes DAO. */
    @Inject private IStringCompactor compactor;

    /** Server ID mask for cache Entries. */
    private int srvIdMaskHigh;

    public void init(String srvId, ITeamcityConn conn) {
        this.srvNme = srvId;
        this.conn = conn;

        srvIdMaskHigh = ITeamcityIgnited.serverIdToInt(srvId);
        buildRefDao.init(); //todo init somehow in auto
        buildConditionDao.init();
        fatBuildDao.init();
        changesDao.init();
    }


    @NotNull
    private String taskName(String taskName) {
        return ITeamcityIgnited.class.getSimpleName() +"." + taskName + "." + srvNme;
    }

    /** {@inheritDoc} */
    @Override public String serverId() {
        return srvNme;
    }

    /** {@inheritDoc} */
    @Override public String host() {
        return conn.host();
    }

    /** {@inheritDoc} */
    @AutoProfiling
    @Override public List<BuildRef> getBuildHistory(
            @Nullable String buildTypeId,
            @Nullable String branchName) {
        ensureActualizeRequested();

        String bracnhNameQry = branchForQuery(branchName);

        return buildRefDao.findBuildsInHistory(srvIdMaskHigh, buildTypeId, bracnhNameQry);
    }

    /** {@inheritDoc} */
    @AutoProfiling
    @Override public List<BuildRefCompacted> getBuildHistoryCompacted(
            @Nullable String buildTypeId,
            @Nullable String branchName) {
        ensureActualizeRequested();

        String bracnhNameQry = branchForQuery(branchName);

        return buildRefDao.findBuildsInHistoryCompacted(srvIdMaskHigh, buildTypeId, bracnhNameQry);
    }

    public String branchForQuery(@Nullable String branchName) {
        String bracnhNameQry;
        if (ITeamcity.DEFAULT.equals(branchName))
            bracnhNameQry = "refs/heads/master";
        else
            bracnhNameQry = branchName;
        return bracnhNameQry;
    }

    public void ensureActualizeRequested() {
        scheduler.sheduleNamed(taskName("actualizeRecentBuildRefs"), this::actualizeRecentBuildRefs, 2, TimeUnit.MINUTES);
    }

    /** {@inheritDoc} */
    @Override public Build triggerBuild(String buildTypeId, String branchName, boolean cleanRebuild, boolean queueAtTop) {
        Build build = conn.triggerBuild(buildTypeId, branchName, cleanRebuild, queueAtTop);

        //todo may add additional parameter: load builds into DB in sync/async fashion
        runActualizeBuildRefs(srvNme, false, Sets.newHashSet(build.getId()));

        return build;
    }

    /** {@inheritDoc} */
    @Override public boolean buildIsValid(int buildId) {
        BuildCondition cond = buildConditionDao.getBuildCondition(srvIdMaskHigh, buildId);

        return cond == null || cond.isValid;
    }

    /** {@inheritDoc} */
    @Override public boolean setBuildCondition(BuildCondition cond) {
        return buildConditionDao.setBuildCondition(srvIdMaskHigh, cond);
    }

    /** {@inheritDoc} */
    @Override public FatBuildCompacted getFatBuild(int buildId, boolean acceptQueued) {
        ensureActualizeRequested();
        FatBuildCompacted existingBuild = fatBuildDao.getFatBuild(srvIdMaskHigh, buildId);

        if (existingBuild != null && !existingBuild.isOutdatedEntityVersion()) {
            boolean finished = !existingBuild.isRunning(compactor) && !existingBuild.isQueued(compactor);

            if(finished || acceptQueued)
                return existingBuild;
        }

        FatBuildCompacted savedVer = buildSync.reloadBuild(conn, buildId, existingBuild);

        //build was modified, probably we need also to update reference accordindly
        if (savedVer != null)
            buildRefDao.save(srvIdMaskHigh, new BuildRefCompacted(savedVer));

        return savedVer == null ? existingBuild : savedVer;
    }

    /** {@inheritDoc} */
    @AutoProfiling
    @Override public Collection<ChangeCompacted> getAllChanges(int[] changeIds) {
        final Map<Long, ChangeCompacted> all = changesDao.getAll(srvIdMaskHigh, changeIds);

        final Map<Integer, ChangeCompacted> changes = new HashMap<>();

        //todo support change version upgrade
        all.forEach((k, v) -> {
            final int changeId = ChangeDao.cacheKeyToChangeId(k);

            changes.put(changeId, v);
        });

        for (int changeId : changeIds) {
            if (!changes.containsKey(changeId)) {
                final ChangeCompacted change = changeSync.reloadChange(srvIdMaskHigh, changeId, conn);

                changes.put(changeId, change);
            }
        }

        return changes.values();
    }


    /**
     *
     */
    void actualizeRecentBuildRefs() {
        // schedule find missing later
        buildSync.invokeLaterFindMissingByBuildRef(srvNme, conn);

        List<BuildRefCompacted> running = buildRefDao.getQueuedAndRunning(srvIdMaskHigh);

        Set<Integer> paginateUntil = new HashSet<>();
        Set<Integer> directUpload = new HashSet<>();

        List<Integer> runningIds = running.stream().map(BuildRefCompacted::id).collect(Collectors.toList());
        OptionalInt max = runningIds.stream().mapToInt(i -> i).max();
        if (max.isPresent()) {
            runningIds.forEach(id->{
                if(id > (max.getAsInt() - MAX_ID_DIFF_TO_ENFORCE_CONTINUE_SCAN))
                    paginateUntil.add(id);
                else
                    directUpload.add(id);
            });
        }
        //schedule direct reload for Fat Builds for all queued too-old builds
        buildSync.scheduleBuildsLoad(conn, directUpload);

        runActualizeBuildRefs(srvNme, false, paginateUntil);

        if(!paginateUntil.isEmpty()) {
            //some builds may stuck in the queued or running, enforce loading now
            buildSync.doLoadBuilds(-1, srvNme, conn, paginateUntil);
        }

        // schedule full resync later
        scheduler.invokeLater(this::sheduleResyncBuildRefs, 15, TimeUnit.MINUTES);
    }

    /**
     *
     */
    private void sheduleResyncBuildRefs() {
        scheduler.sheduleNamed(taskName("fullReindex"), this::fullReindex, 120, TimeUnit.MINUTES);
    }

    /**
     *
     */
    void fullReindex() {
        runActualizeBuildRefs(srvNme, true, null);
    }


    /**
     * @param srvId Server id.
     * @param fullReindex Reindex all builds from TC history.
     * @param mandatoryToReload [in/out] Build ID should be found before end of sync. Ignored if fullReindex mode.
     *
     */
    @SuppressWarnings({"WeakerAccess", "UnusedReturnValue"})
    @MonitoredTask(name = "Actualize BuildRefs(srv, full resync)", nameExtArgsIndexes = {0, 1})
    @AutoProfiling
    protected String runActualizeBuildRefs(String srvId, boolean fullReindex,
                                           @Nullable Set<Integer> mandatoryToReload) {
        AtomicReference<String> outLinkNext = new AtomicReference<>();
        List<BuildRef> tcDataFirstPage = conn.getBuildRefsPage(null, outLinkNext);

        Set<Long> buildsUpdated = buildRefDao.saveChunk(srvIdMaskHigh, tcDataFirstPage);
        int totalUpdated = buildsUpdated.size();
        buildSync.scheduleBuildsLoad(conn, cacheKeysToBuildIds(buildsUpdated));

        int totalChecked = tcDataFirstPage.size();
        int neededToFind = 0;
        if (mandatoryToReload != null) {
            neededToFind = mandatoryToReload.size();

            tcDataFirstPage.stream().map(BuildRef::getId).forEach(mandatoryToReload::remove);
        }

        while (outLinkNext.get() != null) {
            String nextPageUrl = outLinkNext.get();
            outLinkNext.set(null);
            List<BuildRef> tcDataNextPage = conn.getBuildRefsPage(nextPageUrl, outLinkNext);
            Set<Long> curChunkBuildsSaved = buildRefDao.saveChunk(srvIdMaskHigh, tcDataNextPage);
            totalUpdated += curChunkBuildsSaved.size();
            buildSync.scheduleBuildsLoad(conn, cacheKeysToBuildIds(curChunkBuildsSaved));

            int savedCurChunk = curChunkBuildsSaved.size();

            totalChecked += tcDataNextPage.size();

            if (!fullReindex) {
                if (mandatoryToReload != null && !mandatoryToReload.isEmpty())
                    tcDataNextPage.stream().map(BuildRef::getId).forEach(mandatoryToReload::remove);

                if (savedCurChunk == 0 &&
                    (mandatoryToReload == null
                        || mandatoryToReload.isEmpty()
                        || totalChecked > MAX_INCREMENTAL_BUILDS_TO_CHECK)
                ) {
                    // There are no modification at current page, hopefully no modifications at all
                    break;
                }
            }
        }

        int leftToFind = mandatoryToReload == null ? 0 : mandatoryToReload.size();
        return "Entries saved " + totalUpdated + " Builds checked " + totalChecked + " Needed to find " + neededToFind + " remained to find " + leftToFind;
    }

    @NotNull private List<Integer> cacheKeysToBuildIds(Collection<Long> cacheKeysUpdated) {
        return cacheKeysUpdated.stream().map(BuildRefDao::cacheKeyToBuildId).collect(Collectors.toList());
    }
}
