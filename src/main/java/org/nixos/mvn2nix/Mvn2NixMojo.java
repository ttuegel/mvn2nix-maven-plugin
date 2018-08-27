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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.json.Json;
import javax.json.stream.JsonGenerator;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.model.Plugin;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.LocalArtifactRequest;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.WorkspaceRepository;


/**
 * A Mojo to generate an artifact list for Nix to create a Maven repository.
 *
 * @author Thomas Tuegel
 */
@Mojo(name = "mvn2nix", requiresDependencyResolution = ResolutionScope.TEST)
public class Mvn2NixMojo extends AbstractMojo
{
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(property = "outputFile", defaultValue = "project-artifacts.json")
    private String outputFile;

    @Component
    private RepositorySystem repoSystem;

    private String getArtifactDownloadUrl(Artifact artifact)
    {
        // We actually need to use repoSystem to re-resolve the remote
        // repository for the artifact.
        ArtifactRepository repo = artifact.getRepository();
        return "";
    }

    @Override
    public void execute() throws MojoExecutionException
    {
        List<MavenProject> projects =
            session
            .getProjectDependencyGraph()
            .getSortedProjects();

        // Collect all artifacts from this project.
        // This only contains resolved artifacts, which right now means
        // static dependencies. If we invoked all the lifecycle phases
        // for the project first, it would contain all dependencies.
        // It also does not include plugins, but those are available from
        // project.getPluginArtifacts().
        Set<Artifact> artifacts = new HashSet<Artifact>();
        artifacts.addAll(project.getArtifacts());

        try {
            FileOutputStream output = new FileOutputStream(outputFile);
            JsonGenerator generator = Json.createGenerator(output);
            generator.writeStartArray();
            for (Artifact artifact: artifacts) {
                getLog()
                    .info("artifact "
                          + artifact.getGroupId()
                          + ":"
                          + artifact.getArtifactId());
                String url = getArtifactDownloadUrl(artifact);
                generator
                    .writeStartObject()
                    .write("groupId", artifact.getGroupId())
                    .write("artifactId", artifact.getArtifactId())
                    .write("version", artifact.getVersion())
                    .write("url", url)
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
