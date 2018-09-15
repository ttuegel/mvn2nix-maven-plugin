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

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.stream.JsonGenerator;

import org.apache.maven.execution.MavenSession;
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
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutProvider;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;
import org.eclipse.aether.transfer.NoRepositoryLayoutException;
import org.eclipse.aether.transfer.NoTransporterException;


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

    static private SortedSet<ManifestEntry> artifacts =
				Collections.synchronizedSortedSet(new TreeSet<ManifestEntry>());

    private Optional<ManifestEntry>
    resolveArtifact(
        org.eclipse.aether.artifact.Artifact artifact,
        List<RemoteRepository> repos
    )
        throws MojoExecutionException
    {
        RepositorySystemSession repoSession = session.getRepositorySession();

        ArtifactDescriptorRequest request = new ArtifactDescriptorRequest();
        request.setArtifact(artifact);
        request.setRepositories(repos);

        ArtifactDescriptorResult result;
        try {
            result = repoSystem.readArtifactDescriptor(repoSession, request);
        } catch (ArtifactDescriptorException e) {
            throw new MojoExecutionException(
                "Getting descriptor for " + artifact.toString(),
                e
            );
        }

        ArtifactRepository artifactRepo = result.getRepository();
        if (artifactRepo instanceof RemoteRepository) {
            RemoteRepository remoteRepo = (RemoteRepository) artifactRepo;

            RepositoryLayout layout;
            try {
                layout =
                    layoutProvider.newRepositoryLayout(
                        repoSession,
                        remoteRepo
                    );
            } catch (NoRepositoryLayoutException e) {
                throw new MojoExecutionException("Getting repository layout", e);
            }

            URI remoteLocation = layout.getLocation(artifact, false);

						String url =
								String.format(
										"%s/%s",
										remoteRepo.getUrl(),
										remoteLocation
								);

						String sha1;
						try {
								Transporter transporter =
										transporterProvider
										.newTransporter(repoSession, remoteRepo);

								sha1 = getChecksum(artifact, layout, transporter);
						} catch (NoTransporterException e) {
								throw new MojoExecutionException(
										"No transporter for " + artifact.toString(),
										e
								);
						}

						String path = getPathForLocalArtifact(artifact);

            return Optional.of(new ManifestEntry(path, url, sha1));
        } else if (artifactRepo instanceof WorkspaceRepository) {
						// The artifact is part of the current workspace,
            // so there is nothing to do.
						return Optional.empty();
				} else {
						getLog().warn(
								"No repository for " + artifact.toString()
						);
            return Optional.empty();
        }
    }

		public String
		getPathForLocalArtifact(
				Artifact artifact
		) {
				return
						session
						.getRepositorySession()
						.getLocalRepositoryManager()
						.getPathForLocalArtifact(artifact);
		}

		public String
		getChecksum(
				Artifact artifact,
				RepositoryLayout layout,
				Transporter transporter
		)
				throws MojoExecutionException {
				URI checksumLocation =
						layout
						.getChecksums(artifact, false, layout.getLocation(artifact, false))
						.stream()
						.filter(ck -> ck.getAlgorithm().equals("SHA-1"))
						.findFirst()
						.orElseThrow(
								() ->
								new MojoExecutionException(
										"No SHA-1 for " + artifact.toString()
								)
						)
						.getLocation();

				GetTask task = new GetTask(checksumLocation);
				try {
						transporter.get(task);
				} catch (Exception e) {
						throw new MojoExecutionException(
								"Downloading SHA-1 for " + artifact.toString(),
								e
						);
				}

				String sha1;
				try {
						sha1 = new String(task.getDataBytes(), 0, 40, "UTF-8");
				} catch (UnsupportedEncodingException e) {
						throw new MojoExecutionException(
								"Your JVM doesn't support UTF-8, fix that",
								e
						);
				}

				return sha1;
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
				DependencyRequest request = new DependencyRequest();
				request.setCollectRequest(new CollectRequest(dependency, repos));

				DependencyResult result;
				try {
						result =
								repoSystem.resolveDependencies(
										session.getRepositorySession(),
										request
								);
				} catch (DependencyResolutionException e) {
            throw new MojoExecutionException(
                "Resolving dependencies for " + dependency.toString(),
                e
            );
				}

				for (ArtifactResult artifactResult : result.getArtifactResults()) {
						resolveArtifact(artifactResult.getArtifact(), repos);
				}
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

        // Collect plugin artifacts
				// TODO

        // Collect build extension artifacts
				// TODO

        if (project == projects.get(projects.size() - 1)) {
            // This is the last project, so we should write the manifest.
            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                JsonGenerator generator = Json.createGenerator(output);
                generator.writeStartArray();
                for (ManifestEntry artifact: artifacts) {
                    artifact.write(generator);
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

		/**
		 * An entry for the manifest.
		 */
    private final class ManifestEntry
				implements Comparable<ManifestEntry> {
				/**
				 * The path of the artifact in the local repository.
				 */
        String path;

				/**
				 * The URL of the remote artifact.
				 */
        String url;

				/**
				 * The SHA-1 checksum of the remote artifact.
				 */
        String sha1;

        public
        ManifestEntry(
						String path_,
            String url_,
            String sha1_
        ) {
						path = path_;
            url = url_;
            sha1 = sha1_;
        }

				public int
				compareTo(ManifestEntry other) {
						if (!path.equals(other.path))
								return path.compareTo(other.path);
						if (!url.equals(other.url))
								return url.compareTo(other.url);
						if (!sha1.equals(other.sha1))
								return sha1.compareTo(other.sha1);
						return 0;
				}

				public Boolean
				equals(ManifestEntry other) {
						Boolean result = true;
						result &= path.equals(other.path);
						result &= sha1.equals(other.sha1);
						result &= url.equals(other.url);
						return result;
				}

				/**
				 * Record this artifact in the JSON manifest.
				 */
        public void
        write(JsonGenerator generator) {
            generator
								.writeStartObject()
								.write("path", path)
                .write("url", url)
                .write("sha1", sha1)
								.writeEnd();
        }
    }
}
