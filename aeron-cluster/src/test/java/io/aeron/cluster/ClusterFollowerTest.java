/*
 * Copyright 2014-2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.CommonContext;
import io.aeron.archive.Archive;
import io.aeron.archive.ArchiveThreadingMode;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressMessageListener;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredServiceContainer;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.MinMulticastFlowControlSupplier;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.Header;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.EpochClock;
import org.agrona.concurrent.status.CountersReader;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import static io.aeron.Aeron.NULL_VALUE;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class ClusterFollowerTest
{

    private static final long MAX_CATALOG_ENTRIES = 1024;
    private static final int MEMBER_COUNT = 3;
    private static final int MESSAGE_COUNT = 10;
    private static final String MSG = "Hello World!";

    private static final String CLUSTER_MEMBERS = clusterMembersString();
    private static final String LOG_CHANNEL =
        "aeron:udp?term-length=64k|control-mode=manual|control=localhost:55550";
    private static final String ARCHIVE_CONTROL_REQUEST_CHANNEL =
        "aeron:udp?term-length=64k|endpoint=localhost:8010";
    private static final String ARCHIVE_CONTROL_RESPONSE_CHANNEL =
        "aeron:udp?term-length=64k|endpoint=localhost:8020";

    private final AtomicLong timeOffset = new AtomicLong();
    private final EpochClock epochClock = () -> System.currentTimeMillis() + timeOffset.get();

    private final CountDownLatch latchOne = new CountDownLatch(MEMBER_COUNT);
    private final CountDownLatch latchTwo = new CountDownLatch(MEMBER_COUNT - 1);

    private final EchoService[] echoServices = new EchoService[MEMBER_COUNT];
    private ClusteredMediaDriver[] clusteredMediaDrivers = new ClusteredMediaDriver[MEMBER_COUNT];
    private ClusteredServiceContainer[] containers = new ClusteredServiceContainer[MEMBER_COUNT];
    private MediaDriver clientMediaDriver;
    private AeronCluster client;

    private final MutableInteger responseCount = new MutableInteger();
    private final EgressMessageListener egressMessageListener =
        (correlationId, clusterSessionId, timestamp, buffer, offset, length, header) -> responseCount.value++;

    @Before
    public void before()
    {
        for (int i = 0; i < MEMBER_COUNT; i++)
        {
            startNode(i, true);
        }
    }

    @After
    public void after()
    {
        CloseHelper.close(client);
        CloseHelper.close(clientMediaDriver);

        if (null != clientMediaDriver)
        {
            clientMediaDriver.context().deleteAeronDirectory();
        }

        for (final ClusteredServiceContainer container : containers)
        {
            CloseHelper.close(container);
        }

        for (final ClusteredMediaDriver driver : clusteredMediaDrivers)
        {
            CloseHelper.close(driver);

            if (null != driver)
            {
                driver.mediaDriver().context().deleteAeronDirectory();
                driver.consensusModule().context().deleteDirectory();
                driver.archive().context().deleteArchiveDirectory();
            }
        }
    }

    @Ignore
    @Test(timeout = 30_000)
    public void shouldStopFollowerAndRestartFollower() throws Exception
    {
        int leaderMemberId;
        while (NULL_VALUE == (leaderMemberId = findLeaderId(NULL_VALUE)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        final int followerMemberId = (leaderMemberId + 1) >= MEMBER_COUNT ? 0 : (leaderMemberId + 1);

        stopNode(followerMemberId);

        Thread.sleep(1000);

        startNode(followerMemberId, false);

        Thread.sleep(1000);

        assertThat(roleOf(followerMemberId), is(Cluster.Role.FOLLOWER));
    }

    @Ignore
    @Test(timeout = 30_000)
    public void shouldEchoMessagesThenContinueOnNewLeader() throws Exception
    {
        startClient();

        final int leaderMemberId = findLeaderId(NULL_VALUE);
        assertThat(leaderMemberId, not(NULL_VALUE));

        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        msgBuffer.putStringWithoutLengthAscii(0, MSG);

        sendMessages(msgBuffer);
        awaitResponses(MESSAGE_COUNT);

        latchOne.await();

        assertThat(client.leaderMemberId(), is(leaderMemberId));
        assertThat(responseCount.get(), is(MESSAGE_COUNT));
        for (final EchoService service : echoServices)
        {
            assertThat(service.messageCount(), is(MESSAGE_COUNT));
        }

        stopNode(leaderMemberId);

        int newLeaderMemberId;
        while (NULL_VALUE == (newLeaderMemberId = findLeaderId(leaderMemberId)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        assertThat(newLeaderMemberId, not(leaderMemberId));

        sendMessages(msgBuffer);
        awaitResponses(MESSAGE_COUNT * 2);
        assertThat(client.leaderMemberId(), is(newLeaderMemberId));

        latchTwo.await();
        assertThat(responseCount.get(), is(MESSAGE_COUNT * 2));
        for (final EchoService service : echoServices)
        {
            if (service.index() != leaderMemberId)
            {
                assertThat(service.messageCount(), is(MESSAGE_COUNT * 2));
            }
        }
    }

    @Ignore
    @Test(timeout = 30_000)
    public void shouldStopLeaderAndRestartAfterElectionAsFollower() throws Exception
    {
        int leaderMemberId;
        while (NULL_VALUE == (leaderMemberId = findLeaderId(NULL_VALUE)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        stopNode(leaderMemberId);

        while (NULL_VALUE == findLeaderId(leaderMemberId))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        startNode(leaderMemberId, false);

        Thread.sleep(5000);

        assertThat(roleOf(leaderMemberId), is(Cluster.Role.FOLLOWER));
        assertThat(electionCounterOf(leaderMemberId), is((long)NULL_VALUE));
    }

    @Ignore
    @Test(timeout = 30_000)
    public void shouldStopLeaderAndRestartAfterElectionAsFollowerWithSendingAfter() throws Exception
    {
        int leaderMemberId;
        while (NULL_VALUE == (leaderMemberId = findLeaderId(NULL_VALUE)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        stopNode(leaderMemberId);

        while (NULL_VALUE == findLeaderId(leaderMemberId))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        startNode(leaderMemberId, false);

        Thread.sleep(5000);

        assertThat(roleOf(leaderMemberId), is(Cluster.Role.FOLLOWER));
        assertThat(electionCounterOf(leaderMemberId), is((long)NULL_VALUE));

        startClient();

        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        msgBuffer.putStringWithoutLengthAscii(0, MSG);

        sendMessages(msgBuffer);
        awaitResponses(MESSAGE_COUNT);
    }

    @Ignore
    @Test(timeout = 60_000)
    public void shouldStopLeaderAndRestartAfterElectionAsFollowerWithSendingAfterThenStopLeader() throws Exception
    {
        int leaderMemberId;
        while (NULL_VALUE == (leaderMemberId = findLeaderId(NULL_VALUE)))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        stopNode(leaderMemberId);

        while (NULL_VALUE == findLeaderId(leaderMemberId))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }

        startNode(leaderMemberId, false);

        Thread.sleep(5000);

        assertThat(roleOf(leaderMemberId), is(Cluster.Role.FOLLOWER));
        assertThat(electionCounterOf(leaderMemberId), is((long)NULL_VALUE));

        startClient();

        final ExpandableArrayBuffer msgBuffer = new ExpandableArrayBuffer();
        msgBuffer.putStringWithoutLengthAscii(0, MSG);

        sendMessages(msgBuffer);
        awaitResponses(MESSAGE_COUNT);

        final int newLeaderId = findLeaderId(NULL_VALUE);

        stopNode(newLeaderId);

        while (NULL_VALUE == findLeaderId(newLeaderId))
        {
            TestUtil.checkInterruptedStatus();
            Thread.sleep(1000);
        }
    }

    private void startNode(final int index, final boolean cleanStart)
    {
        echoServices[index] = new EchoService(index, latchOne, latchTwo);
        final String baseDirName = CommonContext.getAeronDirectoryName() + "-" + index;
        final String aeronDirName = CommonContext.getAeronDirectoryName() + "-" + index + "-driver";

        final AeronArchive.Context archiveCtx = new AeronArchive.Context()
            .controlRequestChannel(memberSpecificPort(ARCHIVE_CONTROL_REQUEST_CHANNEL, index))
            .controlRequestStreamId(100 + index)
            .controlResponseChannel(memberSpecificPort(ARCHIVE_CONTROL_RESPONSE_CHANNEL, index))
            .controlResponseStreamId(110 + index)
            .aeronDirectoryName(baseDirName);

        clusteredMediaDrivers[index] = ClusteredMediaDriver.launch(
            new MediaDriver.Context()
                .aeronDirectoryName(aeronDirName)
                .threadingMode(ThreadingMode.SHARED)
                .termBufferSparseFile(true)
                .multicastFlowControlSupplier(new MinMulticastFlowControlSupplier())
                .errorHandler(Throwable::printStackTrace)
                .dirDeleteOnStart(true),
            new Archive.Context()
                .maxCatalogEntries(MAX_CATALOG_ENTRIES)
                .aeronDirectoryName(aeronDirName)
                .archiveDir(new File(baseDirName, "archive"))
                .controlChannel(archiveCtx.controlRequestChannel())
                .controlStreamId(archiveCtx.controlRequestStreamId())
                .localControlChannel("aeron:ipc?term-length=64k")
                .localControlStreamId(archiveCtx.controlRequestStreamId())
                .threadingMode(ArchiveThreadingMode.SHARED)
                .deleteArchiveOnStart(cleanStart),
            new ConsensusModule.Context()
                .epochClock(epochClock)
                .errorHandler(Throwable::printStackTrace)
                .clusterMemberId(index)
                .clusterMembers(CLUSTER_MEMBERS)
                .aeronDirectoryName(aeronDirName)
                .clusterDir(new File(baseDirName, "consensus-module"))
                .ingressChannel("aeron:udp?term-length=64k")
                .logChannel(memberSpecificPort(LOG_CHANNEL, index))
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .archiveContext(archiveCtx.clone())
                .deleteDirOnStart(cleanStart));

        containers[index] = ClusteredServiceContainer.launch(
            new ClusteredServiceContainer.Context()
                .aeronDirectoryName(aeronDirName)
                .archiveContext(archiveCtx.clone())
                .clusterDir(new File(baseDirName, "service"))
                .clusteredService(echoServices[index])
                .terminationHook(TestUtil.TERMINATION_HOOK)
                .errorHandler(Throwable::printStackTrace));
    }

    private void stopNode(final int index)
    {
        containers[index].close();
        containers[index] = null;
        clusteredMediaDrivers[index].close();
        clusteredMediaDrivers[index] = null;
    }

    private void startClient()
    {
        final String aeronDirName = CommonContext.getAeronDirectoryName();

        clientMediaDriver = MediaDriver.launch(
            new MediaDriver.Context()
                .threadingMode(ThreadingMode.SHARED)
                .aeronDirectoryName(aeronDirName));

        client = AeronCluster.connect(
            new AeronCluster.Context()
                .egressMessageListener(egressMessageListener)
                .aeronDirectoryName(aeronDirName)
                .ingressChannel("aeron:udp")
                .clusterMemberEndpoints("0=localhost:20110,1=localhost:20111,2=localhost:20112"));
    }

    private void sendMessages(final ExpandableArrayBuffer msgBuffer)
    {
        for (int i = 0; i < MESSAGE_COUNT; i++)
        {
            final long msgCorrelationId = client.nextCorrelationId();
            while (client.offer(msgCorrelationId, msgBuffer, 0, MSG.length()) < 0)
            {
                TestUtil.checkInterruptedStatus();
                client.pollEgress();
                Thread.yield();
            }

            client.pollEgress();
        }
    }

    private void awaitResponses(final int messageCount)
    {
        while (responseCount.get() < messageCount)
        {
            TestUtil.checkInterruptedStatus();
            Thread.yield();
            client.pollEgress();
        }
    }

    private static String memberSpecificPort(final String channel, final int memberId)
    {
        return channel.substring(0, channel.length() - 1) + memberId;
    }

    private static String clusterMembersString()
    {
        final StringBuilder builder = new StringBuilder();

        for (int i = 0; i < MEMBER_COUNT; i++)
        {
            builder
                .append(i).append(',')
                .append("localhost:2011").append(i).append(',')
                .append("localhost:2022").append(i).append(',')
                .append("localhost:2033").append(i).append(',')
                .append("localhost:2044").append(i).append(',')
                .append("localhost:801").append(i).append('|');
        }

        builder.setLength(builder.length() - 1);

        return builder.toString();
    }

    static class EchoService extends StubClusteredService
    {
        private volatile int messageCount;
        private final int index;
        private final CountDownLatch latchOne;
        private final CountDownLatch latchTwo;

        EchoService(final int index, final CountDownLatch latchOne, final CountDownLatch latchTwo)
        {
            this.index = index;
            this.latchOne = latchOne;
            this.latchTwo = latchTwo;
        }

        int index()
        {
            return index;
        }

        int messageCount()
        {
            return messageCount;
        }

        public void onSessionMessage(
            final ClientSession session,
            final long correlationId,
            final long timestampMs,
            final DirectBuffer buffer,
            final int offset,
            final int length,
            final Header header)
        {
            while (session.offer(correlationId, buffer, offset, length) < 0)
            {
                cluster.idle();
            }

            ++messageCount;

            if (messageCount == MESSAGE_COUNT)
            {
                latchOne.countDown();
            }

            if (messageCount == (MESSAGE_COUNT * 2))
            {
                latchTwo.countDown();
            }
        }
    }

    private int findLeaderId(final int skipMemberId)
    {
        int leaderMemberId = NULL_VALUE;

        for (int i = 0; i < 3; i++)
        {
            if (i == skipMemberId)
            {
                continue;
            }

            final ClusteredMediaDriver driver = clusteredMediaDrivers[i];

            final Cluster.Role role = Cluster.Role.get(
                (int)driver.consensusModule().context().clusterNodeCounter().get());

            if (Cluster.Role.LEADER == role)
            {
                leaderMemberId = driver.consensusModule().context().clusterMemberId();
            }
        }

        return leaderMemberId;
    }

    private Cluster.Role roleOf(final int index)
    {
        final ClusteredMediaDriver driver = clusteredMediaDrivers[index];

        return Cluster.Role.get(
            (int)driver.consensusModule().context().clusterNodeCounter().get());
    }

    private long electionCounterOf(final int index)
    {
        final ClusteredMediaDriver driver = clusteredMediaDrivers[index];
        final CountersReader counters = driver.mediaDriver().context().countersManager();

        final long[] electionState = {NULL_VALUE};

        counters.forEach(
            (counterId, typeId, keyBuffer, label) ->
            {
                if (typeId == Election.ELECTION_STATE_TYPE_ID)
                {
                    electionState[0] = counters.getCounterValue(counterId);
                }
            });

        return electionState[0];
    }
}
