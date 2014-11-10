/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.server;

import org.neo4j.graphdb.TransactionFailureException;
import org.neo4j.helpers.Service;
import org.neo4j.kernel.GraphDatabaseDependencies;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.info.JvmChecker;
import org.neo4j.kernel.info.JvmMetadataRepository;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.logging.BufferingConsoleLogger;
import org.neo4j.kernel.logging.ConsoleLogger;
import org.neo4j.kernel.logging.DefaultLogging;
import org.neo4j.kernel.logging.Logging;
import org.neo4j.kernel.monitoring.Monitors;
import org.neo4j.server.configuration.ConfigurationBuilder;
import org.neo4j.server.configuration.Configurator;
import org.neo4j.server.configuration.PropertyFileConfigurator;
import org.neo4j.server.configuration.ServerSettings;

import static java.lang.String.format;

/**
 * @deprecated This class is for internal use only and will be moved to an internal package in a future release.
 * Please use Neo4j Server and plugins or un-managed extensions for bespoke solutions.
 */
@Deprecated
public abstract class Bootstrapper
{
    public static final Integer OK = 0;
    public static final Integer WEB_SERVER_STARTUP_ERROR_CODE = 1;
    public static final Integer GRAPH_DATABASE_STARTUP_ERROR_CODE = 2;

    protected final LifeSupport life = new LifeSupport();
    protected NeoServer server;
	protected ConfigurationBuilder configurator;
    private Thread shutdownHook;
    protected GraphDatabaseDependencies dependencies = GraphDatabaseDependencies.newDependencies();
    private ConsoleLogger log;

    public static void main( String[] args )
    {
        Bootstrapper bootstrapper = loadMostDerivedBootstrapper();
        Integer exit = bootstrapper.start( args );
        if ( exit != 0 )
        {
            System.exit( exit );
        }
    }

    public static Bootstrapper loadMostDerivedBootstrapper()
    {
        Bootstrapper winner = new CommunityBootstrapper();
        for ( Bootstrapper candidate : Service.load( Bootstrapper.class ) )
        {
            if ( candidate.isMoreDerivedThan( winner ) )
            {
                winner = candidate;
            }
        }
        return winner;
    }

    public void controlEvent( int arg )
    {
        // Do nothing, required by the WrapperListener interface
    }

    public Integer start()
    {
        return start( new String[0] );
    }

    // TODO: This does not use args, check if it is safe to remove them
    public Integer start( String[] args )
    {
        try
        {
            dependencies = dependencies.monitors(new Monitors());
            BufferingConsoleLogger consoleBuffer = new BufferingConsoleLogger();
        	configurator = createConfigurationBuilder( consoleBuffer );
        	dependencies = dependencies.logging(createLogging( configurator, dependencies.monitors()));
        	log = dependencies.logging().getConsoleLog( getClass() );
        	consoleBuffer.replayInto( log );

        	life.start();

        	checkCompatibility();

            server = createNeoServer();
            server.start();

            addShutdownHook();

            return OK;
        }
        catch ( TransactionFailureException tfe )
        {
            log.error( format( "Failed to start Neo Server on port [%d], because ",
            		configurator.configuration().get( ServerSettings.webserver_port ) )
                       + tfe + ". Another process may be using database location " + server.getDatabase()
                       .getLocation(), tfe );
            return GRAPH_DATABASE_STARTUP_ERROR_CODE;
        }
        catch ( IllegalArgumentException e )
        {
            log.error( format( "Failed to start Neo Server on port [%s]",
                    configurator.configuration().get( ServerSettings.webserver_port ) ), e );
            return WEB_SERVER_STARTUP_ERROR_CODE;
        }
        catch ( Exception e )
        {
            log.error( format( "Failed to start Neo Server on port [%s]",
                    configurator.configuration().get( ServerSettings.webserver_port ) ), e );
            return WEB_SERVER_STARTUP_ERROR_CODE;
        }
    }

    private Logging createLogging(ConfigurationBuilder configurator, Monitors monitors)
    {
        Config config = new Config( configurator.getDatabaseTuningProperties() );
        return life.add( DefaultLogging.createDefaultLogging( config, monitors ) );
    }

    private void checkCompatibility()
    {
        new JvmChecker( dependencies.logging().getMessagesLog( JvmChecker.class ),
                new JvmMetadataRepository() ).checkJvmCompatibilityAndIssueWarning();
    }

    protected abstract NeoServer createNeoServer();

	public void stop()
    {
        stop( 0 );
    }

    public int stop( int stopArg )
    {
        String location = "unknown location";
        try
        {
            if ( server != null )
            {
                server.stop();
            }
            log.log( "Successfully shutdown Neo Server on port [%d], database [%s]",
                    configurator.configuration().get( ServerSettings.webserver_port ),
                    location );

            removeShutdownHook();

            life.shutdown();

            return 0;
        }
        catch ( Exception e )
        {
            log.error( "Failed to cleanly shutdown Neo Server on port [%d], database [%s]. Reason [%s] ",
                    configurator.configuration().get( ServerSettings.webserver_port ), location, e.getMessage(), e );
            return 1;
        }
    }

    protected void removeShutdownHook()
    {
        if ( shutdownHook != null )
        {
            if ( !Runtime.getRuntime().removeShutdownHook( shutdownHook ) )
            {
                log.warn( "Unable to remove shutdown hook" );
            }
        }
    }

    public NeoServer getServer()
    {
        return server;
    }

    protected void addShutdownHook()
    {
        shutdownHook = new Thread()
        {
            @Override
            public void run()
            {
                log.log( "Neo4j Server shutdown initiated by request" );
                if ( server != null )
                {
                    server.stop();
                }
            }
        };
        Runtime.getRuntime()
                .addShutdownHook( shutdownHook );
    }

    protected Configurator createConfigurator( ConsoleLogger log )
    {
        return new ConfigurationBuilder.ConfigurationBuilderWrappingConfigurator( createConfigurationBuilder( log ) );
    }

    protected ConfigurationBuilder createConfigurationBuilder( ConsoleLogger log )
    {
        return new PropertyFileConfigurator( log );
    }

    protected boolean isMoreDerivedThan( Bootstrapper other )
    {
        // Default implementation just checks if this is a subclass of other
        return other.getClass()
                .isAssignableFrom( getClass() );
    }
}
