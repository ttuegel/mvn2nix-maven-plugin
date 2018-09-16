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

import java.io.UnsupportedEncodingException;
import java.net.URI;

import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.layout.RepositoryLayout;
import org.eclipse.aether.spi.connector.layout.RepositoryLayoutProvider;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterProvider;
import org.eclipse.aether.transfer.NoRepositoryLayoutException;
import org.eclipse.aether.transfer.NoTransporterException;

public class RemoteArtifact implements Comparable<RemoteArtifact> {

		public Artifact artifact;

		public RemoteRepository repo;

		RemoteArtifact(Artifact artifact_, RemoteRepository repo_) {
				artifact = artifact_;
				repo = repo_;
		}

		public Boolean
		equals(RemoteArtifact other) {
				Boolean result = true;
				result &= artifact.equals(other.artifact);
				result &= repo.equals(other.repo);
				return result;
		}

		public int
		compareTo(RemoteArtifact other) {
				if (!artifact.equals(other.artifact))
						return artifact.toString().compareTo(other.artifact.toString());
				if (!repo.equals(other.repo))
						return repo.getUrl().compareTo(other.repo.getUrl());
				return 0;
		}

		public ManifestEntry
		getEntry(
				RepositorySystemSession session,
				RepositoryLayoutProvider layoutProvider,
				TransporterProvider transporterProvider
		) throws MojoExecutionException
		{
				RepositoryLayout layout;
				try {
						layout = layoutProvider.newRepositoryLayout(session, repo);
				} catch (NoRepositoryLayoutException e) {
						throw new MojoExecutionException("Getting repository layout", e);
				}

				URI remoteLocation = layout.getLocation(artifact, false);

				String url = String.format("%s/%s", repo.getUrl(), remoteLocation);

				String sha1;
				try {
						sha1 = getChecksum(
								artifact,
								layout,
								transporterProvider.newTransporter(session, repo)
						);
				} catch (NoTransporterException e) {
						throw new MojoExecutionException(
								"No transporter for " + artifact.toString(),
								e
						);
				}

				String path = getPathForLocalArtifact(session, artifact);

				return new ManifestEntry(path, url, sha1);
    }

		private String
		getPathForLocalArtifact(
				RepositorySystemSession session,
				Artifact artifact
		) {
				return
						session
						.getLocalRepositoryManager()
						.getPathForLocalArtifact(artifact);
		}

		private String
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
}
