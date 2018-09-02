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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    @Parameter(defaultValue="${project.remotePluginRepositories}", readonly=true)
    private List<RemoteRepository> repos;

    @Component
    private RepositoryLayoutProvider layoutProvider;

    @Component
    private TransporterProvider transporterProvider;

    static private Set<Artifact> artifacts = new HashSet<Artifact>();

    private ArtifactDownloadInfo getArtifactDownloadInfo(Artifact artifact)
        throws MojoExecutionException
    {
        ArtifactDownloadInfo info = new ArtifactDownloadInfo();
        RepositorySystemSession repoSession = session.getRepositorySession();

        // Convert between the new API and aether's
        DefaultArtifact defaultArtifact = new DefaultArtifact(getCoordinates(artifact));

        ArtifactDescriptorRequest req = new ArtifactDescriptorRequest();
        req.setArtifact(defaultArtifact);
        req.setRepositories(repos);

        ArtifactDescriptorResult result;

        try {
            result = repoSystem.readArtifactDescriptor(repoSession, req);
        } catch (ArtifactDescriptorException e) {
            throw new MojoExecutionException("Getting descriptor for " + artifact.toString(), e);
        }

        URI fileLoc;

        try {
            fileLoc = new URI("");
        } catch (URISyntaxException e) {
            throw new MojoExecutionException("Building empty URI", e);
        }

        ArtifactRepository ar = result.getRepository();

        if (ar instanceof RemoteRepository) {
            RemoteRepository repo = (RemoteRepository) ar;
            String baseURL = repo.getUrl();

            RepositoryLayout layout;
            try {
                layout = layoutProvider.newRepositoryLayout(repoSession, repo);
            } catch (NoRepositoryLayoutException e) {
                throw new MojoExecutionException("Getting repository layout", e);
            }

            fileLoc = layout.getLocation(defaultArtifact, false);

            info.url = String.format("%s/%s", baseURL, fileLoc);

            RepositoryLayout.Checksum sha1 = layout
                .getChecksums(defaultArtifact, false, fileLoc).stream()
                .filter(ck -> ck.getAlgorithm().equals("SHA-1"))
                .findFirst()
                .orElseThrow(() -> new MojoExecutionException("No SHA-1 for " + artifact.toString()));

            GetTask task = new GetTask(sha1.getLocation());


            try {
                Transporter transporter = transporterProvider.newTransporter(repoSession, repo);
                transporter.get(task);
            } catch (NoTransporterException e) {
                throw new MojoExecutionException("No transporter for " + artifact.toString(), e);
            } catch (Exception e) {
                throw new MojoExecutionException("Downloading SHA-1 for " + artifact.toString(), e);
            }

            try {
                info.hash = new String(task.getDataBytes(), 0, 40, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw new MojoExecutionException("Your JVM doesn't support UTF-8, fix that", e);
            }
        }


        return info;
    }

    @Override
    public void execute()
        throws MojoExecutionException
    {
        // Collect all artifacts from this project.
        for (Artifact artifact: project.getArtifacts()) {
            artifacts.add(artifact);
        }
        // Collect all plugin artifacts from this project.
        for (Artifact artifact: project.getPluginArtifacts()) {
            artifacts.add(artifact);
        }

        if (project == projects.get(projects.size() - 1)) {
            // This is the last project, so we should write the manifest.
            try (FileOutputStream output = new FileOutputStream(outputFile)) {
                JsonGenerator generator = Json.createGenerator(output);
                generator.writeStartArray();
                for (Artifact artifact: artifacts) {
                    getLog()
                        .info("artifact " + getCoordinates(artifact));
                    ArtifactDownloadInfo info = getArtifactDownloadInfo(artifact);

                    generator
                        .writeStartObject()
                        .write("groupId", artifact.getGroupId())
                        .write("artifactId", artifact.getArtifactId())
                        .write("version", artifact.getVersion())
                        .write("url", info.url)
                        .write("sha1", info.hash)
                        .writeEnd();
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

    private class ArtifactDownloadInfo
    {
        public String url;
        public String hash;

        public ArtifactDownloadInfo() {
            url = "";
            hash = "";
        }
    }
}
