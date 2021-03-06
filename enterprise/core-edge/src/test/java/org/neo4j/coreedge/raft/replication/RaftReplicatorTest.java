/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.replication;

import org.junit.Test;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.neo4j.coreedge.network.Message;
import org.neo4j.coreedge.raft.LeaderLocator;
import org.neo4j.coreedge.raft.ReplicatedInteger;
import org.neo4j.coreedge.raft.net.Outbound;
import org.neo4j.coreedge.raft.replication.session.GlobalSession;
import org.neo4j.coreedge.raft.replication.session.LocalSessionPool;
import org.neo4j.coreedge.raft.replication.tx.ConstantTimeRetryStrategy;
import org.neo4j.coreedge.raft.replication.tx.RetryStrategy;
import org.neo4j.coreedge.raft.state.Result;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static junit.framework.TestCase.assertEquals;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.neo4j.test.assertion.Assert.assertEventually;

public class RaftReplicatorTest
{
    private static final int DEFAULT_TIMEOUT_MS = 15_000;

    private LeaderLocator<Object> leaderLocator = mock( LeaderLocator.class );
    private Object myself = new Object();
    private Object leader = new Object();
    private GlobalSession<Object> session = new GlobalSession<>( UUID.randomUUID(), myself );
    private LocalSessionPool<Object> sessionPool = new LocalSessionPool<>( session );
    private RetryStrategy retryStrategy = new ConstantTimeRetryStrategy( 1, SECONDS );

    @Test
    public void shouldSendReplicatedContentToLeader() throws Exception
    {
        // given
        when( leaderLocator.getLeader() ).thenReturn( leader );
        CapturingProgressTracker capturedProgress = new CapturingProgressTracker();
        CapturingOutbound outbound = new CapturingOutbound();

        RaftReplicator<Object> replicator =
                new RaftReplicator<>( leaderLocator, myself, outbound, sessionPool, capturedProgress, retryStrategy );

        ReplicatedInteger content = ReplicatedInteger.valueOf( 5 );
        Thread replicatingThread = replicatingThread( replicator, content, false );

        // when
        replicatingThread.start();
        // then
        assertEventually( "making progress", () -> capturedProgress.last, not( equalTo( null ) ), DEFAULT_TIMEOUT_MS, MILLISECONDS );

        // when
        capturedProgress.last.setReplicated();

        // then
        replicatingThread.join( DEFAULT_TIMEOUT_MS );
        assertEquals( leader, outbound.lastTo );
    }

    @Test
    public void shouldResendAfterTimeout() throws Exception
    {
        // given
        when( leaderLocator.getLeader() ).thenReturn( leader );
        CapturingProgressTracker capturedProgress = new CapturingProgressTracker();
        CapturingOutbound outbound = new CapturingOutbound();

        ConstantTimeRetryStrategy retryStrategy = new ConstantTimeRetryStrategy( 100, MILLISECONDS );
        RaftReplicator<Object> replicator =
                new RaftReplicator<>( leaderLocator, myself, outbound, sessionPool, capturedProgress, retryStrategy );

        ReplicatedInteger content = ReplicatedInteger.valueOf( 5 );
        Thread replicatingThread = replicatingThread( replicator, content, false );

        // when
        replicatingThread.start();
        // then
        assertEventually( "send count", () -> outbound.count, greaterThan( 2 ), DEFAULT_TIMEOUT_MS, MILLISECONDS );

        // cleanup
        capturedProgress.last.setReplicated();
        replicatingThread.join( DEFAULT_TIMEOUT_MS );
    }

    @Test
    public void shouldReleaseSessionWhenFinished() throws Exception
    {
        // given
        when( leaderLocator.getLeader() ).thenReturn( leader );
        CapturingProgressTracker capturedProgress = new CapturingProgressTracker();
        CapturingOutbound outbound = new CapturingOutbound();

        RaftReplicator<Object> replicator =
                new RaftReplicator<>( leaderLocator, myself, outbound, sessionPool, capturedProgress, retryStrategy );

        ReplicatedInteger content = ReplicatedInteger.valueOf( 5 );
        Thread replicatingThread = replicatingThread( replicator, content, true );

        // when
        replicatingThread.start();

        // then
        assertEventually( "making progress", () -> capturedProgress.last, not( equalTo( null ) ), DEFAULT_TIMEOUT_MS, MILLISECONDS );
        assertEquals( 1, sessionPool.openSessionCount() );

        // when
        capturedProgress.last.setReplicated();
        capturedProgress.last.futureResult().complete( 5 );
        replicatingThread.join( DEFAULT_TIMEOUT_MS );

        // then
        assertEquals( 0, sessionPool.openSessionCount() );
    }

    private Thread replicatingThread( RaftReplicator<Object> replicator, ReplicatedInteger content, boolean trackResult )
    {
        return new Thread( () -> {
            try
            {
                Future<Object> futureResult = replicator.replicate( content, trackResult );
                if( trackResult )
                {
                    try
                    {
                        futureResult.get();
                    }
                    catch ( ExecutionException e )
                    {
                        throw new IllegalStateException();
                    }
                }
            }
            catch ( InterruptedException e )
            {
                throw new IllegalStateException();
            }
        } );
    }

    private class CapturingProgressTracker implements ProgressTracker
    {
        private Progress last;

        @Override
        public Progress start( DistributedOperation operation )
        {
            last = new Progress();
            return last;
        }

        @Override
        public void trackReplication( DistributedOperation operation )
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void trackResult( DistributedOperation operation, Result result )
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort( DistributedOperation operation )
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void triggerReplicationEvent()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public int inProgressCount()
        {
            throw new UnsupportedOperationException();
        }
    }

    private class CapturingOutbound implements Outbound<Object>
    {
        private Object lastTo;
        private int count;

        @Override
        public void send( Object to, Message... messages )
        {
            this.lastTo = to;
            this.count++;
        }
    }
}
