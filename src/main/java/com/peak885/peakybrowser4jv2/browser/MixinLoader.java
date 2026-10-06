package com.peak885.peakybrowser4jv2.browser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.util.ReEntranceLock;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.IllegalClassFormatException;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.Collections;

public class MixinLoader
        implements IMixinService, IClassBytecodeProvider, IClassProvider {

    private static final IContainerHandle PRIMARY_CONTAINER =
            new PeakyContainerHandle();

    public static void Init() {
        Logger.info("[MIXIN] Bootstrapping Mixin environment...");

        // Fat-jar + non-Minecraft: never require a refmap. Without this,
        // Mixin resolves refmap against the browser jar itself and crashes.
        System.setProperty("mixin.env.disableRefMap", "true");
        System.setProperty("mixin.debug.export", "false");

        try {
            MixinBootstrap.init();
            Mixins.addConfiguration("peakybrowser.mixins.json");
            Logger.info("[MIXIN] Configuration registered (peakybrowser.mixins.json)");
        } catch (Throwable t) {
            // Audio no longer depends on mixins; keep the browser usable.
            Logger.warn(t, "[MIXIN] Bootstrap failed — continuing without mixins");
        }

        Logger.info("[MAIN] Starting PeakyBrowser4JV2...");
    }

    // -------------------------------------------------------------------------
    // IMixinService
    // -------------------------------------------------------------------------

    @Override
    public String getName() {
        return "PeakyVanillaService";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void prepare() {
    }

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    @Override
    public void offer(IMixinInternal internal) {
    }

    @Override
    public void init() {
    }

    @Override
    public void beginPhase() {
    }

    @Override
    public void checkEnv(Object bootSource) {
    }

    @Override
    public ReEntranceLock getReEntranceLock() {
        return new ReEntranceLock(1);
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return null;
    }

    @Override
    public IClassTracker getClassTracker() {
        return null;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public IFeatureValidator getFeatureValidator() {
        return new IFeatureValidator() {
            @Override
            public void validateEnumExtension(
                    IMixinInfo mixin,
                    ClassInfo target) {
            }
        };
    }

    @Override
    public IAdviceProvider getAdviceProvider() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.emptyList();
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return PRIMARY_CONTAINER;
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return Collections.emptyList();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        if (loader == null) {
            loader = MixinLoader.class.getClassLoader();
        }

        return loader.getResourceAsStream(name);
    }

    @Override
    public String getSideName() {
        return "CLIENT";
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return null;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return null;
    }

    @Override
    public ILogger getLogger(String name) {
        return new TinylogMixinAdapter(name);
    }

    // -------------------------------------------------------------------------
    // IClassProvider
    // -------------------------------------------------------------------------

    @Override
    public URL[] getClassPath() {
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name)
            throws ClassNotFoundException {
        return Class.forName(name);
    }

    @Override
    public Class<?> findClass(
            String name,
            boolean initialize)
            throws ClassNotFoundException {

        return Class.forName(
                name,
                initialize,
                Thread.currentThread().getContextClassLoader()
        );
    }

    @Override
    public Class<?> findAgentClass(
            String name,
            boolean initialize)
            throws ClassNotFoundException {

        return Class.forName(
                name,
                initialize,
                Thread.currentThread().getContextClassLoader()
        );
    }

    // -------------------------------------------------------------------------
    // IClassBytecodeProvider
    // -------------------------------------------------------------------------

    @Override
    public ClassNode getClassNode(String name)
            throws ClassNotFoundException, IOException {

        return getClassNode(name, true, 0);
    }

    @Override
    public ClassNode getClassNode(
            String name,
            boolean runTransformers)
            throws ClassNotFoundException, IOException {

        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(
            String name,
            boolean runTransformers,
            int readerFlags)
            throws ClassNotFoundException, IOException {

        String resourcePath =
                name.replace('.', '/') + ".class";

        try (InputStream input = getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new ClassNotFoundException(name);
            }

            ClassReader reader = new ClassReader(input);
            ClassNode node = new ClassNode();

            reader.accept(node, readerFlags);

            return node;
        }
    }

    // -------------------------------------------------------------------------
    // Tinylog adapter
    // -------------------------------------------------------------------------

    private static final class TinylogMixinAdapter implements ILogger {

        private final String id;

        private TinylogMixinAdapter(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getType() {
            return "Tinylog";
        }

        @Override
        public void catching(
                org.spongepowered.asm.logging.Level level,
                Throwable t) {

            Logger.error(t);
        }

        @Override
        public void catching(Throwable t) {
            Logger.error(t);
        }

        @Override
        public void debug(
                String message,
                Object... params) {

            Logger.debug(message, params);
        }

        @Override
        public void debug(
                String message,
                Throwable t) {

            Logger.debug(t, message);
        }

        @Override
        public void error(
                String message,
                Object... params) {

            Logger.error(message, params);
        }

        @Override
        public void error(
                String message,
                Throwable t) {

            Logger.error(t, message);
        }

        @Override
        public void fatal(
                String message,
                Object... params) {

            Logger.error(message, params);
        }

        @Override
        public void fatal(
                String message,
                Throwable t) {

            Logger.error(t, message);
        }

        @Override
        public void info(
                String message,
                Object... params) {

            Logger.info(message, params);
        }

        @Override
        public void info(
                String message,
                Throwable t) {

            Logger.info(t, message);
        }

        @Override
        public void log(
                org.spongepowered.asm.logging.Level level,
                String message,
                Object... params) {

            switch (level) {
                case TRACE:
                    Logger.debug(message, params);
                    break;

                case DEBUG:
                    Logger.debug(message, params);
                    break;

                case INFO:
                    Logger.info(message, params);
                    break;

                case WARN:
                    Logger.warn(message, params);
                    break;

                case ERROR:
                    Logger.error(message, params);
                    break;

                case FATAL:
                    Logger.error(message, params);
                    break;

                default:
                    Logger.info(message, params);
                    break;
            }
        }

        @Override
        public void log(
                org.spongepowered.asm.logging.Level level,
                String message,
                Throwable t) {

            switch (level) {
                case TRACE:
                case DEBUG:
                    Logger.debug(t, message);
                    break;

                case INFO:
                    Logger.info(t, message);
                    break;

                case WARN:
                    Logger.warn(t, message);
                    break;

                case ERROR:
                case FATAL:
                    Logger.error(t, message);
                    break;

                default:
                    Logger.error(t, message);
                    break;
            }
        }

        @Override
        public <T extends Throwable> T throwing(T t) {
            Logger.error(t);
            return t;
        }

        @Override
        public void trace(
                String message,
                Object... params) {

            Logger.debug(message, params);
        }

        @Override
        public void trace(
                String message,
                Throwable t) {

            Logger.debug(t, message);
        }

        @Override
        public void warn(
                String message,
                Object... params) {

            Logger.warn(message, params);
        }

        @Override
        public void warn(
                String message,
                Throwable t) {

            Logger.warn(t, message);
        }
    }
}