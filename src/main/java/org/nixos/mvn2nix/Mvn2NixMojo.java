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

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.json.Json;
import javax.json.stream.JsonGenerator;

import org.apache.maven.artifact.Artifact;
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

import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
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

    static private SortedSet<NixArtifact> artifacts =
				Collections.synchronizedSortedSet(new TreeSet<NixArtifact>());

    private Optional<NixArtifact>
    resolveNixArtifact(
        Artifact artifact,
        List<RemoteRepository> repos
    )
        throws MojoExecutionException
    {
        RepositorySystemSession repoSession = session.getRepositorySession();
        DefaultArtifact defaultArtifact = toDefaultArtifact(artifact);

        ArtifactDescriptorRequest request = new ArtifactDescriptorRequest();
        request.setArtifact(defaultArtifact);
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

            URI remoteLocation = layout.getLocation(defaultArtifact, false);

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

								sha1 = getChecksum(defaultArtifact, layout, transporter);
						} catch (NoTransporterException e) {
								throw new MojoExecutionException(
										"No transporter for " + artifact.toString(),
										e
								);
						}

            return Optional.of(new NixArtifact(defaultArtifact, url, sha1));
        } else {
            return Optional.empty();
        }
    }

		public String
		getChecksum(
				DefaultArtifact artifact,
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

    private DefaultArtifact
    toDefaultArtifact(Artifact artifact)
    {
        return new DefaultArtifact(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            artifact.getClassifier(),
            artifact.getArtifactHandler().getExtension(),
            artifact.getVersion()
        );
    }

    @Override
    public void execute()
        throws MojoExecutionException
    {
        // Collect all artifacts from this project.
        for (Artifact artifact: project.getArtifacts()) {
            resolveNixArtifact(
                artifact,
                project.getRemoteProjectRepositories()
						)
            .ifPresent(
                a -> artifacts.add(a)
            );
        }
        // Collect all plugin artifacts from this project.
        for (Artifact artifact: project.getPluginArtifacts()) {
            resolveNixArtifact(
								artifact,
						    project.getRemotePluginRepositories()
						)
            .ifPresent(
                a -> artifacts.add(a)
            );
        }

        if (project == projects.get(projects.size() - 1)) {
            // This is the last project, so we should write the manifest.
            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                JsonGenerator generator = Json.createGenerator(output);
                generator.writeStartArray();
                for (NixArtifact artifact: artifacts) {
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

    private static String getCoordinates(Artifact artifact)
    {
        String coords;

        if (artifact.hasClassifier()) {
            coords = String.format("%s:%s:%s:%s:%s",
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getArtifactHandler().getExtension(),
                        artifact.getClassifier(),
                        artifact.getVersion());
        } else {
            coords = String.format("%s:%s:%s:%s",
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getArtifactHandler().getExtension(),
                        artifact.getVersion());
        }

        return coords;
    }

    private final class NixArtifact
				implements Comparable<NixArtifact> {
        String groupId;
        String artifactId;
        String extension;
        Optional<String> classifier;
        String version;
        String url;
        String sha1;

        public
        NixArtifact(
            DefaultArtifact artifact,
            String url_,
            String sha1_
        ) {
            groupId = artifact.getGroupId();
            artifactId = artifact.getArtifactId();
            extension = artifact.getExtension();
            if (artifact.getClassifier().length() > 0) {
                classifier = Optional.of(artifact.getClassifier());
            } else {
                classifier = Optional.empty();
            }
            version = artifact.getVersion();
            url = url_;
            sha1 = sha1_;
        }

				public int
				compareTo(NixArtifact other) {
						if (!groupId.equals(other.groupId))
								return groupId.compareTo(other.groupId);
						if (!artifactId.equals(other.artifactId))
								return artifactId.compareTo(other.artifactId);
						if (!version.equals(other.version))
								return version.compareTo(other.version);
						if (!extension.equals(other.extension))
								return extension.compareTo(other.extension);
						if (!classifier.equals(other.classifier))
								return
										classifier.map(
												c1 -> other.classifier.map(
														c2 -> c1.compareTo(c2)
												).orElse(1)
										).orElse(-1);
						if (!url.equals(other.url))
								return url.compareTo(other.url);
						if (!sha1.equals(other.sha1))
								return sha1.compareTo(other.sha1);
						return 0;
				}

        public void
        write(JsonGenerator generator) {
            generator.writeStartObject();

            generator.write("groupId", groupId)
                .write("artifactId", artifactId)
                .write("extension", extension)
                .write("version", version)
                .write("url", url)
                .write("sha1", sha1);

            classifier.ifPresent(
                c -> generator.write("classifier", c)
            );

            generator.writeEnd();
        }

				public Boolean
				equals(NixArtifact other) {
						Boolean result = true;
						result &= groupId.equals(other.groupId);
						result &= artifactId.equals(other.artifactId);
						result &= extension.equals(other.extension);
						result &= version.equals(other.version);
						result &= url.equals(other.url);
						result &= sha1.equals(other.sha1);
						result &= classifier.equals(other.classifier);
						return result;
				}
    }
}
