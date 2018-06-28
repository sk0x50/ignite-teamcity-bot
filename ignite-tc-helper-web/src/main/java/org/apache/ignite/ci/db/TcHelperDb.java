package org.apache.ignite.ci.db;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import org.apache.ignite.Ignite;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.CacheInterceptorAdapter;
import org.apache.ignite.cache.affinity.rendezvous.RendezvousAffinityFunction;
import org.apache.ignite.ci.HelperConfig;
import org.apache.ignite.ci.util.ObjectInterner;
import org.apache.ignite.configuration.*;
import org.apache.ignite.logger.java.JavaLogger;
import org.apache.ignite.spi.IgniteSpiContext;
import org.apache.ignite.spi.IgniteSpiException;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.TcpDiscoveryIpFinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.ci.web.Launcher.waitStopSignal;

/**
 * Created by dpavlov on 04.08.2017
 */
public class TcHelperDb {

    public static void main(String[] args) {
        Ignite ignite = start();

        System.out.println("Starting Ignite DB only");

        Runnable r = () -> {
            boolean stop = waitStopSignal();

            if (stop) {
                try {
                    TcHelperDb.stop(ignite);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }

        };
        new Thread(r).start();
    }

    public static Ignite start() {
        final IgniteConfiguration cfg = new IgniteConfiguration();
        setWork(cfg, HelperConfig.resolveWorkDir());

        setupDisco(cfg);
        cfg.setConsistentId("TcHelper");
        cfg.setGridLogger(new JavaLogger());

        final DataRegionConfiguration regConf = new DataRegionConfiguration()
            .setMaxSize(12L * 1024 * 1024 * 1024)
            .setPersistenceEnabled(true);

        final DataStorageConfiguration dsCfg = new DataStorageConfiguration()
            .setWalMode(WALMode.LOG_ONLY)
            .setWalHistorySize(1)
            .setCheckpointFrequency(60 * 1000)
            .setWriteThrottlingEnabled(true)
            .setDefaultDataRegionConfiguration(regConf);

        cfg.setDataStorageConfiguration(dsCfg);

        System.out.println("Starting Ignite Server Node");

        final Ignite ignite = Ignition.start(cfg);

        System.out.println("Activating Ignite Server Node");

        ignite.cluster().active(true);


        System.out.println("Activate completed");

        return ignite;
    }

    public static Ignite startClient() {
        final IgniteConfiguration cfg = new IgniteConfiguration();

        setupDisco(cfg);

        cfg.setGridLogger(new JavaLogger());

        cfg.setClientMode(true);

        final Ignite ignite = Ignition.start(cfg);
        ignite.cluster().active(true);
        return ignite;
    }

    private static void setupDisco(IgniteConfiguration cfg) {
        final TcpDiscoverySpi spi = new TcpDiscoverySpi();
        final int locPort = 54433;
        spi.setLocalPort(locPort);
        spi.setLocalPortRange(1);
        spi.setIpFinder(new LocalOnlyTcpDiscoveryIpFinder(locPort));

        cfg.setDiscoverySpi(spi);
    }

    private static void setWork(IgniteConfiguration cfg, File workDir) {
        try {
            cfg.setIgniteHome(workDir.getCanonicalPath());
        }
        catch (IOException e) {
            e.printStackTrace();
            cfg.setIgniteHome(workDir.getAbsolutePath());
        }
    }

    public static void stop(Ignite ignite) {
        Ignition.stop(ignite.name(), false);
    }

    @NotNull
    public static <K, V> CacheConfiguration<K, V> getCacheV2Config(String name) {
        CacheConfiguration<K, V> ccfg = new CacheConfiguration<>(name);

        ccfg.setAffinity(new RendezvousAffinityFunction(false, 32));

        /*
        ccfg.setInterceptor(new CacheInterceptorAdapter<K, V>(){
            @Nullable @Override public V onGet(K key, V val) {
                V v = super.onGet(key, val);

                int i = ObjectInterner.internFields(v);

                if(i>0)
                    System.out.println("cache.get: Strings saved: " + i);

                return v;
            }
        });*/

        return ccfg;
    }

    private static class LocalOnlyTcpDiscoveryIpFinder implements TcpDiscoveryIpFinder {
        private int port;

        public LocalOnlyTcpDiscoveryIpFinder(int port) {
            this.port = port;
        }

        @Override public void onSpiContextInitialized(IgniteSpiContext spiCtx) throws IgniteSpiException {

        }

        @Override public void onSpiContextDestroyed() {

        }

        @Override
        public void initializeLocalAddresses(Collection<InetSocketAddress> addrs) throws IgniteSpiException {

        }

        @Override public Collection<InetSocketAddress> getRegisteredAddresses() throws IgniteSpiException {
            return Collections.singletonList(new InetSocketAddress("localhost", port));
        }

        @Override public boolean isShared() {
            return false;
        }

        @Override public void registerAddresses(Collection<InetSocketAddress> addrs) throws IgniteSpiException {

        }

        @Override public void unregisterAddresses(Collection<InetSocketAddress> addrs) throws IgniteSpiException {

        }

        @Override public void close() {

        }
    }
}
