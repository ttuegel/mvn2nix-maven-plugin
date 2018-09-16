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

import javax.json.stream.JsonGenerator;

/**
	* An entry for the manifest.
	*/
public class ManifestEntry
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
		private
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
