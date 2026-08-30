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

package baritone.process.elytra;

import baritone.api.utils.BetterBlockPos;
import dev.babbaj.pathfinder.PathSegment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Brady
 */
public final class UnpackedSegment {

    private final Stream<BetterBlockPos> path;
    private final boolean finished;

    public UnpackedSegment(Stream<BetterBlockPos> path, boolean finished) {
        this.path = path;
        this.finished = finished;
    }

    public UnpackedSegment append(Stream<BetterBlockPos> other, boolean otherFinished) {
        // The new segment is only finished if the one getting added on is
        return new UnpackedSegment(Stream.concat(this.path, other), otherFinished);
    }

    public UnpackedSegment prepend(Stream<BetterBlockPos> other) {
        return new UnpackedSegment(Stream.concat(other, this.path), this.finished);
    }

    public List<BetterBlockPos> collect() {
        final List<BetterBlockPos> path = this.path.collect(Collectors.toList());

        // Remove backtracks. Built as a fresh list rather than by deleting in place: the index recorded for
        // a position has to stay valid after a truncation, and deleting from `path` while `positionFirstSeen`
        // still holds pre-deletion indices silently removes innocent waypoints between the two occurrences.
        final List<BetterBlockPos> result = new ArrayList<>(path.size());
        final Map<BetterBlockPos, Integer> positionFirstSeen = new HashMap<>();
        for (final BetterBlockPos pos : path) {
            final Integer firstSeen = positionFirstSeen.get(pos);
            if (firstSeen != null) {
                // Rewind to the first visit, dropping the loop and every position recorded inside it.
                for (int i = result.size() - 1; i > firstSeen; i--) {
                    positionFirstSeen.remove(result.remove(i));
                }
            } else {
                positionFirstSeen.put(pos, result.size());
                result.add(pos);
            }
        }

        return result;
    }

    public boolean isFinished() {
        return this.finished;
    }

    public static UnpackedSegment from(final PathSegment segment) {
        return new UnpackedSegment(
                Arrays.stream(segment.packed).mapToObj(BetterBlockPos::deserializeFromLong),
                segment.finished
        );
    }

    public static UnpackedSegment of(final List<BetterBlockPos> path, final boolean finished) {
        return new UnpackedSegment(path.stream(), finished);
    }
}
