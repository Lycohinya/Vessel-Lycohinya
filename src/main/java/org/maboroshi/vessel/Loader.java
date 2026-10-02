package org.maboroshi.vessel;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

public class Loader implements PluginLoader {
    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();

        resolver.addRepository(
                new RemoteRepository.Builder("papermc", "default", "https://repo.papermc.io/repository/maven-public/")
                        .build());

        resolver.addDependency(new Dependency(new DefaultArtifact("de.exlll:configlib-paper:4.8.1"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("org.incendo:cloud-paper:2.0.1"), null));
        resolver.addDependency(new Dependency(new DefaultArtifact("org.incendo:cloud-annotations:2.1.0"), null));

        classpathBuilder.addLibrary(resolver);
    }
}
