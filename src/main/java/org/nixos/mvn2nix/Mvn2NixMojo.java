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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.json.Json;
import javax.json.stream.JsonGenerator;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Plugin;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.ArtifactRepository;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import org.nixos.mvn2nix.NixArtifact;


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
@Execute ( phase = LifecyclePhase.INSTALL )
public class Mvn2NixMojo extends AbstractMojo
{
    @Parameter(defaultValue="${session}", required=true)
    private MavenSession session;

    @Parameter(defaultValue="${project}", required=true)
    private MavenProject project;

    @Parameter(defaultValue="manifest.json", readonly=true)
    private File outputFile;

    @Component
    private RepositorySystem repoSystem;

    private String getArtifactDownloadUrl(Artifact artifact)
    {
        return "";
    }

    static private Set<NixArtifact> artifacts =
        new HashSet<NixArtifact>();

    public void execute()
        throws MojoExecutionException
    {

        // Collect all artifacts from this project.
        for (Artifact artifact: project.getArtifacts()) {
            // If the artifact is remote, or cached from a remote, then
            // resolve its download URL.
            // See also:
            //   org.eclipse.aether.RepositorySystem.resolveArtifact
            //   org.apache.maven.project.MavenProject.getRemoteProjectRepositories
            resolve(artifact, project.getRemoteProjectRepositories()).ifPresent(
                (NixArtifact a) -> artifacts.add(a)
            );
        }
        // Collect all plugin artifacts from this project.
        for (Artifact artifact: project.getPluginArtifacts()) {
            // If the artifact is remote, or cached from a remote, then
            // resolve its download URL.
            // See also:
            //   org.eclipse.aether.RepositorySystem.resolveArtifact
            //   org.apache.maven.project.MavenProject.getRemotePluginRepositories
            resolve(artifact, project.getRemotePluginRepositories()).ifPresent(
                (NixArtifact a) -> artifacts.add(a)
            );
        }

        List<MavenProject> projects =
            session
            .getProjectDependencyGraph()
            .getSortedProjects();

        if (project == projects.get(projects.size() - 1)) {
            // This is the last project, so we should write the manifest.
            try {
                FileOutputStream output = new FileOutputStream(outputFile);
                JsonGenerator generator = Json.createGenerator(output);
                generator.writeStartArray();
                for (NixArtifact artifact: artifacts) {
                    getLog().info("artifact " + artifact.getCoordinates());
                    generator
                        .writeStartObject()
                        .write("groupId", artifact.groupId)
                        .write("artifactId", artifact.artifactId)
                        .write("version", artifact.version)
                        .write("repo", artifact.repositoryUrl)
                        .writeEnd();
                }
                generator.writeEnd();
                generator.close();
                output.close();
            } catch (FileNotFoundException e) {
                throw new MojoExecutionException("Writing " + outputFile, e);
            } catch (IOException e) {
                throw new MojoExecutionException("Writing " + outputFile, e);
            }
        }
    }

    /**
     * Fully resolve the remote repository URL of an Artifact.
     */
    private Optional<NixArtifact>
    resolve( Artifact artifact,
             List<RemoteRepository> repos
           ) {
        ArtifactRequest request =
            new ArtifactRequest(
                toDefaultArtifact(artifact),
                repos,
                artifact.getScope()
            );

        ArtifactResult result = new ArtifactResult(request);
        try {
            result = repoSystem.resolveArtifact(
                session.getRepositorySession(),
                request
            );
        } catch (ArtifactResolutionException e) {
            getLog().warn(
                "Could not resolve artifact: "
                + artifact.getGroupId() + ":"
                + artifact.getArtifactId() + ":"
                + artifact.getVersion() + " " + e
            );
        }

        ArtifactRepository repo = result.getRepository();
        if (repo instanceof RemoteRepository) {
            RemoteRepository remote = (RemoteRepository) repo;
            return Optional.of(
                new NixArtifact( artifact.getGroupId(),
                                 artifact.getArtifactId(),
                                 artifact.getVersion(),
                                 remote.getUrl()
                               )
            );
        } else if (repo instanceof LocalRepository) {
            // Artifact is local.
            return Optional.empty();
        } else if (repo instanceof WorkspaceRepository) {
            // Artifact is local to this workspace.
            return Optional.empty();
        } else {
            getLog().warn(
                "Unknown repository type for artifact: "
                + artifact.getGroupId() + ":"
                + artifact.getArtifactId() + ":"
                + artifact.getVersion()
            );
            return Optional.empty();
        }
    }

    private DefaultArtifact toDefaultArtifact(Artifact artifact) {
        return new DefaultArtifact(
            artifact.getGroupId(),
            artifact.getArtifactId(),
            artifact.getClassifier(),
            artifact.getArtifactHandler().getExtension(),
            artifact.getVersion()
        );
    }
}
