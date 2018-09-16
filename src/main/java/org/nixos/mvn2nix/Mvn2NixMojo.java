/*
 * Copyright (c) 2018 Thomas Tuegel
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 * CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package org.nixos.mvn2nix;

import org.nixos.mvn2nix.ManifestEntry;
import org.nixos.mvn2nix.RemoteArtifact;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.stream.JsonGenerator;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.resolution.VersionRequest;
import org.eclipse.aether.resolution.VersionResolutionException;
import org.eclipse.aether.resolution.VersionResult;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutProvider;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;


/**
 * A Mojo to generate an artifact list for Nix to create a Maven repository.
 *
 * @author Thomas Tuegel
 */
@Mojo( name = "mvn2nix",
       executionStrategy = "once-per-session",
       requiresDependencyResolution = ResolutionScope.TEST,
       requiresOnline = true,
       requiresProject = true
     )
@Execute ( phase = LifecyclePhase.TEST_COMPILE )
public class Mvn2NixMojo extends AbstractMojo
{
    @Parameter(defaultValue="${session}", required=true)
    private MavenSession session;

    @Parameter(defaultValue="${project}", required=true)
    private MavenProject project;

    @Parameter(defaultValue="${reactorProjects}", readonly=true)
    private List<MavenProject> projects;

    @Parameter(defaultValue="manifest.json", readonly=true)
    private String outputFile;

    @Component
    private RepositorySystem repoSystem;

    @Component
    private RepositoryLayoutProvider layoutProvider;

    @Component
    private TransporterProvider transporterProvider;

    static private SortedSet<RemoteArtifact> artifacts =
				Collections.synchronizedSortedSet(
						new TreeSet<RemoteArtifact>()
				);

    private Boolean
    resolveArtifact(
        org.eclipse.aether.artifact.Artifact artifact,
        List<RemoteRepository> repos
    )
        throws MojoExecutionException
    {
				// Don't use isSnapshot() here, which also returns true for resolved
				// snapshot versions.
				if (artifact.getVersion().endsWith("-SNAPSHOT")) {
						getLog().warn(
								"Ignoring snapshot " + artifact.toString()
						);
						return false;
				}

				// Read the artifact descriptor to determine the correct remote
				// repository. The correct remote repository may be "none" for local
				// artifacts! We must not use a resolution request, as this will always
				// return a remote result, even for reactor artifacts.
				ArtifactDescriptorResult result;
        try {
						result =
								repoSystem.readArtifactDescriptor(
										session.getRepositorySession(),
										new ArtifactDescriptorRequest(artifact, repos, null)
								);
				} catch (ArtifactDescriptorException e) {
            throw new MojoExecutionException(
                "Getting artifact descriptor for " + artifact.toString(),
                e
            );
				}

				ArtifactRepository repo = result.getRepository();
				if (result.getRepository() instanceof RemoteRepository) {
						RemoteArtifact remote =
								new RemoteArtifact(
										artifact,
										(RemoteRepository) repo
								);
						artifacts.add(remote);
						return true;
				} else {
						return false;
				}
		}

    private Artifact
    ResolverArtifact(org.apache.maven.artifact.Artifact artifact)
    {
        return new DefaultArtifact(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            artifact.getClassifier(),
            artifact.getArtifactHandler().getExtension(),
            artifact.getVersion()
        );
    }

		private Exclusion
		ResolverExclusion(org.apache.maven.model.Exclusion exclusion)
		{
				return new Exclusion(
						exclusion.getGroupId(),
						exclusion.getArtifactId(),
						null,
						null
				);
		}

		private Dependency
		ResolverDependency(org.apache.maven.model.Dependency dependency)
		{
				return new Dependency(
						new DefaultArtifact(
								dependency.getGroupId(),
								dependency.getArtifactId(),
								dependency.getClassifier(),
								dependency.getType(),
								dependency.getVersion()
						),
						dependency.getScope(),
						dependency.isOptional(),
						dependency
						.getExclusions()
						.stream()
						.map((ex) -> ResolverExclusion(ex))
						.collect(Collectors.toList())
				);
		}

		private void resolveDependency(
				Dependency dependency,
				List<RemoteRepository> repos
		)
        throws MojoExecutionException
		{
				Boolean isRemote =
						resolveArtifact(
								dependency.getArtifact(),
								repos
						);

				if (!isRemote)
						return;

				try {
						DependencyResult result =
								repoSystem.resolveDependencies(
										session.getRepositorySession(),
										new DependencyRequest(
												new CollectRequest(dependency, repos),
												null  // return all dependencies
										)
								);
						for (ArtifactResult artifactResult : result.getArtifactResults()) {
								resolveArtifact(artifactResult.getArtifact(), repos);
						}
				} catch (DependencyResolutionException e) {
            throw new MojoExecutionException(
                "Resolving dependencies for " + dependency.toString(),
                e
            );
				}
		}

		private void
		resolvePlugin(
				Plugin plugin,
				List<RemoteRepository> repos
		)
        throws MojoExecutionException
		{
				resolveDependency(
						new Dependency(
								new DefaultArtifact(
										plugin.getGroupId(),
										plugin.getArtifactId(),
										"jar",
										plugin.getVersion()
								),
								"compile"
						),
						repos
				);

				for (org.apache.maven.model.Dependency dependency :
								 plugin.getDependencies()) {
						resolveDependency(ResolverDependency(dependency), repos);
				}
		}

		private void
		resolveExtension(
				org.apache.maven.model.Extension extension,
				List<RemoteRepository> repos
		)
        throws MojoExecutionException
		{
				resolveDependency(
						new Dependency(
								new DefaultArtifact(
										extension.getGroupId(),
										extension.getArtifactId(),
										"jar",
										extension.getVersion()
								),
								"compile"
						),
						repos
				);
		}

    @Override
    public void execute()
        throws MojoExecutionException
    {
				// Collect parent artifact
				resolveArtifact(
						ResolverArtifact(project.getParentArtifact()),
						project.getRemoteProjectRepositories()
				);

        // Collect dependency artifacts
				for (org.apache.maven.model.Dependency dependency :
								 project.getDependencies()) {
						resolveDependency(
								ResolverDependency(dependency),
								project.getRemoteProjectRepositories()
						);
				}

        // Collect project plugin artifacts
				for (Plugin plugin :
								 project.getPluginManagement().getPlugins()) {
						resolvePlugin(
								plugin,
								project.getRemotePluginRepositories()
						);
				}

        // Collect build plugin artifacts
				for (Plugin plugin :
								 project.getBuild().getPlugins()) {
						resolvePlugin(
								plugin,
								project.getRemotePluginRepositories()
						);
				}

        // Collect build extension artifacts
				for (org.apache.maven.model.Extension extension :
								 project.getBuild().getExtensions()) {
						resolveExtension(
								extension,
								project.getRemotePluginRepositories()
						);
				}

        if (project == projects.get(projects.size() - 1)) {
            // This is the last project, so we should write the manifest.
            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                JsonGenerator generator = Json.createGenerator(output);
                generator.writeStartArray();
                for (RemoteArtifact artifact : artifacts) {
										artifact
												.getEntry(
														session.getRepositorySession(),
														layoutProvider,
														transporterProvider
												)
												.write(generator);
                }
                generator.writeEnd();
                generator.close();
            } catch (FileNotFoundException e) {
                throw new MojoExecutionException("Writing " + outputFile, e);
            } catch (IOException e) {
                throw new MojoExecutionException("Writing " + outputFile, e);
            }
        }
    }
}
