/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.gradle.task;

import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * @author Brady
 * @since 10/12/2018
 */
public class CreateDistTask extends BaritoneGradleTask {

    private static MessageDigest SHA1_DIGEST;

    /**
     * Every enabled loader has its own createDist task and they all share dist/, so with parallel project execution
     * they can otherwise checksum a jar another task is halfway through writing, or race each other writing
     * checksums.txt. All three run in the same daemon, so one lock is enough to serialize the shared directory.
     */
    private static final Object DIST_LOCK = new Object();

    @TaskAction
    protected void exec() throws Exception {
        super.doFirst();
        super.verifyArtifacts();
        super.verifyProguardArtifact(this.artifactApiPath);

        if (this.compType == null) {
            // Without a compType every loader would write the same file name and silently overwrite each other.
            throw new IllegalStateException("createDist requires compType to be set to the loader name");
        }

        // One jar per loader, named after the loader it is for. This is the api build: everything outside
        // baritone.api is obfuscated, so it is both the jar a player installs and the one another mod can integrate
        // against. The standalone and unoptimized builds stay in each subproject's build directory, where the people
        // who want them (a little extra performance, or a readable stack trace) can still find them.
        Path dist = getRootRelativeFile("dist/" + getDistFileName());

        // NIO will not automatically create directories
        Files.createDirectories(getRootRelativeFile("dist/"));

        // Copy beside the target and move it into place, so that another loader's task can never checksum a
        // half-written jar. The copy itself needs no lock: the name is unique to this loader.
        Path partial = getRootRelativeFile("dist/" + getDistFileName() + ".part");
        Files.copy(this.artifactApiPath, partial, REPLACE_EXISTING);

        synchronized (DIST_LOCK) {
            try {
                Files.move(partial, dist, REPLACE_EXISTING, ATOMIC_MOVE);
            } catch (IOException | UnsupportedOperationException e) {
                Files.move(partial, dist, REPLACE_EXISTING);
            }

            // Drop this loader's leftovers: jars from earlier versions, the three-variants-per-loader names this
            // build no longer produces, and any part file a crashed run left behind. Nothing else prunes dist/, so
            // without this they would sit there being checksummed forever.
            String stalePattern = String.format(
                    "\\Q%s\\E-(api-|standalone-|unoptimized-)?\\Q%s\\E-.*\\.jar(\\.part)?",
                    this.artifactName,
                    this.compType
            );
            try (Stream<Path> old = Files.list(getRootRelativeFile("dist/"))) {
                List<Path> stale = old
                        .filter(e -> {
                            String name = e.getFileName().toString();
                            return name.matches(stalePattern) && !name.equals(getDistFileName());
                        })
                        .collect(Collectors.toList());
                for (Path path : stale) {
                    Files.deleteIfExists(path);
                }
            }

            // Calculate all checksums and format them like "shasum", sorted to keep the file stable no matter which
            // loader happens to run last.
            List<String> shasum;
            try (Stream<Path> jars = Files.list(getRootRelativeFile("dist/"))) {
                shasum = jars
                        .filter(e -> e.getFileName().toString().endsWith(".jar"))
                        .sorted()
                        .map(path -> sha1(path) + "  " + path.getFileName().toString())
                        .collect(Collectors.toList());
            }

            shasum.forEach(System.out::println);

            // Write the checksums to a file
            Files.write(getRootRelativeFile("dist/checksums.txt"), shasum);
        }
    }

    /**
     * @return The name to publish this loader's jar under, i.e. {@code baritone-neoforge-26.2.jar}
     */
    private String getDistFileName() {
        return String.format("%s-%s.jar", this.artifactName, this.artifactVersion);
    }

    private static synchronized String sha1(Path path) {
        try {
            if (SHA1_DIGEST == null) {
                SHA1_DIGEST = MessageDigest.getInstance("SHA-1");
            }
            return bytesToHex(SHA1_DIGEST.digest(Files.readAllBytes(path))).toLowerCase();
        } catch (Exception e) {
            // haha no thanks
            throw new IllegalStateException(e);
        }
    }

    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    public static String bytesToHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }
}
