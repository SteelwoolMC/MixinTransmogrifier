package io.github.steelwoolmc.mixintransmog;

import cpw.mods.modlauncher.LaunchPluginHandler;
import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.TransformationServiceDecorator;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.MixinLaunchPlugin;
import org.spongepowered.asm.launch.MixinLaunchPluginLegacy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.github.steelwoolmc.mixintransmog.Constants.LOG;

public class MixinTransformationService implements ITransformationService {
    /**
     * Replace the original mixin launch plugin
     */
    private static void replaceMixinLaunchPlugin() {
        try {
            // This is a hack to get our shaded mixin to behave properly, as otherwise it tries to uses the thread classloader and then fails to load things
            // TODO what are the consequences of this? seems like it could potentially have rather bad unintended consequences
            var classLoader = MixinTransformationService.class.getClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);

            // Use reflection to get the loaded launch plugins
            var launcherLaunchPluginsField = Launcher.class.getDeclaredField("launchPlugins");
            launcherLaunchPluginsField.setAccessible(true);
            var launchPluginHandlerPluginsField = LaunchPluginHandler.class.getDeclaredField("plugins");
            launchPluginHandlerPluginsField.setAccessible(true);
            Map<String, ILaunchPluginService> plugins = (Map) launchPluginHandlerPluginsField.get(launcherLaunchPluginsField.get(Launcher.INSTANCE));

            // Replace original mixin with our mixin
            plugins.put("mixin", new MixinLaunchPlugin());
            LOG.debug("Replaced the mixin launch plugin");

            // Launch plugin services are only loaded from the BOOT layer, so we must inject ours manually
            plugins.computeIfAbsent("mixin-transmogrifier", key -> new ShadedMixinPluginService());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public MixinTransformationService() {
        LOG.info("Mixin Transmogrifier is definitely up to no good...");
        replaceMixinLaunchPlugin();
        LOG.info("crimes against java were committed");
    }

    @Override
    public String name() {
        return "mixin-transmogrifier";
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        LOG.debug("onLoad called");
        LOG.debug(otherServices.stream().collect(Collectors.joining(", ")));

        try {
            Field handlerField = Launcher.class.getDeclaredField("transformationServicesHandler");
            Object handler = handlerField.get(Launcher.INSTANCE);
            Field serviceLookupField = handler.getClass().getDeclaredField("serviceLookup");
            Map<String, TransformationServiceDecorator> serviceLookup = (Map) serviceLookupField.get(handler);
            Constructor<TransformationServiceDecorator> ctr = TransformationServiceDecorator.class.getDeclaredConstructor(ITransformationService.class);
            ctr.setAccessible(true);
            // Silently replace service, avoiding a ConcurrentModificationException
            serviceLookup.put("mixin", ctr.newInstance(new DummyMixinTransformationService()));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public void initialize(IEnvironment environment) {
        try {
            LOG.debug("initialize called");

            var mixinBootstrapStartMethod = MixinBootstrap.class.getDeclaredMethod("start");
            mixinBootstrapStartMethod.setAccessible(true);

            Optional<ILaunchPluginService> plugin = environment.findLaunchPlugin("mixin");
            if (plugin.isEmpty()) {
                throw new Error("Mixin Launch Plugin Service could not be located");
            }
            ILaunchPluginService launchPlugin = plugin.get();
            if (!(launchPlugin instanceof MixinLaunchPluginLegacy)) {
                throw new Error("Mixin Launch Plugin Service is present but not compatible");
            }

            var mixinPluginInitMethod = MixinLaunchPluginLegacy.class.getDeclaredMethod("init", IEnvironment.class, List.class);
            mixinPluginInitMethod.setAccessible(true);

            // The actual init invocations
            mixinBootstrapStartMethod.invoke(null);
            mixinPluginInitMethod.invoke(launchPlugin, environment, List.of());
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<ITransformer> transformers() {
        return List.of();
    }
}
