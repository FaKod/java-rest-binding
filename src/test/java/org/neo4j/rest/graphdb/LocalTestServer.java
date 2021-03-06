/**
 * Copyright (c) 2002-2012 "Neo Technology,"
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
package org.neo4j.rest.graphdb;

import org.apache.commons.configuration.Configuration;
import org.mortbay.component.LifeCycle;
import org.mortbay.jetty.Server;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.kernel.AbstractGraphDatabase;
import org.neo4j.server.Bootstrapper;
import org.neo4j.server.NeoServerWithEmbeddedWebServer;
import org.neo4j.server.configuration.PropertyFileConfigurator;
import org.neo4j.server.database.Database;
import org.neo4j.server.database.GraphDatabaseFactory;
import org.neo4j.server.modules.RESTApiModule;
import org.neo4j.server.modules.ServerModule;
import org.neo4j.server.modules.ThirdPartyJAXRSModule;
import org.neo4j.server.startup.healthcheck.StartupHealthCheck;
import org.neo4j.server.startup.healthcheck.StartupHealthCheckRule;
import org.neo4j.server.web.Jetty6WebServer;
import org.neo4j.test.ImpermanentGraphDatabase;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author mh
 * @since 24.03.11
 */
public class LocalTestServer {
    private NeoServerWithEmbeddedWebServer neoServer;
    private final int port;
    private final String hostname;
    protected String propertiesFile = "test-db.properties";

    public LocalTestServer() {
        this("localhost",7473);
    }

    public LocalTestServer(String hostname, int port) {
        this.port = port;
        this.hostname = hostname;
    }

    public void start() {
        if (neoServer!=null) throw new IllegalStateException("Server already running");
        URL url = getClass().getResource("/" + propertiesFile);
        if (url==null) throw new IllegalArgumentException("Could not resolve properties file "+propertiesFile);
        final List<Class<? extends ServerModule>> serverModules = Arrays.asList(RESTApiModule.class, ThirdPartyJAXRSModule.class);
        final Bootstrapper bootstrapper = new Bootstrapper() {
            @Override
            protected GraphDatabaseFactory getGraphDatabaseFactory(Configuration configuration) {
                return new GraphDatabaseFactory() {
                    @Override
                    public AbstractGraphDatabase createDatabase(String databaseStoreDirectory, Map<String, String> databaseProperties) {
                       return new ImpermanentGraphDatabase();
                    }
                };
            }

            @Override
            protected Iterable<StartupHealthCheckRule> getHealthCheckRules() {
                return Collections.emptyList();
            }

            @Override
            protected Iterable<Class<? extends ServerModule>> getServerModules() {
                return serverModules;
            }
        };
        final Jetty6WebServer jettyWebServer = new Jetty6WebServer() {
            @Override
            protected void startJetty() {
                final Server jettyServer = getJetty();
                jettyServer.setStopAtShutdown(true);
                final JettyStartupListener startupListener = new JettyStartupListener();
                jettyServer.getServer().addLifeCycleListener(startupListener);
                // System.err.println("jetty is started before notification " + jettyServer.isStarted());

                super.startJetty();

                startupListener.await();
                // System.err.println("jetty is started after notification " + jettyServer.isStarted());
            }
        };
        neoServer = new NeoServerWithEmbeddedWebServer(bootstrapper, new StartupHealthCheck(), new PropertyFileConfigurator(new File(url.getPath())), jettyWebServer, serverModules) {
            @Override
            protected int getWebServerPort() {
                return port;
            }
        };
        neoServer.start();
    }

    public void stop() {
        try {
        neoServer.stop();
        } catch(Exception e) {
            System.err.println("Error stopping server: "+e.getMessage());
        }
        neoServer=null;
    }

    public int getPort() {
        return port;
    }

    public String getHostname() {
        return hostname;
    }

    public LocalTestServer withPropertiesFile(String propertiesFile) {
        this.propertiesFile = propertiesFile;
        return this;
    }
    public Database getDatabase() {
        return neoServer.getDatabase();
    }

    public URI baseUri() {
        return neoServer.baseUri();
    }

    public void cleanDb() {
        Neo4jDatabaseCleaner cleaner = new Neo4jDatabaseCleaner(getGraphDatabase());
        cleaner.cleanDb();
    }

    public GraphDatabaseService getGraphDatabase() {
        return getDatabase().graph;
    }

    private static class JettyStartupListener implements LifeCycle.Listener {
        CountDownLatch latch=new CountDownLatch(1);
        public void await() {
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch(InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        }

        @Override
        public void lifeCycleStarting(LifeCycle event) {
        }

        @Override
        public void lifeCycleStarted(LifeCycle event) {
            latch.countDown();
        }

        @Override
        public void lifeCycleFailure(LifeCycle event, Throwable cause) {
            latch.countDown();
            throw new RuntimeException(cause);
        }

        @Override
        public void lifeCycleStopping(LifeCycle event) {

        }

        @Override
        public void lifeCycleStopped(LifeCycle event) {
            latch.countDown();
        }
    }
}
