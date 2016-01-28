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
package org.neo4j.coreedge.raft.state.id_allocation;

import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

import org.neo4j.coreedge.raft.replication.id.IdAllocationState;
import org.neo4j.coreedge.raft.state.StatePersister;
import org.neo4j.coreedge.raft.state.StateRecoveryManager;
import org.neo4j.coreedge.raft.state.id_allocation.InMemoryIdAllocationState.InMemoryIdAllocationStateChannelMarshal;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.impl.store.id.IdType;
import org.neo4j.kernel.internal.DatabaseHealth;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.LogProvider;

/**
 * The OnDiskAllocationState is a decorator around InMemoryIdAllocationState providing on-disk persistence of
 * InMemoryIdAllocationState instances. The purpose of this persistent state is to remember the cumulative effects
 * of RAFT-ed ID allocation such that the RAFT log can be safely truncated.
 * <p>
 * It is log structured for convenience and ease of operational problem solving.
 */
public class OnDiskIdAllocationState extends LifecycleAdapter implements IdAllocationState
{
    public static final String DIRECTORY_NAME = "id-allocation-state";
    public static final String FILENAME = "id.allocation.";

    private InMemoryIdAllocationState inMemoryIdAllocationState;

    private final StatePersister<InMemoryIdAllocationState> statePersister;

    public OnDiskIdAllocationState( FileSystemAbstraction fileSystemAbstraction, File stateDir,
                                    int numberOfEntriesBeforeRotation, Supplier<DatabaseHealth> databaseHealthSupplier,
                                    LogProvider logProvider )
            throws IOException
    {
        File fileA = new File( stateDir, FILENAME + "a" );
        File fileB = new File( stateDir, FILENAME + "b" );

        InMemoryIdAllocationStateChannelMarshal marshal = new InMemoryIdAllocationStateChannelMarshal();

        IdAllocationStateRecoveryManager recoveryManager =
                new IdAllocationStateRecoveryManager( fileSystemAbstraction, marshal );

        StateRecoveryManager.RecoveryStatus recoveryStatus = recoveryManager.recover( fileA, fileB );

        this.inMemoryIdAllocationState = recoveryManager.readLastEntryFrom( fileSystemAbstraction, recoveryStatus.previouslyActive() );

        this.statePersister = new StatePersister<>( fileA, fileB, fileSystemAbstraction, numberOfEntriesBeforeRotation,
                marshal, recoveryStatus.previouslyInactive(), databaseHealthSupplier );

        logProvider.getLog( getClass() ).info( "State restored, last index is %d",
                inMemoryIdAllocationState.logIndex() );
    }

    @Override
    public int lastIdRangeLength( IdType idType )
    {
        return inMemoryIdAllocationState.lastIdRangeLength( idType );
    }

    @Override
    public void lastIdRangeLength( IdType idType, int idRangeLength )
    {
        inMemoryIdAllocationState.lastIdRangeLength( idType, idRangeLength );
    }

    @Override
    public long logIndex()
    {
        return inMemoryIdAllocationState.logIndex();
    }

    /**
     * This should be the last method called after updating the state. It has a
     * side-effect that it flushes the in-memory state to disk.
     *
     * @param logIndex The value to set as the last log index at which this state was updated
     */
    @Override
    public void logIndex( long logIndex )
    {
        InMemoryIdAllocationState temp = new InMemoryIdAllocationState(inMemoryIdAllocationState);
        temp.logIndex( logIndex );

        try
        {
            statePersister.persistStoreData( temp );
            inMemoryIdAllocationState = temp;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    @Override
    public long firstUnallocated( IdType idType )
    {
        return inMemoryIdAllocationState.firstUnallocated( idType );
    }

    @Override
    public void firstUnallocated( IdType idType, long idRangeEnd )
    {
        inMemoryIdAllocationState.firstUnallocated( idType, idRangeEnd );
    }

    @Override
    public long lastIdRangeStart( IdType idType )
    {
        return inMemoryIdAllocationState.lastIdRangeStart( idType );
    }

    @Override
    public void lastIdRangeStart( IdType idType, long idRangeStart )
    {
        inMemoryIdAllocationState.lastIdRangeStart( idType, idRangeStart );
    }

    @Override
    public void shutdown() throws Throwable
    {
        statePersister.close();
    }
}
