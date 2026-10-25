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
package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.BaritoneProcessHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Follows a user-defined list of points in order. The points can be added by hand or recorded while walking.
 * Between two points Baritone still picks its own path, so denser points keep it closer to the intended line.
 */
public final class RouteProcess extends BaritoneProcessHelper {

    /**
     * Distance in blocks between automatically recorded points
     */
    private static final double RECORD_STEP = 3;

    private final List<BetterBlockPos> points = new ArrayList<>();
    private boolean following;
    private boolean loop;
    private boolean recording;
    private int index;
    private BetterBlockPos lastRecorded;

    public RouteProcess(Baritone baritone) {
        super(baritone);
    }

    // ---- editing ----

    public void add(BetterBlockPos pos) {
        points.add(pos);
    }

    public BetterBlockPos undo() {
        return points.isEmpty() ? null : points.remove(points.size() - 1);
    }

    public void clear() {
        stop();
        stopRecording();
        points.clear();
    }

    public List<BetterBlockPos> getPoints() {
        return Collections.unmodifiableList(points);
    }

    // ---- recording ----

    public void startRecording() {
        stop();
        points.clear();
        lastRecorded = null;
        recording = true;
    }

    public void stopRecording() {
        recording = false;
        lastRecorded = null;
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * Called every game tick by {@link baritone.behavior.RouteBehavior}
     */
    public void recordTick() {
        if (!recording || ctx.player() == null || ctx.world() == null) {
            return;
        }
        if (!ctx.player().onGround()) {
            return; // no points in mid-air, they would not be reachable as goals
        }
        BetterBlockPos feet = ctx.playerFeet();
        if (lastRecorded == null || distSq(feet, lastRecorded) >= RECORD_STEP * RECORD_STEP) {
            points.add(feet);
            lastRecorded = feet;
        }
    }

    private static double distSq(BetterBlockPos a, BetterBlockPos b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- following ----

    public boolean start(boolean loop) {
        if (points.isEmpty()) {
            return false;
        }
        stopRecording();
        this.loop = loop;
        this.index = 0;
        this.following = true;
        return true;
    }

    public void stop() {
        this.following = false;
        this.loop = false;
        this.index = 0;
    }

    @Override
    public boolean isActive() {
        return following;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (points.isEmpty()) {
            onLostControl();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        if (index >= points.size()) {
            index = 0;
        }
        if (calcFailed) {
            logDirect("Не смог построить путь до точки " + (index + 1) + ", маршрут остановлен");
            onLostControl();
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        Goal goal = new GoalBlock(points.get(index));
        if (goal.isInGoal(ctx.playerFeet())) {
            index++;
            if (index >= points.size()) {
                if (!loop) {
                    logDirect("Маршрут пройден");
                    onLostControl();
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
                index = 0;
            }
            goal = new GoalBlock(points.get(index));
        }
        return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        stop();
    }

    @Override
    public String displayName0() {
        return "Route " + (index + 1) + "/" + points.size();
    }

    // ---- files ----

    private Path routesDir() throws IOException {
        Path dir = baritone.getDirectory().resolve("routes");
        Files.createDirectories(dir);
        return dir;
    }

    private static String clean(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "");
    }

    /**
     * @return the saved name, or null if the name is empty after cleaning
     */
    public String save(String rawName) throws IOException {
        String name = clean(rawName);
        if (name.isEmpty() || points.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(points.size());
        for (BetterBlockPos p : points) {
            lines.add(p.x + " " + p.y + " " + p.z);
        }
        Files.write(routesDir().resolve(name + ".txt"), lines);
        return name;
    }

    /**
     * @return the number of loaded points, or -1 if the file does not exist
     */
    public int load(String rawName) throws IOException {
        Path file = routesDir().resolve(clean(rawName) + ".txt");
        if (!Files.exists(file)) {
            return -1;
        }
        List<BetterBlockPos> loaded = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] t = line.split("\\s+");
            loaded.add(new BetterBlockPos(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2])));
        }
        stop();
        stopRecording();
        points.clear();
        points.addAll(loaded);
        return loaded.size();
    }

    public List<String> savedNames() throws IOException {
        try (Stream<Path> s = Files.list(routesDir())) {
            return s.map(p -> p.getFileName().toString())
                    .filter(f -> f.endsWith(".txt"))
                    .map(f -> f.substring(0, f.length() - 4))
                    .sorted()
                    .toList();
        }
    }
}
